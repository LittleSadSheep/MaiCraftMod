// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.Comparator;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** A structural layer is a hard frontier; corners and outlines are only ordering preferences within it. */
final class BuildLayerFrontier {
    private BuildLayerFrontier() {}

    static int layer(BuildTaskRecord.Target target) {
        var state = target.desiredState();
        int base = BuildPlacementGeometry.primaryOf(target).getY();
        // Hanging fixtures are installed with their ceiling, not before that permanent support exists.
        boolean ceiling = state.hasProperty(BlockStateProperties.HANGING)
                && state.getValue(BlockStateProperties.HANGING)
                || state.hasProperty(BlockStateProperties.ATTACH_FACE)
                && state.getValue(BlockStateProperties.ATTACH_FACE) == AttachFace.CEILING;
        return ceiling ? Math.addExact(base, 1) : base;
    }

    static Comparator<BuildTaskRecord.Target> order(Map<Long, BuildTaskRecord.Target> targets) {
        return Comparator.comparingInt(BuildLayerFrontier::layer)
                .thenComparingInt(target -> boundaryPriority(target, targets))
                .thenComparing(BuildOrder.BUILD_ORDER);
    }

    static int boundaryPriority(BuildTaskRecord.Target target, Map<Long, BuildTaskRecord.Target> targets) {
        if (BuildCellRules.isAirTarget(target)) return 0;
        var state = target.desiredState();
        if (BuildOrder.needsSupport(state) || state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock) return 4;
        if (state.getBlock() instanceof RotatedPillarBlock
                && state.getValue(BlockStateProperties.AXIS) == Direction.Axis.Y) return 1;
        BlockPos pos = target.pos();
        int alongX = solid(targets, pos.west()) + solid(targets, pos.east());
        int alongZ = solid(targets, pos.north()) + solid(targets, pos.south());
        // Orthogonal exposed sides distinguish a corner/end from the two opposite sides of a wall.
        if (alongX < 2 && alongZ < 2) return 1;
        return alongX + alongZ < 4 ? 2 : 3;
    }

    private static int solid(Map<Long, BuildTaskRecord.Target> targets, BlockPos pos) {
        var target = targets.get(pos.asLong());
        return target != null && !BuildCellRules.isAirTarget(target) ? 1 : 0;
    }
}
