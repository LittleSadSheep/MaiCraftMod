// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 挖掉一格方块的动作缝：刨出任务只管"这一格该挖"，怎么瞄准、选工具、等确认都交给游戏接口层的挖掘。
 *
 * <p>本刻有没有真的挖开一格由实现回答（progressed），任务靠它判断有没有在前进。
 */
public interface BlockBreaking {

    /** 本刻朝这一格挖一下；一格要挖很多刻，做完一格才算一次真实进展。 */
    ActionStatus dig(TickContext context, BlockPos cell);

    /** 不再挖了：结清这次挖掘占着的交互与按键。任务决定结束的当刻调用。 */
    void stop(TickContext context);

    /** 给调试面板和日志的一句话。 */
    String describe();
}
