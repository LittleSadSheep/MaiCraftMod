// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.maiwithu.maicraft.network.OptionalServerNetwork;
import org.maiwithu.maicraft.network.OptionalServerPayload;
import org.maiwithu.maicraft.server.machine.ServerMachineOperations;

/** Common bootstrap is safe on dedicated servers and does not initialize client automation. */
public final class MaiCraftFabric implements ModInitializer {
    @Override public void onInitialize() {
        ServerMachineOperations.register();
        PayloadTypeRegistry.playC2S().register(OptionalServerPayload.TYPE, OptionalServerPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OptionalServerPayload.TYPE, OptionalServerPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(OptionalServerPayload.TYPE, (payload, context) -> {
            if (ServerPlayNetworking.canSend(context.player(), OptionalServerPayload.TYPE))
                OptionalServerNetwork.receive(context.player(), payload,
                        response -> ServerPlayNetworking.send(context.player(), OptionalServerPayload.of(response)));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                OptionalServerNetwork.disconnected(handler.player));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
                OptionalServerNetwork.worldChanged(player));
        ServerPlayerEvents.AFTER_RESPAWN.register((previous, player, alive) ->
                OptionalServerNetwork.worldChanged(player));
        ServerLifecycleEvents.SERVER_STOPPED.register(OptionalServerNetwork::stopped);
    }
}
