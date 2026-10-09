// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge;

import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.maiwithu.maicraft.bootstrap.Bootstrap;
import org.maiwithu.maicraft.bootstrap.ClientLifecycle;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.game.serverlink.LinkTransport;
import org.maiwithu.maicraft.network.MaiCraftPayload;

/**
 * NeoForge 的客户端入口：dist=CLIENT 限定它只在客户端加载，只把客户端事件转给公共代码，不写业务。
 * 新增客户端事件时，先在 {@link ClientLifecycle} 加方法，再在这里和 Fabric 入口各接一行。
 */
@Mod(value = ModIdentity.MOD_ID, dist = Dist.CLIENT)
public final class MaiCraftNeoForgeClient {
    private final ClientLifecycle client;

    public MaiCraftNeoForgeClient(IEventBus modBus) {
        // 与服务端 MaiCraft 的发送通道；接收由公共入口的载荷登记转给 NeoForgeClientLink 再到这里。
        client = Bootstrap.startClient(new NeoForgeLoaderEnvironment(), new LinkTransport() {
            @Override public boolean available() {
                var connection = Minecraft.getInstance().getConnection();
                return connection != null && connection.hasChannel(MaiCraftPayload.TYPE);
            }
            @Override public void send(JsonObject envelope) {
                PacketDistributor.sendToServer(MaiCraftPayload.of(envelope));
            }
        });
        // 客户端准备完成后创建内核与能力 -> 每刻结束推进角色当前的任务与会话 -> 退出前收尾。
        modBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(this::onGameShuttingDown);
        NeoForge.EVENT_BUS.addListener(this::onChatReceived);
    }

    // 创建工作排到客户端主线程的工作队列，保证访问游戏对象时在正确的线程上。
    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            client.started();
            NeoForgeClientLink.install(client);
        });
    }

    private void onClientTick(ClientTickEvent.Post event) {
        client.tickEnd(Minecraft.getInstance());
    }

    private void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        client.serverLinkDisconnected(Minecraft.getInstance());
    }

    private void onGameShuttingDown(GameShuttingDownEvent event) {
        client.stopping();
    }

    // 聊天栏收到的消息分三种：系统消息、有签名的玩家消息（带原话与 UUID）、没签名的玩家消息（只有整行），有什么给什么。
    private void onChatReceived(ClientChatReceivedEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event instanceof ClientChatReceivedEvent.System system) {
            client.systemMessageReceived(minecraft, system.getMessage(), system.isOverlay());
        } else if (event instanceof ClientChatReceivedEvent.Player player) {
            client.playerChatReceived(minecraft, player.getMessage(), player.getPlayerChatMessage().decoratedContent(),
                    player.getSender(), null, player.getBoundChatType());
        } else {
            client.playerChatReceived(minecraft, event.getMessage(), null, null, null, event.getBoundChatType());
        }
    }
}

/** 公共入口与客户端入口之间的接缝：两个入口是各自加载的实例，服务端发来的信封从这里转给客户端部分。 */
final class NeoForgeClientLink {
    private static final AtomicReference<ClientLifecycle> CLIENT = new AtomicReference<>();

    private NeoForgeClientLink() {}

    static void install(ClientLifecycle client) { CLIENT.set(client); }

    static void received(String json) {
        var client = CLIENT.get();
        if (client != null) client.serverLinkReceived(Minecraft.getInstance(), json);
    }
}
