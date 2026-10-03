// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import org.maiwithu.maicraft.client.actor.EquipmentReceipts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原版完成装备同步包处理后，只观察本地玩家的耐久变化与打空；不修改任何同步结果。 */
@Mixin(ClientPacketListener.class)
public abstract class EquipmentReceiptMixin {
    @Inject(method = "handleSetEquipment", at = @At("TAIL"))
    private void maicraft$observeEquipment(ClientboundSetEquipmentPacket packet, CallbackInfo callback) {
        EquipmentReceipts.equipped(Minecraft.getInstance().player, packet);
    }
}
