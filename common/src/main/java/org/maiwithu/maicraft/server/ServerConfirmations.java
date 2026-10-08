// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 交互确认通道：服务端把"这个玩家刚才破坏 / 放置 / 交互了哪一格"主动推给客户端，
 * 作为客户端"提交 → 逐刻确认"之外的权威确认。只发给有活跃 MaiCraft 会话的玩家，
 * 普通玩家一个包都不会多收；服务端只报告事实，不替角色动手，也不提供透视。
 */
public final class ServerConfirmations {

    /** 加载器提供的发送通道：把信封发给指定玩家。 */
    public interface Push {
        void send(ServerPlayer player, JsonObject envelope);
    }

    /** 谁有活跃的 MaiCraft 会话，由服务端链路判断。 */
    public interface ActiveSessions {
        boolean active(ServerPlayer player);
    }

    private final Push push;
    private final ActiveSessions sessions;

    public ServerConfirmations(Push push, ActiveSessions sessions) {
        this.push = push;
        this.sessions = sessions;
    }

    /** 玩家放好了方块（放置钩子只在成功后调用）。 */
    public void placed(ServerPlayer player, BlockPos position, BlockState state) {
        send(player, "place", position, state);
    }

    /** 玩家破坏了方块。 */
    public void broke(ServerPlayer player, BlockPos position, BlockState state) {
        send(player, "break", position, state);
    }

    /** 玩家对一格方块完成了使用（右键）交互。 */
    public void used(ServerPlayer player, BlockPos position, BlockState state) {
        send(player, "interact", position, state);
    }

    private void send(ServerPlayer player, String action, BlockPos position, BlockState state) {
        if (!sessions.active(player)) return;
        MinecraftServer server = player.serverLevel().getServer();
        push.send(player, confirmationEnvelope(action,
                player.serverLevel().dimension().location().toString(), position,
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                Integer.toUnsignedLong(server.getTickCount())));
    }

    /** 确认信封的形状；纯函数，便于离线测试与客户端解码保持一致。 */
    static JsonObject confirmationEnvelope(String action, String dimension, BlockPos position,
                                           String blockId, long serverTick) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("kind", "confirmation");
        envelope.addProperty("bootstrap", 1);
        envelope.addProperty("action", action);
        envelope.addProperty("dimension", dimension);
        JsonObject positionJson = new JsonObject();
        positionJson.addProperty("x", position.getX());
        positionJson.addProperty("y", position.getY());
        positionJson.addProperty("z", position.getZ());
        envelope.add("position", positionJson);
        envelope.addProperty("block", blockId);
        envelope.addProperty("serverTick", serverTick);
        return envelope;
    }
}
