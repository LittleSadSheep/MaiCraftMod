// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * 读真实角色身上的状态效果：每个效果给注册 ID；同一个效果不因等级或剩余时长重复列出。
 */
public final class ClientEffectReads implements ReadsEffects {

    private final LocalPlayer player;

    public ClientEffectReads(LocalPlayer player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    @Override
    public List<String> active() {
        List<String> effects = new ArrayList<>();
        player.getActiveEffects().forEach(instance ->
                effects.add(BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect().value()).toString()));
        return List.copyOf(effects);
    }
}
