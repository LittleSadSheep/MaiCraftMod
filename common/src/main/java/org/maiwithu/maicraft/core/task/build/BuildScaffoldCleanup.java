// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.moves.AimGeometry;
import java.util.LinkedHashSet;
import net.minecraft.world.level.block.BaseFireBlock;

/** 只读清理站位规划；实际导航仍须核实并走完每条路线。 */
final class BuildScaffoldCleanup {
    record Candidate(BlockPos cell, Vec3 feet) {}
    private final LocalPlayer player;
    private final BlockPos target;
    private final LongSet inheritedForbidden;
    private final List<BlockPos> cells = new ArrayList<>();
    private final Set<BlockPos> rejected = new HashSet<>();
    private final Set<BlockPos> routeCells = new LinkedHashSet<>();
    private int cursor, offered;
    private int failedRoutes;
    private String lastRouteFailure = "";
    private Candidate lastCandidate;

    BuildScaffoldCleanup(LocalPlayer player, BlockPos target, LongSet forbidden) {
        this.player = player; this.target = target.immutable();
        inheritedForbidden = new LongOpenHashSet(forbidden);
        int radius = Math.min(6, (int) Math.ceil(AimGeometry.blockReachDistance(player)));
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++)
            for (int y = -radius - 1; y <= radius; y++) cells.add(target.offset(x, y, z));
        cells.sort(Comparator.comparingDouble(p -> Vec3.atBottomCenterOf(p).distanceToSqr(player.position())));
    }

    boolean exhausted() { return cursor >= cells.size() || offered >= 24; }
    void rejectCurrent() { rejected.add(PlayerNav.playerFeet(player).immutable()); }

    // 回收站位有视线但走不过去时，保留实际导航原因，避免只剩“搜索耗尽”而无法判断被哪里挡住。
    void routeFailed(String reason) {
        failedRoutes++;
        lastRouteFailure = reason == null ? "navigation failed" : reason.substring(0, Math.min(240, reason.length()));
        // 同一次导航已经比较整组可见站位；整组无路后不能拆成单个目标再重复寻路。
        routeCells.clear();
    }

    NavGoal goal() {
        // 每刻继续有界观察，把这一根支撑的所有安全站位交给一次路线比较，交互仍由真实到达后的射线确认。
        if (!exhausted()) next();
        if (!exhausted()) return null;
        var goals = routeCells.stream().filter(cell -> !rejected.contains(cell)).map(NavGoal::exact).toList();
        return goals.isEmpty() ? null : NavGoal.composite(goals);
    }

    Candidate next() {
        var physical = PhysicalObstacleSnapshot.capture(player.clientLevel, Vec3.atCenterOf(target));
        var actual = world(false);
        var removed = world(true);
        var forbidden = forbidden();
        long deadline = System.nanoTime() + 3_000_000L;
        for (int work = 0; work < 32 && !exhausted(); work++) {
            BlockPos cell = cells.get(cursor++);
            if (!rejected.contains(cell)) {
                Vec3 feet = ground(actual, forbidden, physical).stance(cell);
                if (feet != null && ground(removed, forbidden, physical).clear(feet, feet)
                        && visible(world(false), feet.add(0, player.getEyeHeight(Pose.STANDING), 0))) {
                    offered++;
                    lastCandidate = new Candidate(cell, feet);
                    routeCells.add(cell.immutable());
                    return lastCandidate;
                }
            }
            if (System.nanoTime() >= deadline) break;
        }
        return null;
    }

    boolean ready() {
        if (!player.onGround() || rejected.contains(PlayerNav.playerFeet(player))) return false;
        var physical = PhysicalObstacleSnapshot.capture(player.clientLevel, player.position());
        var forbidden = forbidden();
        Vec3 feet = player.position();
        // 必须同时保证角色当前身体位置和移除脚手架后的支撑位置安全，并检查格子交界处。
        return ground(world(false), forbidden, physical).clear(feet, feet)
                && ground(world(true), forbidden, physical).clear(feet, feet)
                && visible(world(false), player.getEyePosition());
    }

    Map<String, Object> evidence() {
        var data = new LinkedHashMap<String, Object>(Map.of("checked_stances", cursor, "candidate_stances", cells.size(),
                "offered_stances", offered, "rejected_actual_stances", rejected.size(),
                "search_complete", exhausted(), "routing", "existing_footing_only"));
        // 支撑可能在蓝图外，单靠正式目标序号无法定位；把本次目标、候选落脚高度与路由失败一起记入施工证据。
        data.put("target", List.of(target.getX(), target.getY(), target.getZ()));
        data.put("failed_routes", failedRoutes);
        data.put("grouped_route_stances", routeCells.size());
        data.put("last_route_failure", lastRouteFailure);
        if (lastCandidate != null) data.put("candidate_feet", List.of(lastCandidate.feet.x, lastCandidate.feet.y, lastCandidate.feet.z));
        return data;
    }

    private LongSet forbidden() {
        var all = new LongOpenHashSet(inheritedForbidden);
        all.addAll(NavigationSafetyContext.forbiddenBodyCells());
        return all;
    }

    private BuildSupportWorld world(boolean removed) {
        return new BuildSupportWorld(player.level(), player.level()::isLoaded,
                removed ? Map.of(target, Blocks.AIR.defaultBlockState()) : Map.of());
    }

    private GroundCorridor ground(BuildSupportWorld world, LongSet forbidden, PhysicalObstacleSnapshot physical) {
        return new GroundCorridor(world, player.level()::isLoaded, player.getBbWidth(),
                Math.max(player.getBbHeight(), player.getDimensions(Pose.STANDING).height()), forbidden, physical);
    }

    private boolean visible(BuildSupportWorld world, Vec3 eye) {
        try {
            if (!player.level().isLoaded(target)) return false;
            var state = world.getBlockState(target);
            if (state.isAir()) return false;
            VoxelShape shape = state.getShape(world, target);
            if (shape.isEmpty()) shape = Shapes.block();
            VoxelShape collision = state.getCollisionShape(world, target);
            // 与 BlockDigger.digTargetStep 保持完全相同的七个探测点；更密集的搜索会承诺原生挖掘器之后无法复现的射击角度。
            List<Vec3> aims = new ArrayList<>();
            Vec3 center = collision.isEmpty() ? Vec3.atCenterOf(target) : point(collision, .5, .5, .5);
            if (!collision.isEmpty() && state.getBlock() instanceof BaseFireBlock)
                center = new Vec3(center.x, target.getY(), center.z);
            aims.add(center);
            for (Direction face : Direction.values()) aims.add(point(shape,
                    .5 + face.getStepX() * .5, .5 + face.getStepY() * .5, .5 + face.getStepZ() * .5));
            for (Vec3 aim : aims) {
                Vec3 direction = aim.subtract(eye);
                if (direction.lengthSqr() < 1e-8) continue;
                var hit = world.clip(new ClipContext(eye,
                        eye.add(direction.normalize().scale(AimGeometry.blockReachDistance(player))),
                        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)
                        && !world.sawUnloaded()) return true;
            }
        } catch (RuntimeException | LinkageError unavailable) { return false; }
        return false;
    }

    private Vec3 point(VoxelShape shape, double x, double y, double z) {
        return new Vec3(target.getX() + shape.min(Direction.Axis.X) * x + shape.max(Direction.Axis.X) * (1 - x),
                target.getY() + shape.min(Direction.Axis.Y) * y + shape.max(Direction.Axis.Y) * (1 - y),
                target.getZ() + shape.min(Direction.Axis.Z) * z + shape.max(Direction.Axis.Z) * (1 - z));
    }
}
