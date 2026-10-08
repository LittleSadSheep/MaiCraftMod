// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.maiwithu.maicraft.bootstrap.Bootstrap;
import org.maiwithu.maicraft.bootstrap.ServerLifecycle;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.network.MaiCraftPayload;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;

/**
 * Fabric 的通用入口，独立服务器和客户端都会加载。
 * 只把服务端事件转给公共代码，不写业务，也不引用任何仅客户端的类。
 */
public final class MaiCraftFabric implements ModInitializer {
    @Override public void onInitialize() {
        // 服务端把确认信封直接发给目标玩家的客户端；通道保持 optional，没装 Mod 的普通玩家照样进服。
        ServerLifecycle server = Bootstrap.startCommon(new FabricLoaderEnvironment(),
                (player, envelope) -> ServerPlayNetworking.send(player, MaiCraftPayload.of(envelope)));
        PayloadTypeRegistry.playC2S().register(MaiCraftPayload.TYPE, MaiCraftPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MaiCraftPayload.TYPE, MaiCraftPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MaiCraftPayload.TYPE, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player
                    && ServerPlayNetworking.canSend(player, MaiCraftPayload.TYPE))
                server.clientEnvelope(player, payload,
                        response -> ServerPlayNetworking.send(player, MaiCraftPayload.of(response)));
        });
        ServerTickEvents.END_SERVER_TICK.register(server::tickEnd);
        ServerLifecycleEvents.SERVER_STOPPED.register(server::stopped);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, ignored) -> {
            if (handler.player != null) server.clientDisconnected(handler.player);
        });
        // 破坏方块的事件在方块真正消失后触发，结果可靠；放置由服务端 Mixin 钩子记录。
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, ignored) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer serverPlayer)
                server.playerBrokeBlock(serverPlayer, pos, state);
        });
    }
}
