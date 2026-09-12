// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import java.util.List;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** Range planning only; the server still checks native access, target identity and player permissions. */
final class ProductionObservationRange {
    // Create 6.0.10 SmartBlockEntity.canPlayerUse measures player feet to the block center, inclusive.
    static final double CREATE_RADIUS = 8;
    private static final double POSITION_MARGIN = .5;
    private ProductionObservationRange() {}

    static double radius(Level level, BlockPos position) {
        if (!level.isLoaded(position)) return CREATE_RADIUS;
        return NativeApi.is(level.getBlockEntity(position), "com.simibubi.create.foundation.blockEntity.SmartBlockEntity")
                ? CREATE_RADIUS : ServerAccess.OBSERVATION_RADIUS;
    }

    static boolean ready(Vec3 feet, List<BlockPos> positions, ToDoubleFunction<BlockPos> radius) {
        if (positions.isEmpty() || feet.distanceToSqr(positions.getFirst().getCenter()) > 144) return false;
        return positions.stream().allMatch(position -> {
            double allowed = radius.applyAsDouble(position) - POSITION_MARGIN;
            return allowed > 0 && feet.distanceToSqr(position.getCenter()) <= allowed * allowed;
        });
    }

    static int goalRadius(List<BlockPos> positions, ToDoubleFunction<BlockPos> radius) {
        if (positions.isEmpty()) throw new IllegalArgumentException("production_observation_positions_missing");
        BlockPos first = positions.getFirst();
        double allowed = 12;
        for (BlockPos position : positions)
            allowed = Math.min(allowed, radius.applyAsDouble(position) - POSITION_MARGIN - Math.sqrt(first.distSqr(position)));
        if (allowed < 1) throw new IllegalArgumentException("production_observation_group_exceeds_native_range");
        // GoalNear uses block coordinates. Leave room for feet/center differences and verify the real body afterward.
        return Math.max(0, Math.min(10, (int) Math.floor(allowed) - 1));
    }
}
