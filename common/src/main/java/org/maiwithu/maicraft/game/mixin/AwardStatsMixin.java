// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import org.maiwithu.maicraft.game.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原版处理完个人统计包后，把"离上次休息多少刻"交给休息统计；不改任何同步结果。 */
@Mixin(ClientPacketListener.class)
public abstract class AwardStatsMixin {

    @Inject(method = "handleAwardStats", at = @At("TAIL"))
    private void maicraft$observeRest(ClientboundAwardStatsPacket packet, CallbackInfo callback) {
        var statistics = ClientHooks.restStatistics();
        var minecraft = Minecraft.getInstance();
        if (statistics != null && minecraft.isSameThread()) statistics.received(minecraft.player, packet);
    }
}
