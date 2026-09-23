// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.core.integration.create.transmission.KineticRouteGeometry.*;
import java.util.Arrays;

/** 正交传动轴候选通过真实齿轮箱转向；齿轮箱轴线采用未使用的轴向。 */
final class KineticShaftGeometry {
    static final List<List<Direction.Axis>> ORDERS = List.of(
            List.of(Direction.Axis.X, Direction.Axis.Y, Direction.Axis.Z), List.of(Direction.Axis.X, Direction.Axis.Z, Direction.Axis.Y),
            List.of(Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z), List.of(Direction.Axis.Y, Direction.Axis.Z, Direction.Axis.X),
            List.of(Direction.Axis.Z, Direction.Axis.X, Direction.Axis.Y), List.of(Direction.Axis.Z, Direction.Axis.Y, Direction.Axis.X));
    private KineticShaftGeometry() {}
    static List<Plan> candidates(Endpoint source, Direction sourceFace, Endpoint target, Direction targetFace, Terrain terrain, Limits limits) {
        List<Plan> plans = new ArrayList<>(); BlockPos a = source.position().relative(sourceFace), b = target.position().relative(targetFace);
        for (var order : ORDERS) {
            var path = new ArrayList<BlockPos>(); path.add(source.position()); path.add(a);
            append(path, b, order, limits.maxPlacements()); path.add(target.position());
            add(plans, compile(source, sourceFace, target, targetFace, terrain, limits, path));
        }
        // 少量明确绕行路线可让同向输出端和障碍路线与高架链传动方案公平竞争。
        for (Direction detour : Direction.values()) {
            var path = new ArrayList<BlockPos>(); path.add(source.position()); path.add(a);
            append(path, a.relative(detour, 2), ORDERS.getFirst(), limits.maxPlacements());
            append(path, b.relative(detour, 2), ORDERS.getFirst(), limits.maxPlacements());
            append(path, b, ORDERS.getFirst(), limits.maxPlacements()); path.add(target.position());
            add(plans, compile(source, sourceFace, target, targetFace, terrain, limits, path));
        }
        return List.copyOf(plans);
    }
    private static Plan compile(Endpoint source, Direction sourceFace, Endpoint target, Direction targetFace, Terrain terrain, Limits limits, List<BlockPos> path) {
        if (path.size() < 3 || path.size() - 2 > limits.maxPlacements() || new HashSet<>(path).size() != path.size()) return null;
        KineticGeometryWork work = new KineticGeometryWork(source, sourceFace, target, targetFace, terrain, limits);
        installPath(work, path);
        boolean turning = work.blocks.values().stream().anyMatch(p -> p.blockId().equals("create:gearbox"));
        String family = turning ? "shaft_gearbox" : source.family().contains("cogwheel") || target.family().contains("cogwheel") ? "gear_shaft" : "axial_shaft";
        return work.finish(family);
    }
    /** 两端点已经存在或会单独安装；这里只生成中间方块格。 */
    static void installPath(KineticGeometryWork work, List<BlockPos> path) {
        if (path.size() < 2 || path.size() > work.limits.maxPlacements() + 2 || new HashSet<>(path).size() != path.size()) { work.valid = false; return; }
        for (int i = 1; i < path.size(); i++) work.join(path.get(i - 1), path.get(i));
        for (int i = 1; i + 1 < path.size(); i++) {
            Direction before = KineticRouteGeometry.between(path.get(i - 1), path.get(i)), after = KineticRouteGeometry.between(path.get(i), path.get(i + 1));
            if (before == null || after == null || before == after.getOpposite()) { work.valid = false; return; }
            if (before.getAxis() == after.getAxis()) work.put(path.get(i), "create:shaft", Map.of("axis", before.getAxis().getName()));
            else {
                Direction.Axis unused = Arrays.stream(Direction.Axis.values()).filter(axis -> axis != before.getAxis() && axis != after.getAxis()).findFirst().orElseThrow();
                work.put(path.get(i), "create:gearbox", Map.of("axis", unused.getName()));
            }
        }
    }
    static void append(List<BlockPos> path, BlockPos target, List<Direction.Axis> order, int maximum) {
        for (Direction.Axis axis : order) {
            BlockPos from = path.getLast();
            int distance = axis.choose(target.getX() - from.getX(), target.getY() - from.getY(), target.getZ() - from.getZ());
            Direction face = Direction.fromAxisAndDirection(axis, distance < 0 ? Direction.AxisDirection.NEGATIVE : Direction.AxisDirection.POSITIVE);
            for (int step = 0; step < Math.abs(distance); step++) {
                if (path.size() > maximum + 2) return;
                path.add(path.getLast().relative(face));
            }
        }
    }
    private static void add(List<Plan> plans, Plan plan) { if (plan != null) plans.add(plan); }
}
