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

/** Acknowledges a server BE update after vanilla and the optional mod have processed its data. */
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

    // AEBaseMenu overrides initializeContents without calling vanilla's method, so the ordinary
    // container mixin alone cannot observe its real initial inventory packet.
    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void maicraft$receivedMachineContents(ClientboundContainerSetContentPacket packet, CallbackInfo callback) {
        var player = Minecraft.getInstance().player;
        if (player != null && player.clientLevel == level && player.containerMenu.containerId == packet.getContainerId()) {
            StockEvidence.containerSynchronized(player.containerMenu);
        }
    }
}
