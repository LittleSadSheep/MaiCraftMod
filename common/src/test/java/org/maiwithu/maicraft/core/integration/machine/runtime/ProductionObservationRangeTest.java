// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Native range arithmetic for every requested target; actual Create permission calls are checked by the GameTest harness. */
public final class ProductionObservationRangeTest {
    private ProductionObservationRangeTest() {}
    public static void main(String[] args) {
        BlockPos first = BlockPos.ZERO, create = new BlockPos(4, 0, 0);
        ToDoubleFunction<BlockPos> mixed = position -> position.equals(create) ? 8 : 16;
        check(!ProductionObservationRange.ready(new Vec3(-4.5, .5, .5), List.of(first, create), mixed),
                "First position proximity ignored a Create endpoint nine blocks away");
        check(ProductionObservationRange.ready(new Vec3(2.5, .5, .5), List.of(first, create), mixed), "A common valid stance was rejected");
        check(ProductionObservationRange.goalRadius(List.of(first), ignored -> 8) == 6, "Create observation retained the old ten-block navigation goal");
        check(!ProductionObservationRange.ready(new Vec3(8.51, .5, .5), List.of(first), ignored -> 8), "Far Create read was admitted");
        check(ProductionObservationRange.ready(new Vec3(7.5, .5, .5), List.of(first), ignored -> 8), "Near Create read was rejected");
        check(ProductionObservationRange.ready(new Vec3(12, .5, .5), List.of(first), ignored -> 16), "Generic target needlessly inherited Create's eight-block limit");
        verifyGoalEnvelope(List.of(first, create), mixed);
        verifyGoalEnvelope(List.of(first, create), ignored -> 16);
        verifyGoalEnvelope(List.of(first, new BlockPos(0, 2, 0)), ignored -> 8);
        var sparse = new LinkedHashSet<BlockPos>();
        for (int station = 0; station < 4; station++) {
            sparse.add(new BlockPos(20 * station, 0, 0)); sparse.add(new BlockPos(20 * station + 3, 0, 0));
            sparse.add(new BlockPos(20 * station + 3, 2, 0));
        }
        var groups = ProductionOutputMonitor.group(sparse);
        check(groups.size() == 4, "Far stations were packed into a group without a common safe observation radius");
        for (var group : groups) verifyGoalEnvelope(group, ignored -> 8);
        var path = new ArrayList<BlockPos>(); for (int x = 0; x <= 40; x++) path.add(new BlockPos(x, 0, 0));
        var segments = ProductionConnectionPath.split(path, "kinetic");
        check(segments.size() > 1, "Far kinetic path bypassed bounded segmentation");
        for (var segment : segments) verifyGoalEnvelope(segment.points(path), ignored -> 8);
        try {
            ProductionObservationRange.goalRadius(List.of(first, new BlockPos(20, 0, 0)), ignored -> 8);
            throw new AssertionError("An infeasible group silently clamped a negative radius");
        } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("exceeds_native_range"), "Wrong group failure"); }
        System.out.println("ProductionObservationRangeTest: per-target native limits, vertical/mixed groups and four sparse stations passed");
    }

    private static void verifyGoalEnvelope(List<BlockPos> positions, ToDoubleFunction<BlockPos> limits) {
        int radius = ProductionObservationRange.goalRadius(positions, limits);
        BlockPos first = positions.getFirst();
        for (int x = -radius; x <= radius; x++) for (int y = -radius; y <= radius; y++) for (int z = -radius; z <= radius; z++) {
            if (x * x + y * y + z * z > radius * radius) continue;
            Vec3 feet = new Vec3(first.getX() + x + .5, first.getY() + y, first.getZ() + z + .5);
            check(ProductionObservationRange.ready(feet, positions, limits), "Returned goal includes a stance outside a target's native range");
            for (BlockPos position : positions) check(feet.distanceToSqr(position.getCenter()) <= Math.pow(limits.applyAsDouble(position), 2),
                    "Returned-ready state exceeds the actual target permission range");
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
