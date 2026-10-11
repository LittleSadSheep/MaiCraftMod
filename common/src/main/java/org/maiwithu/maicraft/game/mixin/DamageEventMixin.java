// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import org.maiwithu.maicraft.game.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原版处理完伤害包后，只把本地玩家挨的这一下（是谁、什么伤）交给伤害观察；不改任何同步结果。 */
@Mixin(ClientPacketListener.class)
public abstract class DamageEventMixin {

    @Shadow private ClientLevel level;

    @Inject(method = "handleDamageEvent", at = @At("TAIL"))
    private void maicraft$observeDamage(ClientboundDamageEventPacket packet, CallbackInfo callback) {
        var observer = ClientHooks.damageObserver();
        LocalPlayer player = Minecraft.getInstance().player;
        if (observer == null || player == null || level == null || packet.entityId() != player.getId()) return;
        observer.hurt(player, packet.getSource(level), level.getGameTime());
    }
}
