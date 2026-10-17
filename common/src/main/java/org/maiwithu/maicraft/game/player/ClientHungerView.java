// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import java.util.Objects;

import net.minecraft.client.player.LocalPlayer;

/**
 * 读真实角色的饥饿情况：饱食度来自角色的饥饿数据，创造模式没有饥饿机制。
 */
public final class ClientHungerView implements ReadsHunger {

    private final LocalPlayer player;

    public ClientHungerView(LocalPlayer player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    @Override
    public int foodLevel() {
        return player.getFoodData().getFoodLevel();
    }

    @Override
    public boolean hungerMechanicsOn() {
        return !player.getAbilities().instabuild;
    }
}
