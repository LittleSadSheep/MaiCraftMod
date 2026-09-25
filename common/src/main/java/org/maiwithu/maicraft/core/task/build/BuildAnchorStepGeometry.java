// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.transport.TransportLanding;
import java.util.List;

/** 锚点内最多半格的原生踏阶证明；微小高阶接触只在此使用，不降低水平檐边的支撑余量。 */
final class BuildAnchorStepGeometry {
    record Check(String rejection, BlockPos cell) {
        boolean allowed() { return rejection == null; }
        Map<String, Object> evidence() {
            // 被阻挡的实际格随谓词结论返回，外层不能再把保护区域与真实碰撞都解释成没有路。
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reason", allowed() ? "verified" : rejection);
            if (cell != null) data.put("failure_position", Map.of("x", cell.getX(), "y", cell.getY(), "z", cell.getZ()));
            return Map.copyOf(data);
        }
    }
    private static final double EPS = 1e-7;
    private BuildAnchorStepGeometry() {}

    static boolean safe(LocalPlayer player, Predicate<BlockPos> loaded, LongSet forbidden, Predicate<BlockPos> permitted,
                        PhysicalObstacleSnapshot physical, Vec3 from, Vec3 to, Vec3 drift) {
        return safe(player, loaded, forbidden, permitted, physical, from, to, drift, player.getBbHeight());
    }

    // 候选姿态只改变只读的碰撞高度；必须先证明站立通道，再决定是否确实需要低顶潜行。
    static boolean safe(LocalPlayer player, Predicate<BlockPos> loaded, LongSet forbidden, Predicate<BlockPos> permitted,
                        PhysicalObstacleSnapshot physical, Vec3 from, Vec3 to, Vec3 drift, double height) {
        return check(player, loaded, forbidden, permitted, physical, from, to, drift, height).allowed();
    }

