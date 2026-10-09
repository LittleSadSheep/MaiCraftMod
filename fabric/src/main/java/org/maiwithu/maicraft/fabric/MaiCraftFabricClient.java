// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.fabric;

import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.maiwithu.maicraft.bootstrap.Bootstrap;
import org.maiwithu.maicraft.bootstrap.ClientLifecycle;
import org.maiwithu.maicraft.game.serverlink.LinkTransport;
import org.maiwithu.maicraft.network.MaiCraftPayload;

/**
 * Fabric 的客户端入口：只把客户端事件转给公共代码，不写业务。
 * 新增客户端事件时，先在 {@link ClientLifecycle} 加方法，再在这里和 NeoForge 入口各接一行。
 */
public final class MaiCraftFabricClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        // 与服务端 MaiCraft 的发送通道；服务端发来的信封由下面的全局接收器转给客户端部分。
        ClientLifecycle client = Bootstrap.startClient(new FabricLoaderEnvironment(), new LinkTransport() {
            @Override public boolean available() { return ClientPlayNetworking.canSend(MaiCraftPayload.TYPE); }
            @Override public void send(JsonObject envelope) { ClientPlayNetworking.send(MaiCraftPayload.of(envelope)); }
        });
        ClientLifecycleEvents.CLIENT_STARTED.register(minecraft -> client.started());
        ClientTickEvents.END_CLIENT_TICK.register(client::tickEnd);
        ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> client.stopping());
        ClientPlayNetworking.registerGlobalReceiver(MaiCraftPayload.TYPE, (payload, context) ->
                client.serverLinkReceived(Minecraft.getInstance(), payload.json()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) ->
                client.serverLinkDisconnected(minecraft));
        // 聊天栏收到的消息：玩家说的话（有签名的带原话与 UUID，没签名的只有整行）与系统消息，有什么给什么。
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, chatType, receivedAt) ->
                client.playerChatReceived(Minecraft.getInstance(), message,
                        signedMessage == null ? null : signedMessage.decoratedContent(),
                        sender != null ? sender.getId() : signedMessage == null ? null : signedMessage.sender(),
                        sender == null ? null : sender.getName(), chatType));
        ClientReceiveMessageEvents.GAME.register((message, actionBar) ->
                client.systemMessageReceived(Minecraft.getInstance(), message, actionBar));
    }
}
