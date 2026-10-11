// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 对一格方块右键使用主手物品的动作接缝：点火、插末影之眼都走它。
 * 走到能点的站位由任务先用走到解决，动作自己完成瞄准、核对命中、提交与逐刻确认；
 * 生效与否按调用方给的确认条件核对。生产实现包住交互动作入口，测试用替身。
 */
public interface UsesBlocks {

    /** 右键一格方块，生效与否按给定的确认条件核对；接缝没接上时给空。 */
    Action useBlock(BlockPos target, InteractionConfirmation confirmation);
}
