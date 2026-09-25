// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.moves.AimGeometry;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.pathing.moves.movements.BuildPlacementRegistry;
import org.maiwithu.maicraft.entity.InputDriver;
import java.util.LinkedHashMap;
import org.maiwithu.maicraft.core.build.BuildingBudgets;

/** 一次有界回收接近：原生搭垫脚块到已验证站位，途中所有观察到的现成方块均禁止拆改。 */
final class BuildScaffoldCleanupAccess implements PlayerNav.ContextProvider, BuildPlacementRegistry.Provider {
    enum Status { RUNNING, READY, FAILED }
    private static final int DEADLINE_TICKS = 600, SNAPSHOT_PER_TICK = 512;
    private final LocalPlayer player;
    private final Level level;
    private final NavGoal goal;
    private final BooleanSupplier ready;
    private final PlayerNav.ContextProvider parent;
    private final BuildPlacementRegistry.Provider owner;
    private final Scope scope;
    private final long deadline;
    private PlayerNav nav;
    private Status status = Status.RUNNING, finishing;
    private String failure = "", phase = "observing_protected_terrain";
    private long lastObservation = Long.MIN_VALUE, lastSnapshotTick = Long.MIN_VALUE;
    private boolean acquiredBody;
    private int totalReads, confirmedSupports;

    BuildScaffoldCleanupAccess(LocalPlayer player, NavGoal candidate, BooleanSupplier ready, PlayerNav.ContextProvider parent) {
        this(player, candidate, ready, parent, null, null);
    }
    BuildScaffoldCleanupAccess(LocalPlayer player, NavGoal candidate, BooleanSupplier ready, PlayerNav.ContextProvider parent,
                               BlockPos siteMin, BlockPos siteMax) {
        this.player = Objects.requireNonNull(player); level = player.level(); goal = Objects.requireNonNull(candidate);
        this.ready = Objects.requireNonNull(ready); this.parent = Objects.requireNonNull(parent);
        if (!(parent instanceof BuildPlacementRegistry.Provider provider))
            throw new IllegalArgumentException("cleanup construction access requires the original scaffold ownership provider");
        owner = provider; deadline = level.getGameTime() + DEADLINE_TICKS;
        scope = new Scope(level, PlayerNav.playerFeet(player), candidate, siteMin, siteMax);
    }

    Status tick() {
        if (status != Status.RUNNING) return status;
        if (player.level() != level || ClientRuntime.actor().activeContext().filter(c -> c.player() == player && c.isCurrent()
                && c.body().automationOwnsControls()).isEmpty()) {
            if (nav != null) nav.abandon(); failure = "cleanup_access_body_changed"; return status = Status.FAILED;
        }
        if (finishing != null) return settleStop();
        if (level.getGameTime() >= deadline) return fail("cleanup_access_deadline");
        if (!scope.inside(PlayerNav.playerFeet(player))) return fail("cleanup_access_left_observed_scope");
        if (!scope.complete()) {
            if (lastSnapshotTick == level.getGameTime()) return Status.RUNNING;
            if (!EmbeddedBaritoneRuntime.yieldActiveForExternalAction(player)) return Status.RUNNING;
            acquiredBody = true;
            InputDriver.halt(player);
            totalReads += scope.scan(SNAPSHOT_PER_TICK);
            lastSnapshotTick = level.getGameTime();
            if (scope.failure != null) return fail(scope.failure);
            if (!scope.complete()) return Status.RUNNING;
        }
        // 每刻先刷新整个原生交互半径，再让导航取动作；旧空气变成外来墙或机器时也立刻列为不可拆。
        if (lastObservation != level.getGameTime()) {
            double reach = AimGeometry.blockReachDistance(player);
            // 装备增加触距时照常清理；局部观察保持有限范围，累计读格数量不再决定是否允许角色继续动作。
            int radius = Math.max(1, Math.min(8, (int) Math.ceil(reach) + 1));
            int reads = scope.refresh(BlockPos.containing(player.getEyePosition()), radius);
            totalReads += reads; lastObservation = level.getGameTime();
            if (scope.failure != null) return fail(scope.failure);
        }
        if (ready.getAsBoolean()) return finish(Status.READY);
        if (nav == null) {
            nav = PlayerNav.toGoal(player, () -> goal, .8, ready, this).walkingOnly(); phase = "approaching_with_native_supports";
        }
        return switch (nav.tick()) {
            case RUNNING -> Status.RUNNING;
            case FAILED -> fail("cleanup_access_no_path: " + nav.failReason());
            case ARRIVED -> ready.getAsBoolean() ? finish(Status.READY) : fail("cleanup_access_arrival_not_ready");
        };
    }

