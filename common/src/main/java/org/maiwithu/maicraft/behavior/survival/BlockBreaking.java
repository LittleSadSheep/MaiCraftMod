// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import net.minecraft.core.BlockPos;

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
}
