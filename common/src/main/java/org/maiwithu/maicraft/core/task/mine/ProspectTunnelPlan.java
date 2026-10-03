// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.mine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/** 通道以脚位轨迹和身体扫过的空间定义；每次只补剩余障碍，挖掘次数由真实地形决定。 */
final class ProspectTunnelPlan {
    private final BlockPos anchor;
    private final Direction heading;
    private final int bodyBlocks, length;
    private final boolean descending;

    ProspectTunnelPlan(BlockPos anchor, Direction heading, int targetY, double bodyHeight, int maxSteps) {
        if (heading == null || heading.getAxis().isVertical() || maxSteps < 1 || bodyHeight <= 0)
            throw new IllegalArgumentException("invalid prospect tunnel geometry");
        this.anchor = anchor.immutable(); this.heading = heading;
        bodyBlocks = Math.max(1, (int) Math.ceil(bodyHeight));
        descending = anchor.getY() > targetY;
        length = descending ? Math.min(maxSteps, anchor.getY() - targetY) : maxSteps;
    }
    BlockPos foot(int step) { return anchor.relative(heading, step).below(descending ? step : 0); }
    boolean descending() { return descending; }
    Direction heading() { return heading; }
    int length() { return length; }

    /** 向下一格时身体要扫过上一步的头部高度，正常角色因此需要三格；水平移动只占两格。 */
    List<BlockPos> clearance(int step) {
        BlockPos feet = foot(step);
        int top = Math.max(feet.getY(), foot(step - 1).getY()) + bodyBlocks - 1;
        var cells = new ArrayList<BlockPos>();
        for (int y = top; y >= feet.getY(); y--) cells.add(new BlockPos(feet.getX(), y, feet.getZ()));
        return List.copyOf(cells);
    }
    boolean contains(BlockPos at) {
        BlockPos delta = at.subtract(anchor);
        int step = delta.getX() * heading.getStepX() + delta.getZ() * heading.getStepZ();
        return step > 0 && step <= length && clearance(step).contains(at);
    }

    /** 只查看当前够得到的通道前沿；矿石让原生线段中断后，下一次仍从未清掉的格子继续。 */
    List<BlockPos> obstacles(Function<BlockPos, BlockState> read, int reachableSteps) {
        var result = new ArrayList<BlockPos>();
        for (int step = 1; step <= Math.min(length, reachableSteps); step++) {
            for (BlockPos at : clearance(step)) {
                BlockState state = read.apply(at);
                if (state != null && !state.isAir()) result.add(at);
            }
        }
        return List.copyOf(result);
    }

    /** 只有每一步的身体空间与脚下支撑都满足真实行走条件，才把最远连续脚位交给导航。 */
    BlockPos walkableEnd(Predicate<BlockPos> emptyBody, Predicate<BlockPos> safeFloor) {
        BlockPos end = anchor;
        for (int step = 1; step <= length; step++) {
            if (!safeFloor.test(foot(step).below()) || !clearance(step).stream().allMatch(emptyBody)) break;
            end = foot(step);
        }
        return end.equals(anchor) ? null : end;
    }
}