    // FOT 必须在本次接近期间登记这个代理，结束并安全交接后恢复原 Provider；否则原生确认会因身份不符被丢弃。
    BuildPlacementRegistry.Provider provider() { return this; }
    boolean isSafeToCancel() { return nav == null || nav.isSafeToCancel(); }
    void stop() { if (status == Status.RUNNING) fail("cleanup_access_stopped"); }
    String failure() { return failure; }
    long deadline() { return deadline; }
    private Status fail(String reason) { if (failure.isEmpty()) failure = reason; return finish(Status.FAILED); }
    private Status finish(Status result) {
        if (finishing == null) { finishing = result; if (nav != null) nav.stop(); }
        return settleStop();
    }
    private Status settleStop() {
        // 期限到也不突然撤掉正在踏阶／下落的原生动作；旧导航的安全收尾继续持有身体，不开启第二条路线。
        if (!isSafeToCancel()) { phase = "waiting_for_safe_stop"; return Status.RUNNING; }
        if (acquiredBody) InputDriver.halt(player);
        status = finishing; phase = status == Status.READY ? "ready" : "failed"; return status;
    }

    @Override public TerrainPermit permit() { return TerrainPermit.TERRAFORM; }
    @Override public int minimumFeetY() { return Math.max(parent.minimumFeetY(), scope.min.getY() + 1); }
    @Override public LongSet embeddedProtectedMutationCells() {
        var result = new LongOpenHashSet(parent.embeddedProtectedMutationCells()); result.addAll(NavigationSafetyContext.protectedMutationCells());
        result.addAll(scope.protectedCells); return LongSets.unmodifiable(result);
    }
    @Override public LongSet embeddedForbiddenBodyCells() {
        var result = new LongOpenHashSet(parent.embeddedForbiddenBodyCells()); result.addAll(NavigationSafetyContext.forbiddenBodyCells());
        result.addAll(scope.fence); return LongSets.unmodifiable(result);
    }
    @Override public BlockState desiredState(BlockPos pos) { return owner.desiredState(pos); }
    @Override public boolean acceptsPlacement(BlockPos pos, BlockState state) { return owner.acceptsPlacement(pos, state); }
    @Override public boolean permitsTemporaryScaffold(BlockPos pos) {
        return scope.complete() && scope.inside(pos) && level.isLoaded(pos) && level.getBlockState(pos).isAir()
                && !embeddedProtectedMutationCells().contains(pos.asLong()) && !embeddedForbiddenBodyCells().contains(pos.asLong())
                && owner.permitsTemporaryScaffold(pos);
    }
    @Override public boolean permitsScaffoldSupport(BlockPos clicked, BlockPos pos, BlockState support) {
        if (!permitsTemporaryScaffold(pos) || !scope.inside(clicked) || !level.isLoaded(clicked)
                || !level.getBlockState(clicked).equals(support)) return false;
        boolean inherited = parent.embeddedProtectedMutationCells().contains(clicked.asLong())
                || parent.embeddedForbiddenBodyCells().contains(clicked.asLong()) || NavigationSafetyContext.protectsMutation(clicked)
                || NavigationSafetyContext.forbidsBody(clicked) || NavigationSafetyContext.protectsUse(clicked);
        if (inherited) return owner.permitsScaffoldSupport(clicked, pos, support);
        // 本模块加的是“禁拆”，不禁止把普通草地或新土柱当支点。原主人保护仍只由原 Provider 批准。
        // 容器／工作台等右键会开界面的支点不走这个例外，避免把施工点击变成意外菜单交互。
        return !support.hasBlockEntity() && support.getFluidState().isEmpty() && support.isCollisionShapeFullBlock(level, clicked)
                && support.getMenuProvider(level, clicked) == null;
    }
    @Override public Map<Item, Integer> scaffoldReservations() { return owner.scaffoldReservations(); }
    @Override public void confirmedScaffold(BlockPos pos, BlockState state) { confirmedSupports++; owner.confirmedScaffold(pos, state); }
    @Override public void confirmedScaffoldRemoval(BlockPos pos) { owner.confirmedScaffoldRemoval(pos); }

    Map<String, Object> evidence() {
        var out = new LinkedHashMap<String, Object>();
        out.put("phase", phase); out.put("failure", failure); out.put("read_blocks", totalReads);
        out.put("snapshot_cells", scope.volume); out.put("snapshot_complete", scope.complete()); out.put("protected_observed_blocks", scope.protectedCells.size());
        out.put("scope_min", List.of(scope.min.getX(), scope.min.getY(), scope.min.getZ()));
        out.put("scope_max", List.of(scope.max.getX(), scope.max.getY(), scope.max.getZ()));
        out.put("confirmed_new_supports", confirmedSupports); out.put("remaining_ticks", Math.max(0, deadline - level.getGameTime()));
        out.put("safe_to_cancel", isSafeToCancel()); out.put("route_created", nav != null);
        if (nav != null) { out.put("planning", nav.planningInFlight()); out.put("navigation", nav.outcomeSummary()); }
        return Map.copyOf(out);
    }