    /** 到站后的身体微调逐项保留拒绝原因；仍按原生支撑、保护、碰撞和余速顺序验证，不放宽通行条件。 */
    static Check check(LocalPlayer player, Predicate<BlockPos> loaded, LongSet forbidden, Predicate<BlockPos> permitted,
                       PhysicalObstacleSnapshot physical, Vec3 from, Vec3 to, Vec3 drift, double height) {
        double rise = to.y - from.y, width = player.getBbWidth();
        if (rise < -EPS || rise > .5 + EPS || rise > player.maxUpStep() + EPS || from.distanceToSqr(to) > .8 * .8 + EPS)
            return new Check("anchor_height_or_distance_out_of_range", BlockPos.containing(to));
        var world = new BuildSupportWorld(player.level(), loaded, Map.of());
        if (rise > EPS) {
            var oldFloor = world.getBlockState(BlockPos.containing(from.x, from.y - EPS, from.z)).getBlock();
            var nextFloor = world.getBlockState(BlockPos.containing(to.x, to.y - EPS, to.z)).getBlock();
            if (!(oldFloor instanceof StairBlock || oldFloor instanceof SlabBlock || nextFloor instanceof StairBlock || nextFloor instanceof SlabBlock))
                return new Check("step_requires_native_stair_or_slab", BlockPos.containing(to.x, to.y - EPS, to.z));
        }
        Vec3 bend = new Vec3(from.x, to.y, from.z);
        Vec3 raisedDrift = new Vec3(drift.x, to.y, drift.z);
        if (!physical.clearSegment(from, from, width, height) || !physical.clearSegment(from, bend, width, height)
                || !physical.clearSegment(bend, to, width, height) || !physical.clearSegment(bend, raisedDrift, width, height))
            return new Check("physical_structure_or_entity_collision", null);
        var terrain = new ArrayList<AABB>();
        AABB area = box(from, width, height).minmax(box(to, width, height)).minmax(box(drift, width, height)).inflate(1);
        int cells = 0;
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(area.minX, area.minY, area.minZ), BlockPos.containing(area.maxX, area.maxY, area.maxZ))) {
            if (++cells > 512) return new Check("anchor_geometry_read_budget_exceeded", cell.immutable());
            if (!loaded.test(cell)) return new Check("anchor_geometry_unloaded", cell.immutable());
            var state = world.getBlockState(cell); AABB owner = new AABB(cell);
            if ((!permitted.test(cell) || forbidden.contains(cell.asLong())) && (swept(owner, from, bend, to, width, height)
                    || swept(owner, from, bend, raisedDrift, width, height))) return new Check("anchor_body_cell_forbidden", cell.immutable());
            if (TransportLanding.unsafe(world, cell, state) && (swept(owner.inflate(EPS * 2), from, bend, to, width, height)
                    || swept(owner.inflate(EPS * 2), from, bend, raisedDrift, width, height))) return new Check("anchor_hazard_intersection", cell.immutable());
            for (AABB local : state.getCollisionShape(world, cell).toAabbs()) {
                AABB shape = local.move(cell);
                if (swept(shape, from, bend, to, width, height) || swept(shape, from, bend, raisedDrift, width, height))
                    return new Check("anchor_block_collision", cell.immutable());
                terrain.add(shape); if (terrain.size() > 512) return new Check("anchor_collision_shape_budget_exceeded", cell.immutable());
            }
        }
        if (world.sawUnloaded()) return new Check("anchor_geometry_unloaded", null);
        if (!supported(terrain, from, width) || !supported(terrain, to, width)) return new Check("anchor_foot_support_missing", null);
        // 余速也可能越过目标或侧偏；同时核对抬阶后的扫掠和两种真实台面的连续支撑，不能只验证主方向。
        if (!covered(terrain, from, to, width) || !covered(terrain, from, raisedDrift, width)
                || !supported(terrain, drift, width) && !supported(terrain, raisedDrift, width))
            return new Check("anchor_swept_support_missing", null);
        return rise > EPS || drift.distanceToSqr(from) < EPS * EPS ? new Check(null, null)
                : check(player, loaded, forbidden, permitted, physical, from, drift, from, height);
    }
    private static boolean covered(List<AABB> terrain, Vec3 from, Vec3 to, double width) {
        // 原生 step 由碰到半阶的水平移动触发；两种实际台面在整段上必须连续覆盖，不能借抬高身体越过空隙。
        var intervals = new ArrayList<double[]>();
        for (AABB shape : terrain) if (Math.abs(shape.maxY - from.y) < EPS || Math.abs(shape.maxY - to.y) < EPS) {
            double[] span = span(shape, from, to, width); if (span != null) intervals.add(span);
        }
        intervals.sort(Comparator.comparingDouble(i -> i[0])); double covered = 0;
        for (double[] span : intervals) { if (span[0] > covered + EPS) return false; covered = Math.max(covered, span[1]); }
        return covered >= 1 - EPS;
    }
    private static boolean supported(List<AABB> shapes, Vec3 feet, double width) {
        return shapes.stream().anyMatch(s -> Math.abs(s.maxY - feet.y) < EPS && s.maxX > feet.x - width / 2 + EPS
                && s.minX < feet.x + width / 2 - EPS && s.maxZ > feet.z - width / 2 + EPS && s.minZ < feet.z + width / 2 - EPS);
    }
    private static double[] span(AABB s, Vec3 from, Vec3 to, double width) {
        double half = width / 2 - EPS; double[] range = {0, 1};
        return clip(from.x, to.x - from.x, s.minX - half, s.maxX + half, range)
                && clip(from.z, to.z - from.z, s.minZ - half, s.maxZ + half, range) ? range : null;
    }
    private static boolean clip(double origin, double delta, double min, double max, double[] range) {
        if (Math.abs(delta) < EPS) return origin >= min && origin <= max;
        double a = (min - origin) / delta, b = (max - origin) / delta;
        range[0] = Math.max(range[0], Math.min(a, b)); range[1] = Math.min(range[1], Math.max(a, b)); return range[0] <= range[1];
    }
    private static boolean swept(AABB obstacle, Vec3 from, Vec3 bend, Vec3 to, double width, double height) {
        AABB expanded = new AABB(obstacle.minX - width / 2 + EPS, obstacle.minY - height + EPS, obstacle.minZ - width / 2 + EPS,
                obstacle.maxX + width / 2 - EPS, obstacle.maxY - EPS, obstacle.maxZ + width / 2 - EPS);
        return expanded.contains(from) || expanded.contains(bend) || expanded.contains(to)
                || expanded.clip(from, bend).isPresent() || expanded.clip(bend, to).isPresent();
    }
    private static AABB box(Vec3 feet, double width, double height) {
        return new AABB(feet.x - width / 2, feet.y, feet.z - width / 2, feet.x + width / 2, feet.y + height, feet.z + width / 2);
    }
}
