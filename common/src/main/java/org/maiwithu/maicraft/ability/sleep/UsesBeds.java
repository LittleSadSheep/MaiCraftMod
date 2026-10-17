// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 上床的接缝：瞄准床头右键一下，按给定的确认条件等游戏结算。测试给固定值。
 */
public interface UsesBeds {

    /** 生成"对着床头点一下"的动作。 */
    Action use(BlockPos head, InteractionConfirmation confirmation);
}
