// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.client.command.MaiCraftStatus;
import org.maiwithu.maicraft.client.preview.PreviewCommands;
import org.maiwithu.maicraft.client.preview.PreviewController;
import org.maiwithu.maicraft.core.MaiCraftCore;
import org.maiwithu.maicraft.mcp.MaiCraftRuntimeFacade;

/** Fabric 的客户端接线入口：注册功能、启动本地 MCP，并把游戏事件转交给共享运行时。 */
public final class MaiCraftFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 先建立工具和任务执行器的对应关系，客户端启动后再开放 MCP 接单。
        MaiCraftCore.init();
        ClientLifecycleEvents.CLIENT_STARTED.register(client ->
                ClientRuntime.start(MaiCraftRuntimeFacade.instance()));
        // 持续任务由游戏 tick 推进；网络请求本身只负责提交和查询任务。
        ClientTickEvents.END_CLIENT_TICK.register(ClientRuntime::tick);
        ClientTickEvents.END_CLIENT_TICK.register(PreviewController::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClientRuntime.stop());
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receivedAt) ->
                GameplayAttentionMonitor.chat(
                        sender.getName(), sender.getId(), message.getString(), false));
        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
                GameplayAttentionMonitor.chat(null, null, message.getString(), true));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(PreviewCommands.attach(ClientCommandManager.literal("maicraft")
                        .then(ClientCommandManager.literal("status").executes(context ->
                                MaiCraftStatus.showInChat())))));
    }
}
