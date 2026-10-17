// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 往哪个方向丢：丢出去的东西别落在必经的走廊上——一格宽的隧道、楼梯、门口落了一地，回头路过又被吸回包里。
 * 围着转一圈看十六个方向，估一估东西大概落在哪（往前几格，撞墙就停在墙前），落点周围能站人的空地越多越好，
 * 脚下是液体、深坑的方向不丢；路上、落点脚下有会收东西的方块（置物台、传送带、箱子、漏斗这类带方块实体的）也不丢——
 * 实机往置物台那边丢多余的金板，全落到台上被收了进去。都差不多时朝面前丢，不为丢东西转一大圈。只挑方向，不挖坑、不点火。
 */
final class DropSpot {

    /** 东西大概飞多远（格）。 */
    private static final int THROW_REACH = 5;
    /** 看落点周围几格内的空地：两格半径，五乘五。 */
    private static final int AROUND = 2;
    /** 转一圈看几个方向。 */
    private static final int DIRECTIONS = 16;
    /** 比面前那个方向开阔这么多（空地格数）以上才转过去丢。 */
    private static final int WORTH_TURNING = 3;

    private DropSpot() {}

    /**
     * 挑一个丢的方向（水平单位向量）。
     *
     * @param blocks  读方块：没加载为 null
     * @param feet    丢的时候站的格子
     * @param forward 此刻面朝的水平方向
     */
    static Vec3 direction(Function<BlockPos, BlockState> blocks, BlockPos feet, Vec3 forward) {
        int forwardScore = openness(blocks, feet, forward);
        Vec3 best = forward;
        int bestScore = forwardScore;
        for (int i = 0; i < DIRECTIONS; i++) {
            double angle = Math.PI * 2 * i / DIRECTIONS;
            Vec3 candidate = new Vec3(-Math.sin(angle), 0, Math.cos(angle));
            int score = openness(blocks, feet, candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return bestScore >= forwardScore + WORTH_TURNING ? best : forward;
    }

    // 朝这个方向丢：东西落在哪一格（往前走到撞墙为止），落点周围五乘五里能站人的空地有几格；落点脚下是液体或深坑为 -1。
    static int openness(Function<BlockPos, BlockState> blocks, BlockPos feet, Vec3 direction) {
        BlockPos landing = feet;
        for (int step = 1; step <= THROW_REACH; step++) {
            BlockPos next = BlockPos.containing(feet.getX() + 0.5 + direction.x * step, feet.getY(),
                    feet.getZ() + 0.5 + direction.z * step);
            // 挡住去路的、飞过的地面上是会收东西的方块：东西落上去会被收走，这个方向不丢。
            if (catches(blocks, next) || catches(blocks, next.below())) return -1;
            if (!passable(blocks, next) || !passable(blocks, next.above())) break;
            landing = next;
        }
        if (landing.equals(feet) || !standable(blocks, landing)) return -1;
        int open = 0;
        for (int dx = -AROUND; dx <= AROUND; dx++) {
            for (int dz = -AROUND; dz <= AROUND; dz++) {
                if (standable(blocks, landing.offset(dx, 0, dz))) open++;
            }
        }
        return open;
    }

    // 能站人：脚下这格和头那格都穿得过、不是液体，下面一格是实心的。
    private static boolean standable(Function<BlockPos, BlockState> blocks, BlockPos at) {
        BlockState below = blocks.apply(at.below());
        return passable(blocks, at) && passable(blocks, at.above())
                && below != null && below.getFluidState().isEmpty() && below.isSolid();
    }

    // 会收东西的方块：带方块实体的（置物台、传送带、箱子、漏斗、工作盆）。
    private static boolean catches(Function<BlockPos, BlockState> blocks, BlockPos at) {
        BlockState state = blocks.apply(at);
        return state != null && state.hasBlockEntity();
    }

    private static boolean passable(Function<BlockPos, BlockState> blocks, BlockPos at) {
        BlockState state = blocks.apply(at);
        return state != null && state.getFluidState().isEmpty() && !state.isSolid();
    }
}
