// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import org.maiwithu.maicraft.game.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版处理完本玩家相关的两个网络包后，只读观察耐久变化与打空；不改任何同步结果。
 * 装备同步包对本人恒不到达，真实必达通道是打空实体事件；两条注入共用同一份观察账目。
 */
@Mixin(ClientPacketListener.class)
public abstract class EquipmentConfirmationMixin {

    @Inject(method = "handleSetEquipment", at = @At("TAIL"))
    private void maicraft$observeEquipment(ClientboundSetEquipmentPacket packet, CallbackInfo callback) {
        var confirmations = ClientHooks.equipmentConfirmations();
        if (confirmations != null) confirmations.equipped(Minecraft.getInstance().player, packet);
    }

    @Inject(method = "handleEntityEvent", at = @At("TAIL"))
    private void maicraft$observeBreakEvent(ClientboundEntityEventPacket packet, CallbackInfo callback) {
        var confirmations = ClientHooks.equipmentConfirmations();
        if (confirmations != null) confirmations.entityEvent(Minecraft.getInstance().player, packet);
    }
}
