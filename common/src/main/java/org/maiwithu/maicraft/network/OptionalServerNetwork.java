// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.network;

import com.google.gson.JsonObject;
import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.maiwithu.maicraft.server.machine.ServerProductionEvents;

/** Loader-independent dispatch tied to the authenticated packet listener, never a client-supplied UUID. */
public final class OptionalServerNetwork {
    private static final Map<ServerGamePacketListenerImpl, ServerProtocolDispatcher> CONNECTIONS =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private OptionalServerNetwork() {}

    public static void receive(ServerPlayer player, OptionalServerPayload payload, Consumer<JsonObject> reply) {
        JsonObject envelope;
        try {
            if (payload.json().length() > ProtocolJson.MAX_REQUEST_CHARS)
                throw new IllegalArgumentException("Request too large");
            envelope = payload.envelope();
        }
        catch (RuntimeException malformed) {
            reply.accept(ProtocolReplies.reject(new JsonObject(), "invalid_envelope", "Malformed envelope",
                    Integer.toUnsignedLong(player.serverLevel().getServer().getTickCount())));
            return;
        }
        receive(player, envelope, reply);
    }

    public static void receive(ServerPlayer player, JsonObject envelope, Consumer<JsonObject> reply) {
        MinecraftServer server = player.serverLevel().getServer();
        if (!server.isSameThread()) throw new IllegalStateException("Network dispatch requires the server game thread");
        if (player.hasDisconnected() || player.connection.player != player) return;
        var dispatcher = CONNECTIONS.computeIfAbsent(player.connection, ignored -> new ServerProtocolDispatcher());
        reply.accept(dispatcher.receive(envelope, new ServerProtocolDispatcher.Peer() {
            @Override public String dimension() { return player.serverLevel().dimension().location().toString(); }
            @Override public long tick() { return Integer.toUnsignedLong(server.getTickCount()); }
            @Override public boolean mayMutate() { return player.isAlive() && !player.isSpectator(); }
            @Override public List<ServerFeature> features() { return ServerOperationRegistry.features(player); }
            @Override public JsonObject execute(String operationId, JsonObject body) {
                return ServerOperationRegistry.execute(player, operationId, body);
            }
        }));
    }

    public static void worldChanged(ServerPlayer player) {
        var dispatcher = CONNECTIONS.get(player.connection);
        if (dispatcher != null) dispatcher.invalidateWorld();
        ServerProductionEvents.disconnected(player);
    }

    public static void disconnected(ServerPlayer player) {
        try { ServerProductionEvents.disconnected(player); }
        finally { CONNECTIONS.remove(player.connection); }
    }
    public static void stopped(MinecraftServer server) {
        List<ServerPlayer> remaining;
        synchronized (CONNECTIONS) {
            remaining = CONNECTIONS.keySet().stream().filter(connection -> connection.player.serverLevel().getServer() == server)
                    .map(connection -> connection.player).toList();
        }
        remaining.forEach(OptionalServerNetwork::disconnected);
    }
}
