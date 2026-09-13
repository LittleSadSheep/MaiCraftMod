// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import sun.misc.Unsafe;

/** 按实机原坐标重建外檐：先证明现有石英顶可直接潜行扩檐，再检查每块垫块只能依赖已成立的前缀。 */
public final class BuildEavePlacementProofTest {
    private static final BlockPos QUARTZ = new BlockPos(4, 75, 9), FIRST = new BlockPos(4, 74, 9), SECOND = new BlockPos(4, 74, 10);
    private static final BlockPos FAR_QUARTZ = new BlockPos(4, 75, 10);
    private static final Vec3 ORIGIN = new Vec3(8.3459795, 76, 8.5005297);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        originalEaveDirectPlacementNeedsNoDirt();
        supportsAreProvedInPrefixOrder();
        confirmedFirstSupportIsRealForTheSecond();
        uniqueExistingStancesHaveAFiniteRouteBudget();
        System.out.println("BuildEavePlacementProofTest: passed");
    }

    private static void originalEaveDirectPlacementNeedsNoDirt() throws Exception {
        try (var f = new Fixture()) {
            var target = quartz(QUARTZ); var world = f.view();
            check(BuildPlacementGeometry.projectedGestureFrom(f.h.player, target, world, f.level::isLoaded, ORIGIN) == null,
                    "the original centered eave position cannot see the needed outward side through the quartz cap");
            var search = f.search(target, world, ORIGIN, true); finish(search);
            check(search.accepted(), "the actual (4,75,9) quartz has a reachable native crouching edge placement; "
                    + searchDiagnostics(f, target, world, search));
            var access = search.access();
            check(access.edge() && access.gesture().sneak() && access.feet().y == 76 && access.feet().z > 9,
                    "the useful stance retains continuous outward position and native crouching eye height");
            check(access.gesture().clicked().equals(QUARTZ.north()) && access.gesture().face() == net.minecraft.core.Direction.SOUTH,
                    "direct extension clicks the existing north quartz block's actual south face");
            // 除了计划标签，还让原生外形射线验证首先命中该侧面，防止从顶面背后“点穿”同一个支撑方块。
            Vec3 eye = access.feet().add(0, f.h.player.getEyeHeight(Pose.CROUCHING), 0);
            // 原生准星继续投到交互距离，不能恰好截断在边界上而把合法首个交点变成终点 MISS。
            Vec3 rayEnd = eye.add(access.gesture().point().subtract(eye).normalize().scale(4.5));
            var hit = f.level.clip(new ClipContext(eye, rayEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, f.h.player));
            check(hit.getBlockPos().equals(access.gesture().clicked()) && hit.getDirection() == access.gesture().face(),
                    "the native first shape intersection must be the declared outward click face");
            check(f.clear(access.approach(), access.feet()) && access.approach().distanceToSqr(access.feet()) <= .7 * .7,
                    "the entire micro-movement keeps real body support and collision clearance");
            check(f.level.getBlockState(FIRST).isAir() && f.level.getBlockState(QUARTZ).isAir() && world.unchanged(),
                    "finding a direct eave gesture cannot preplace dirt, quartz, or any hypothetical floor");
            check(f.h.blockUses() == 0 && f.h.itemUses() == 0 && f.h.player.position().equals(ORIGIN),
                    "the proof performs no native action or body relocation");
        }
    }

    private static void supportsAreProvedInPrefixOrder() throws Exception {
        try (var f = new Fixture()) {
            var target = quartz(FAR_QUARTZ);
            var ordered = f.support(target, List.of(FIRST, SECOND)); finish(ordered);
            check(ordered.accepted() && ordered.placementFor(FIRST) != null && ordered.placementFor(SECOND) != null,
                    "the two-support chain needs an executable placement witness for each block");
            check(ordered.evidence().get("verified_support_steps").equals(2)
                    && ((Number) ordered.evidence().get("reachable_stances")).intValue() <= 512,
                    "prerequisite proofs share the original finite reachable-stance budget");
            var reversed = f.support(target, List.of(SECOND, FIRST)); finish(reversed);
            check(!reversed.accepted() && reversed.evidence().get("reason").toString().startsWith("support_step_not_placeable:"),
                    "the unconnected outer support cannot borrow the later inner support as its click face");
            check(reversed.placementFor(SECOND) == null && f.level.getBlockState(FIRST).isAir() && f.level.getBlockState(SECOND).isAir(),
                    "a rejected prefix submits no support and retains no executable witness for it");
            // 让身体处在只有全部假设垫块才能托住的位置，旧的整链投影会显得可站；新第一步必须拒绝。
            f.h.position(new Vec3(4.5, 75, 9.5));
            var floating = f.support(target, List.of(FIRST, SECOND)); finish(floating);
            check(!floating.accepted() && floating.evidence().get("verified_support_steps").equals(0),
                    "an origin supported only by an unplaced future block cannot start the construction sequence");
        }
    }

    private static void confirmedFirstSupportIsRealForTheSecond() throws Exception {
        try (var f = new Fixture()) {
            var target = quartz(FAR_QUARTZ); var firstProof = f.support(target, List.of(FIRST, SECOND)); finish(firstProof);
            check(firstProof.accepted(), "the initial sequence has complete prerequisites");
            var record = new BuildTaskRecord("confirmed-prefix", 1000, List.of(target), false, true);
            var task = new FirstPersonBuildCompanionTask(f.h.player, record);
            // 此处只注入“原生确认后”的世界/账本回调，不模拟客户端施工；随后确实走新的剩余支撑证明。
            f.level.states.put(FIRST, Blocks.DIRT.defaultBlockState()); task.confirmedScaffold(FIRST, Blocks.DIRT.defaultBlockState());
            check(!firstProof.current() && record.scaffoldLedger().owns(FIRST, Blocks.DIRT.defaultBlockState()),
                    "the old all-air proof is stale, while the confirmed prefix has actual ownership");
            var secondProof = f.support(target, List.of(SECOND)); finish(secondProof);
            check(secondProof.accepted() && secondProof.current() && secondProof.placementFor(SECOND) != null,
                    "the second click is proved against real confirmed first dirt and only the remaining air proposal");
            f.level.states.put(SECOND, Blocks.STONE.defaultBlockState());
            check(!secondProof.current() && secondProof.invalidationReason().equals("support_projection_site_changed"),
                    "someone else's new block in the second location invalidates the pending click");
            var occupied = f.support(target, List.of(SECOND)); finish(occupied);
            check(!occupied.accepted() && f.level.getBlockState(SECOND).is(Blocks.STONE) && !record.scaffoldLedger().contains(SECOND),
                    "rechecking must not clear or claim an unowned replacement block");
            f.level.states.put(SECOND, Blocks.DIRT.defaultBlockState()); task.confirmedScaffold(SECOND, Blocks.DIRT.defaultBlockState());
            var finalPlacement = f.search(target, f.view(), ORIGIN, false); finish(finalPlacement);
            check(finalPlacement.accepted() && record.scaffoldLedger().owns(SECOND, Blocks.DIRT.defaultBlockState()),
                    "after the second actual confirmation the final target is reachable using the two real supports");
            check(f.h.blockUses() == 0 && f.h.itemUses() == 0, "proof and confirmation-fixture callbacks never issue another click");
        }
    }

    private static void uniqueExistingStancesHaveAFiniteRouteBudget() throws Exception {
        try (var f = new Fixture()) {
            var routes = new BuildStanceNavigation(PlayerNav.ContextProvider.DEFAULT); routes.startAt(BlockPos.containing(ORIGIN));
            check(routes.existingFooting(f.h.player, new BlockPos(4, 76, 8))
                    && !routes.existingFooting(f.h.player, new BlockPos(4, 76, 9)),
                    "a real eave anchor is valid, but the outward empty cell center is not a navigation landing");
            BlockPos first = new BlockPos(2, 76, 8); check(routes.claimRoute(first) && !routes.claimRoute(first), "face sample variants cannot resubmit one route");
            for (int x = 3; x <= 9; x++) {
                BlockPos at = new BlockPos(x, 76, 8); check(routes.existingFooting(f.h.player, at) && routes.claimRoute(at), "only physically supported distinct candidates spend route slots");
            }
            check(!routes.claimRoute(new BlockPos(10, 76, 8)), "the target cannot expand to eighty or more real route attempts");
            routes.nextExistingPass(); check(!routes.claimRoute(first), "a looser existing-footing pass cannot reset the overall target budget");
            routes.environmentChanged(); check(routes.claimRoute(first), "confirmed changed surroundings permit a genuinely new bounded attempt");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness h = new InteractionWorldTestHarness();
        final SceneLevel level;
        Fixture() throws Exception {
            Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null); level = (SceneLevel) memory.allocateInstance(SceneLevel.class);
            level.states = new LinkedHashMap<>(); level.border = new WorldBorder();
            field(Level.class, "dimension").set(level, Level.OVERWORLD); field(Level.class, "isClientSide").setBoolean(level, true);
            field(Entity.class, "level").set(h.player, level); field(LocalPlayer.class, "clientLevel").set(h.player, level); Minecraft.getInstance().level = level;
            field(Entity.class, "dimensions").set(h.player, EntityDimensions.scalable(.6F, 1.8F)); h.position(ORIGIN); h.player.setDeltaMovement(new Vec3(0, -.0784, 0));
            for (int x = 2; x <= 12; x++) {
                level.states.put(new BlockPos(x, 75, 8), Blocks.SMOOTH_QUARTZ.defaultBlockState());
                for (int y = 73; y <= 74; y++) level.states.put(new BlockPos(x, y, 8), x >= 5 && x <= 7 ? Blocks.GLASS.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        BuildSupportWorld view() { return new BuildSupportWorld(level, level::isLoaded, Map.of()); }
        BuildPlacementAccessSearch search(BuildTaskRecord.Target target, BuildSupportWorld world, Vec3 origin, boolean edgesOnly) {
            return new BuildPlacementAccessSearch(h.player, target, world, origin, LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY, 512, edgesOnly);
        }
        BuildSupportAccess support(BuildTaskRecord.Target target, List<BlockPos> supports) {
            return new BuildSupportAccess(h.player, target, supports, Blocks.DIRT.defaultBlockState(), supports::contains, LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY);
        }
        boolean clear(Vec3 from, Vec3 to) { return new GroundCorridor(level, level::isLoaded, .6, 1.8, LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY).clear(from, to); }
        public void close() throws Exception { h.close(); }
    }
    private static final class SceneLevel extends ClientLevel {
        Map<BlockPos, BlockState> states; WorldBorder border;
        private SceneLevel() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        @Override public boolean isLoaded(BlockPos pos) { return pos.getX() >= 0 && pos.getX() < 16 && pos.getZ() >= 0 && pos.getZ() < 16; }
        @Override public BlockState getBlockState(BlockPos pos) { if (!isLoaded(pos)) throw new AssertionError("unloaded read " + pos); return states.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getHeight() { return 384; }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public long getGameTime() { return 100; }
    }
    private static BuildTaskRecord.Target quartz(BlockPos pos) { return new BuildTaskRecord.Target(Blocks.SMOOTH_QUARTZ.defaultBlockState(), Items.SMOOTH_QUARTZ,
            pos, "eave", null, null, null, false, Set.of(), true, Set.of()); }
    private static Map<String, Object> searchDiagnostics(Fixture f, BuildTaskRecord.Target target, BuildSupportWorld world,
                                                        BuildPlacementAccessSearch search) throws Exception {
        // 失败时拆开实际几何、原生手法与搜索预算，不能把预算耗尽或缺失夹具字段含混解释成确实无路。
        var data = new LinkedHashMap<String, Object>(); data.put("reason", search.reason()); data.put("checked", search.checked());
        data.put("visited", search.visited()); data.put("reads", world.reads()); data.put("saw_unloaded", world.sawUnloaded());
        var corridor = (GroundCorridor) field(search.getClass(), "corridor").get(search);
        data.put("corridor_exhausted", corridor.exhausted()); data.put("corridor_remaining", field(GroundCorridor.class, "remainingReads").getInt(corridor));
        Vec3 anchor = new Vec3(4.5, 76, 8.5), edge = new Vec3(4.5, 76, 9.15);
        data.put("direct_clear", probe(() -> f.clear(anchor, edge)));
        data.put("direct_gesture", probe(() -> BuildPlacementGeometry.projectedGestureFrom(f.h.player, target, f.view(), f.level::isLoaded, edge, true)));
        data.put("origin_edge_gesture", probe(() -> BuildPlacementGeometry.projectedGestureFrom(f.h.player, target, f.view(), f.level::isLoaded,
                ORIGIN.add(0, 0, .65), true)));
        Vec3 eye = edge.add(0, f.h.player.getEyeHeight(Pose.CROUCHING), 0), point = new Vec3(4.5, 75.5, 8.9999);
        data.put("native_ray", probe(() -> f.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, f.h.player))));
        data.put("crouching_eye_height", f.h.player.getEyeHeight(Pose.CROUCHING)); data.put("body_width", f.h.player.getBbWidth());
        return data;
    }
    @FunctionalInterface private interface DiagnosticProbe { Object run() throws Exception; }
    private static String probe(DiagnosticProbe operation) {
        try { return String.valueOf(operation.run()); }
        catch (Exception failure) { return failure.getClass().getSimpleName() + ":" + failure.getMessage(); }
    }
    private static void finish(BuildPlacementAccessSearch search) { for (int i = 0; i < 4096; i++) if (search.advance(16)) return; throw new AssertionError("access search exceeded finite work"); }
    private static void finish(BuildSupportAccess search) { for (int i = 0; i < 4096; i++) if (search.advance(16)) return; throw new AssertionError("support proof exceeded finite work"); }
    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (NoSuchFieldException absent) { }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
