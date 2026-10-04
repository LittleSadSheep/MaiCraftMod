// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import org.maiwithu.maicraft.client.runtime.GameplayReminders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原版完成个人统计同步后传递休息计数；旧连接的迟到包不能给新身体补上旧睡眠史。 */
@Mixin(ClientPacketListener.class)
public abstract class ClientRestStatisticsMixin {
    @Inject(method = "handleAwardStats", at = @At("TAIL"))
    private void maicraft$observeRest(ClientboundAwardStatsPacket packet, CallbackInfo callback) {
        var player = Minecraft.getInstance().player;
        if (player != null && player.connection == (Object) this)
            GameplayReminders.receiveRestStatistics(player, packet);
    }
}
