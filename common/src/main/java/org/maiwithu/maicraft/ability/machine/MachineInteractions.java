// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 机器能力要的原生交互入口：右键一格方块（拨机器自带的开关）。
 * 动作自己做瞄准、提交与逐刻确认；站位由调用方先靠近。测试用替身按脚本回答。
 */
interface MachineInteractions {

    /** 右键一格方块，生效与否按给定的确认条件核对。 */
    Action useBlock(BlockPos target, InteractionConfirmation confirmation);
}
