// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import org.maiwithu.maicraft.game.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版处理完死亡包（打开死亡界面）后，把本地玩家的那句死因（"MaiBot 被苦力怕炸死了"）记下来：
 * 真人看一眼死亡界面就知道怎么死的，角色重生后也该知道。不改任何同步结果。
 */
@Mixin(ClientPacketListener.class)
public abstract class DeathMessageMixin {

    @Shadow private ClientLevel level;

    @Inject(method = "handlePlayerCombatKill", at = @At("TAIL"))
    private void maicraft$noteDeathMessage(ClientboundPlayerCombatKillPacket packet, CallbackInfo callback) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || level == null || packet.playerId() != player.getId()) return;
        ClientHooks.noteDeathMessage(packet.message().getString(), level.getGameTime());
    }
}
