// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 挖方块的执行接缝：走到一格方块跟前，持续挖到它消失，掉落物等角色自己捡。
 * 采集熟作物与采掘矿石都用它；现场动作（靠近、按住左键、确认方块消失）由游戏接口层
 * 用靠近与交互的公开接口实现，测试用替身。这个入口还没接上时返回 empty，来源如实报告不支持。
 */
public interface DigsBlocks {

    /** 为挖掉一格生成动作；做完时这一格已经是空气。 */
    Optional<Action> dig(BlockPos target);

    /**
     * 连锁挖一格：对准触发格挖，装了连锁挖的模组时按住连锁键，按形状把一批一起挖掉——
     * 模组给出的那一批每一格 mayBreak 都同意才连锁，不然只挖触发格。face 不为空时先从这一面挖
     * （开通道的形状按命中面算）。做完时触发格已经挖掉；哪些副格也挖掉了由调用方逐格复查。没有连锁挖时和 dig 一样。
     */
    default Optional<Action> digChained(BlockPos trigger, Direction face, BreakAccelerator.ChainShape shape,
            Predicate<BlockPos> mayBreak) {
        return dig(trigger);
    }

    /** 装了连锁挖的模组：挑格、挑面时值得为连锁凑整片。没装时为假。 */
    default boolean chains() {
        return false;
    }

    /** 这一格此刻已经没了（空气或只剩流体）：连锁挖顺带挖掉的格不用再走过去挖。读不到时答还在。 */
    default boolean gone(TickContext context, BlockPos cell) {
        return false;
    }
}
