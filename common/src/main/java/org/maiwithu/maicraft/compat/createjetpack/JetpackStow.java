// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.createjetpack;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.behavior.navigation.spi.StowBeforeWalking;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 走路前把误开的喷气背包关上：人玩的时候开着飞行、悬停就交回来，或者落地防护把开关拨回了掉下去之前开着的样子，
 * 开着走路一起跳就被推高、走下台阶悬在半空慢慢沉，寻路一步都对不上。
 * 只在站在地上或水里时关：半空里关掉就是往下掉。飞过去那一段自己开的开关不经过这里，它飞完自己拨回。
 */
final class JetpackStow implements StowBeforeWalking {

    private final CreateJetpackCompat compat;

    JetpackStow(CreateJetpackCompat compat) {
        this.compat = Objects.requireNonNull(compat, "compat");
    }

    @Override public Optional<Action> stow(PlayerContext context) {
        LocalPlayer player = context.localPlayer();
        if (player == null || !(player.onGround() || player.isInWater())) return Optional.empty();
        Optional<JetpackControls.Pack> pack = compat.pack(player);
        if (pack.isEmpty() || !pack.get().worn() || !pack.get().active() && !pack.get().hovering()) {
            return Optional.empty();
        }
        return Optional.of(new SwitchesTo(compat, false, false, "走路前把误开的喷气背包关上"));
    }
}
