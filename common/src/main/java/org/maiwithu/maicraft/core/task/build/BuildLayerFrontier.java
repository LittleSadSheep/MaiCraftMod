// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.Comparator;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 决定先盖哪一层，再决定这一层先放边角还是中间：本层未完成时先不去上层，层内的边角优先只是排序偏好。
 */
final class BuildLayerFrontier {
    private BuildLayerFrontier() {}

    static int layer(BuildTaskRecord.Target target) {
        var state = target.desiredState();
        int base = BuildPlacementGeometry.primaryOf(target).getY();
        // 吊灯等挂在顶面的东西排到天花板所在层，等永久支撑形成后再装。
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
        // 横向和纵向都没有两侧邻居时，视为边角或端点；只缺一侧的墙边随后，四周都有邻居的填充格更后。
        if (alongX < 2 && alongZ < 2) return 1;
        return alongX + alongZ < 4 ? 2 : 3;
    }

    // 这里数的是计划里非空气的邻居，不是在读取现场，也不是按真实碰撞盒认定完整实体。
    private static int solid(Map<Long, BuildTaskRecord.Target> targets, BlockPos pos) {
        var target = targets.get(pos.asLong());
        return target != null && !BuildCellRules.isAirTarget(target) ? 1 : 0;
    }
}
