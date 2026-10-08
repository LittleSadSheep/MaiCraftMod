// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.UseKeyHold;

/** 按住使用键投影的真实接法：续期与松开原样交给游戏层那份按住使用键投影。 */
public final class UseKeyHoldProjection implements UseKeyProjection {
    private final UseKeyHold hold;

    public UseKeyHoldProjection(UseKeyHold hold) {
        this.hold = hold;
    }

    @Override public boolean renew(Object owner, PlayerContext context, PendingInteraction pending,
                                   InteractionHand hand, ItemStack before) {
        return hold.renew(owner, context, pending, hand, before);
    }

    @Override public void release(Object owner) {
        hold.release(owner);
    }
}
