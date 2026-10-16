// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;

import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 用一格方块的生产读端：包住交互动作入口的右键一格，和朝一格扔主手里的东西。
 * 启动时建一份交给跨维度任务；替身测试不经过它。
 */
public final class LiveBlockUses implements UsesBlocks {

    private final Interactions interactions;

    public LiveBlockUses(Interactions interactions) {
        this.interactions = interactions;
    }

    @Override
    public Action useBlock(BlockPos target, InteractionConfirmation confirmation) {
        return interactions.useBlock(target, confirmation);
    }

    // 扔珍珠进折跃门：手里的东西沿视线出手，不是右键准星指着的方块；用主手扔。
    @Override
    public Action useHeldItemToward(BlockPos target, InteractionConfirmation confirmation) {
        return interactions.useHeldItemToward(target, InteractionHand.MAIN_HAND, confirmation);
    }
}
