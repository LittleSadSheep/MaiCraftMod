// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.maiwithu.maicraft.network.MaiCraftPayload;
import org.maiwithu.maicraft.network.ProtocolJson;
import org.maiwithu.maicraft.network.ProtocolReplies;
import org.maiwithu.maicraft.network.ServerFeature;
import org.maiwithu.maicraft.network.ServerProtocolDispatcher;

/**
 * 服务端链路：把收到的客户端信封交给按连接隔离的协议引擎，应答通过加载器发回。
 * 绝不使用客户端自己报的身份，只认当前连接上的玩家对象；普通玩家没有会话，照常游玩。
 */
public final class ServerLinkNetwork {
    private static final Logger LOG = LoggerFactory.getLogger(ServerLinkNetwork.class);
    private final ServerOperationRegistry operations;
    private final Map<ServerGamePacketListenerImpl, ServerProtocolDispatcher> connections =
            Collections.synchronizedMap(new IdentityHashMap<>());

    public ServerLinkNetwork(ServerOperationRegistry operations) {
        this.operations = operations;
    }

    public void receive(ServerPlayer player, MaiCraftPayload payload, Consumer<JsonObject> reply) {
        JsonObject envelope;
        try {
            if (payload.json().length() > ProtocolJson.MAX_REQUEST_CHARS)
                throw new IllegalArgumentException("Request too large");
            envelope = payload.envelope();
        } catch (RuntimeException malformed) {
            reply.accept(ProtocolReplies.reject(new JsonObject(), "invalid_envelope", "Malformed envelope",
                    Integer.toUnsignedLong(player.serverLevel().getServer().getTickCount())));
            return;
        }
        receive(player, envelope, reply);
    }

    public void receive(ServerPlayer player, JsonObject envelope, Consumer<JsonObject> reply) {
        MinecraftServer server = player.serverLevel().getServer();
        LOG.info("[serverlink] 服务端收到信封 kind={}，来自 {}", ServerProtocolDispatcher.probeKind(envelope),
                player.getGameProfile().getName());
        if (!server.isSameThread()) throw new IllegalStateException("Network dispatch requires the server game thread");
        if (player.hasDisconnected() || player.connection.player != player) return;
        var dispatcher = connections.computeIfAbsent(player.connection, ignored -> new ServerProtocolDispatcher());
        reply.accept(dispatcher.receive(envelope, new ServerProtocolDispatcher.Peer() {
            @Override public String dimension() { return player.serverLevel().dimension().location().toString(); }
            @Override public long tick() { return Integer.toUnsignedLong(server.getTickCount()); }
            @Override public boolean mayMutate() { return player.isAlive() && !player.isSpectator(); }
            @Override public List<ServerFeature> features() { return operations.features(player); }
            @Override public JsonObject execute(String operationId, JsonObject body) {
                return operations.execute(player, operationId, body);
            }
        }));
    }

    /** 该玩家当前是否有活跃的 MaiCraft 会话；交互确认只发给这些玩家。 */
    public boolean hasSession(ServerPlayer player) {
        return connections.containsKey(player.connection);
    }

    public void worldChanged(ServerPlayer player) {
        var dispatcher = connections.get(player.connection);
        if (dispatcher != null) dispatcher.invalidateWorld();
    }

    public void disconnected(ServerPlayer player) {
        connections.remove(player.connection);
    }

    public void stopped(MinecraftServer server) {
        List<ServerGamePacketListenerImpl> remaining;
        synchronized (connections) {
            remaining = connections.keySet().stream()
                    .filter(connection -> connection.player.serverLevel().getServer() == server).toList();
        }
        remaining.forEach(connections::remove);
    }
}
