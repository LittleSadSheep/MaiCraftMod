// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import org.maiwithu.maicraft.core.integration.machine.assembly.ServerBlockEntityReceipts;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原版和可选模组处理完服务器方块实体数据后，确认对应更新已完成。 */
@Mixin(ClientPacketListener.class)
public abstract class MachineBlockEntityReceiptMixin {
    @Shadow private ClientLevel level;

    @Inject(method = "handleBlockEntityData", at = @At("RETURN"))
    private void maicraft$receivedMachineState(ClientboundBlockEntityDataPacket packet, CallbackInfo callback) {
        if (level == null || !level.isLoaded(packet.getPos()) || packet.getTag() == null || packet.getTag().isEmpty()) return;
        var entity = level.getBlockEntity(packet.getPos());
        if (entity != null && entity.getType() == packet.getType()) {
            ServerBlockEntityReceipts.received(level, packet.getPos());
        }
    }

    // AEBaseMenu 会覆写 initializeContents 而不调用原版方法，因此普通容器 mixin 无法单独观察到其真实初始库存数据包。
    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void maicraft$receivedMachineContents(ClientboundContainerSetContentPacket packet, CallbackInfo callback) {
        var player = Minecraft.getInstance().player;
        if (player != null && player.clientLevel == level && player.containerMenu.containerId == packet.getContainerId()) {
            StockEvidence.containerSynchronized(player.containerMenu);
        }
    }
}
