// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.maiwithu.maicraft.client.actor.ItemEntityReceipts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 读取原生实体生成和拾取包；全部观察留在客户端线程，原版仍负责实体与背包的实际变化。 */
@Mixin(ClientPacketListener.class)
public abstract class ItemEntityReceiptMixin {
    @Shadow private ClientLevel level;

    @Inject(method = "handleAddEntity", at = @At("TAIL"))
    private void maicraft$observedItemSpawn(ClientboundAddEntityPacket packet, CallbackInfo callback) {
        ItemEntityReceipts.entityAdded(Minecraft.getInstance().player, level, packet.getId());
    }

    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
    private void maicraft$observedItemPickup(ClientboundTakeItemEntityPacket packet, CallbackInfo callback) {
        // HEAD 在网络线程也会先经过一次；只在线程重投后的客户端处理前读取，避免越线程查询即将删除的实体。
        var minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) ItemEntityReceipts.taking(minecraft.player, level, packet);
    }
}
