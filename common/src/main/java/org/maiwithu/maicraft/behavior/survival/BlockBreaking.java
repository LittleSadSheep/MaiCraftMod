// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;

import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 挖掉一格方块的动作：刨出任务只管"这一格该挖"，怎么瞄准、选工具、等确认都交给游戏接口层的挖掘。
 *
 * <p>本刻有没有真的挖开一格由动作回答（progressed），任务靠它判断有没有在前进。
 * 它是任务阶段里的动作，任务结束时由任务统一收尾，挖到一半的挖掘不会悬着。
 */
public interface BlockBreaking extends Action {

    /** 接下来挖哪一格；每刻挖之前设置，要挖的格子换了就换着挖。 */
    void aimAt(BlockPos cell);

    /** 只从这一面挖：连锁挖的形状按命中面算（开通道要对准迎面）；为空时哪面看得见挖哪面。 */
    default void fromFace(Direction face) {
    }

    /** 工具拿好、准星对准以后，第一下挥下去之前每刻问一次；答 false 这一刻先不挥（等连锁键、等预览）。 */
    default void beforeFirstSwing(Predicate<BlockHitResult> gate) {
    }

    /** 已经挥下去、游戏还没确认的那一下：连锁键要按到它结清才松。 */
    default boolean swingPending() {
        return false;
    }
}
