// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.maiwithu.maicraft.game.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 读原生的实体生成包与拾取包，交给掉落物确认；全部观察留在客户端线程，原版照旧改实体与背包。 */
@Mixin(ClientPacketListener.class)
public abstract class ItemEntityConfirmationMixin {

    @Shadow private ClientLevel level;

    @Inject(method = "handleAddEntity", at = @At("TAIL"))
    private void maicraft$observedItemSpawn(ClientboundAddEntityPacket packet, CallbackInfo callback) {
        var confirmations = ClientHooks.dropConfirmations();
        if (confirmations != null) confirmations.entityAdded(Minecraft.getInstance().player, level, packet.getId());
    }

    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
    private void maicraft$observedItemPickup(ClientboundTakeItemEntityPacket packet, CallbackInfo callback) {
        // HEAD 在网络线程也会先经过一次；只在重投到客户端线程后、原版处理前读，免得跨线程查即将删除的实体。
        var minecraft = Minecraft.getInstance();
        var confirmations = ClientHooks.dropConfirmations();
        if (confirmations != null && minecraft.isSameThread()) confirmations.taking(minecraft.player, level, packet);
    }
}