    /** 全部已验证候选与起点周围的有限盒；外壳阻止搜索离开观察范围，未知区块不被当作可施工空气。 */
    static final class Scope {
        final Level level;
        final BlockPos min, max;
        final long volume;
        final LongOpenHashSet protectedCells = new LongOpenHashSet(), fence = new LongOpenHashSet();
        private final Iterator<BlockPos> pending;
        String failure;
        Scope(Level level, BlockPos start, NavGoal candidate) {
            this(level, start, candidate, null, null);
        }
        Scope(Level level, BlockPos start, NavGoal candidate, BlockPos siteMin, BlockPos siteMax) {
            this.level = level; var cells = new ArrayList<BlockPos>(); collect(candidate, cells, 0); cells.add(start);
            if ((siteMin == null) != (siteMax == null)) throw new IllegalArgumentException("cleanup site bounds need both corners");
            // 把整栋房子的前门和既有楼梯绕行空间一并留下，不能用起点到高处目标的窄直盒封死正常出口。
            if (siteMin != null) { cells.add(siteMin); cells.add(siteMax); }
            if (cells.stream().anyMatch(p -> Math.abs((long) p.getX()) > 30_000_000 || Math.abs((long) p.getZ()) > 30_000_000))
                throw new IllegalArgumentException("cleanup access coordinates exceed world bounds");
            int minX = cells.stream().mapToInt(BlockPos::getX).min().orElseThrow() - 5, maxX = cells.stream().mapToInt(BlockPos::getX).max().orElseThrow() + 5;
            int minZ = cells.stream().mapToInt(BlockPos::getZ).min().orElseThrow() - 5, maxZ = cells.stream().mapToInt(BlockPos::getZ).max().orElseThrow() + 5;
            int minY = Math.max(level.getMinBuildHeight(), cells.stream().mapToInt(BlockPos::getY).min().orElseThrow() - 3);
            int maxY = Math.min(level.getMaxBuildHeight() - 1, cells.stream().mapToInt(BlockPos::getY).max().orElseThrow() + 4);
            min = new BlockPos(minX, minY, minZ); max = new BlockPos(maxX, maxY, maxZ);
            volume = ((long) maxX - minX + 1) * ((long) maxY - minY + 1) * ((long) maxZ - minZ + 1);
            // 大厅清理仍分帧观察完整通行范围；其容量独立于目标数，并明确由客户端配置限制。
            if (volume <= 0 || volume > BuildingBudgets.current().maxCleanupAccessCells()
                    || cells.stream().anyMatch(pos -> !inside(pos)))
                throw new IllegalArgumentException("cleanup access requires a bounded loaded local region");
            pending = BlockPos.betweenClosed(min, max).iterator();
        }
        private static void collect(NavGoal goal, List<BlockPos> cells, int depth) {
            if (depth > 2 || cells.size() >= 24) throw new IllegalArgumentException("too many cleanup access candidates");
            if (goal instanceof NavGoal.Exact exact) cells.add(exact.goal);
            else if (goal instanceof NavGoal.Composite group) for (NavGoal member : group.members) collect(member, cells, depth + 1);
            else throw new IllegalArgumentException("cleanup access only accepts previously checked exact stance goals");
        }
        boolean inside(BlockPos pos) {
            return pos.getX() > min.getX() && pos.getX() < max.getX() && pos.getY() > min.getY() && pos.getY() < max.getY()
                    && pos.getZ() > min.getZ() && pos.getZ() < max.getZ();
        }
        boolean complete() { return failure == null && !pending.hasNext(); }
        int scan(int budget) {
            int reads = 0;
            while (failure == null && pending.hasNext() && reads < Math.max(0, Math.min(SNAPSHOT_PER_TICK, budget))) {
                BlockPos cell = pending.next(); if (!inside(cell)) fence.add(cell.asLong());
                if (!observe(cell)) break; reads++;
            }
            return reads;
        }
        int refresh(BlockPos eye, int radius) {
            if (radius < 1 || radius > 7) { failure = "cleanup_access_read_radius"; return 0; }
            int reads = 0;
            for (BlockPos cell : BlockPos.betweenClosed(eye.offset(-radius, -radius, -radius), eye.offset(radius, radius, radius))) {
                if (!observe(cell)) break; reads++;
            }
            return reads;
        }
        private boolean observe(BlockPos cell) {
            if (level.isOutsideBuildHeight(cell)) { protectedCells.add(cell.asLong()); return true; }
            if (!level.isLoaded(cell) || !level.getWorldBorder().isWithinBounds(cell)) { failure = "cleanup_access_unobserved_terrain"; return false; }
            if (!level.getBlockState(cell).isAir()) protectedCells.add(cell.asLong());
            return true;
        }
    }
}
