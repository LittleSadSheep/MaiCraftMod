// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge;

import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.network.ClientProtocolBridge;
import org.maiwithu.maicraft.network.OptionalServerNetwork;
import org.maiwithu.maicraft.network.OptionalServerPayload;
import org.maiwithu.maicraft.server.machine.ServerMachineOperations;

/** Optional authoritative server entry. No client-only class is referenced by this bootstrap. */
@Mod(Constants.MOD_ID)
public final class MaiCraftNeoForge {
    public MaiCraftNeoForge(IEventBus modBus) {
        ServerMachineOperations.register();
        modBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::logout);
        NeoForge.EVENT_BUS.addListener(this::changedDimension);
        NeoForge.EVENT_BUS.addListener(this::respawn);
        NeoForge.EVENT_BUS.addListener(this::stopped);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        // Never put the mod or feature version here: mismatches belong in JSON negotiation.
        event.registrar("1").optional().playBidirectional(OptionalServerPayload.TYPE, OptionalServerPayload.CODEC,
                (payload, context) -> {
                    if (context.flow() == PacketFlow.SERVERBOUND) {
                        if (context.player() instanceof ServerPlayer player)
                            OptionalServerNetwork.receive(player, payload,
                                    response -> context.reply(OptionalServerPayload.of(response)));
                    } else ClientProtocolBridge.receive(payload.json());
                });
    }

    private void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) OptionalServerNetwork.disconnected(player);
    }

    private void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) OptionalServerNetwork.worldChanged(player);
    }

    private void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) OptionalServerNetwork.worldChanged(player);
    }

    private void stopped(ServerStoppedEvent event) { OptionalServerNetwork.stopped(event.getServer()); }
    private void serverTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        org.maiwithu.maicraft.server.machine.watch.MachineWatchService.tick(event.getServer());
    }
}
