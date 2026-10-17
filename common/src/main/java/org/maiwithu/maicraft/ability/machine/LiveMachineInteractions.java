// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Objects;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 机器交互入口的生产实现：转交给玩家行为层的交互模型，动作自己做瞄准、提交与逐刻确认。
 */
final class LiveMachineInteractions implements MachineInteractions {

    private final Interactions interactions;

    LiveMachineInteractions(Interactions interactions) {
        this.interactions = Objects.requireNonNull(interactions, "interactions");
    }

    @Override public Action useBlock(BlockPos target, InteractionConfirmation confirmation) {
        return interactions.useBlock(target, confirmation);
    }
}
