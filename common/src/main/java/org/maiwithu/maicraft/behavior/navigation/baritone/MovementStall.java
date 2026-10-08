// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import baritone.pathing.movement.Movement;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * 路线路线执行段放弃某一步时留下的卡住的位置：哪一步、因何放弃、角色面前是哪一格，以及这一步途经、可能需要开关的格子。
 * 导航按面前格累计失败次数：第一次先切换途经的门再试，第二次仍卡住才把面前格列为本次导航的障碍并重算绕行。
 */
public record MovementStall(Cause cause, String movement, BlockPos src, BlockPos dest, BlockPos front,
                            List<BlockPos> passageCells, int ticks) {
    /** 单步超时、被撞回后最远一步始终没越过、这一步自己报告走不通。 */
    public enum Cause { TIMEOUT, NO_ADVANCE, UNREACHABLE }

    public MovementStall {
        passageCells = List.copyOf(passageCells);
    }

    public static MovementStall capture(Cause cause, Movement movement, Vec3 body, BlockPos feet, int ticks) {
        // Baritone 坐标重写了哈希，进集合和写进诊断前统一换成原版坐标。
        BlockPos src = plain(movement.getSrc()), dest = plain(movement.getDest());
        // 普通一步的面前格就是这一步的终点；合并后的长直线终点可能在几十格外，改取角色沿直线前进方向的下一格。
        BlockPos front = movement instanceof MovementGroundStraight straight
                ? straightFront(body, straight.target(), src.getY(), feet) : dest;
        // 角色脚下这一格必须留作出口，永远不列为障碍。
        if (front != null && front.equals(feet)) front = null;
        // 门可能在起点（从门洞里走出）、终点或这一步需要清开的格子里；面前格也一起检查。
        var cells = new LinkedHashSet<BlockPos>();
        cells.add(src); cells.add(src.above()); cells.add(dest); cells.add(dest.above());
        // 记录卡住的位置只是收集现场，缺少清障格时照样记下，不能反过来让路线执行段这一刻出错。
        BlockPos[] toBreak = movement.toBreakAll();
        if (toBreak != null) for (BlockPos cell : toBreak) if (cell != null) cells.add(plain(cell));
        if (front != null) { cells.add(front); cells.add(front.above()); }
        return new MovementStall(cause, movement.getClass().getSimpleName(), src, dest, front, List.copyOf(cells), ticks);
    }

    private static BlockPos plain(BlockPos pos) {
        return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    // 沿角色到直线终点的水平方向每 0.25 格取样，最多看两格，返回第一个不是脚下柱列的格子；终点就在脚下时没有面前格。
    static BlockPos straightFront(Vec3 body, Vec3 target, int y, BlockPos feet) {
        double dx = target.x - body.x, dz = target.z - body.z, length = Math.sqrt(dx * dx + dz * dz);
        if (!(length > 1e-6)) return null;
        for (double step = 0.25; step <= 2.0; step += 0.25) {
            double t = Math.min(step, length) / length;
            BlockPos cell = BlockPos.containing(body.x + dx * t, y, body.z + dz * t);
            if (cell.getX() != feet.getX() || cell.getZ() != feet.getZ()) return cell;
            if (step >= length) break;
        }
        return null;
    }

    /** 门的上下两半共用下半格作为登记键，栅栏门就是自身格子，这样从任一半点击都对应同一条切换登记。 */
    public static BlockPos passageKey(BlockPos pos, BlockState state) {
        boolean upper = state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
        return upper ? pos.below().immutable() : pos.immutable();
    }
}
