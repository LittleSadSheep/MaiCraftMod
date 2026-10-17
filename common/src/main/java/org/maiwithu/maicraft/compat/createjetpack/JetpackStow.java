// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.createjetpack;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.behavior.navigation.spi.StowBeforeWalking;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 走路前把误开的喷气背包飞行关上：人玩的时候开着飞行就交回来，或者落地防护把开关拨回了掉下去之前开着的样子，
 * 开着走路一起跳就被推高、走下台阶悬在半空慢慢沉，寻路一步都对不上。只关飞行，悬停留着人原来的设置。
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
        // 只看飞行开关：悬停只是"飞起来时悬着"的偏好，飞行关着时它不起作用，不替人改。
        // 原先两个开关都关，人每次按 F8 收回都发现悬停被关掉了。
        if (pack.isEmpty() || !pack.get().worn() || !pack.get().active()) return Optional.empty();
        return Optional.of(new SwitchesTo(compat, false, pack.get().hovering(), "走路前把误开的喷气背包飞行关上"));
    }
}
