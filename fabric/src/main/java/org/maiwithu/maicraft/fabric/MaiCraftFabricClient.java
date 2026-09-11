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

/**
 * Fabric 的客户端接线入口，把加载器事件连接到公共运行时、预览和消息观察。
 * 它不另写一套任务调度或身体动作，两个加载器尽量共用同一实现。
 */
public final class MaiCraftFabricClient implements ClientModInitializer {
    @Override
    // 先登记公共工具和任务，再把启动、每刻更新、退出接到 Fabric 事件上；真正的玩法逻辑仍在公共模块。
    public void onInitializeClient() {
        FabricOptionalServerClient.install();
        // 先建立工具和任务执行器的对应关系，客户端启动后再开放 MCP 接单。
        MaiCraftCore.init();
        ClientLifecycleEvents.CLIENT_STARTED.register(client ->
                ClientRuntime.start(MaiCraftRuntimeFacade.instance()));
        // 持续任务由游戏 tick 推进；网络请求本身只负责提交和查询任务。
        ClientTickEvents.END_CLIENT_TICK.register(ClientRuntime::tick);
        ClientTickEvents.END_CLIENT_TICK.register(PreviewController::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClientRuntime.stop());
        // 聊天消息保留发送者身份；游戏提示按系统文字交给注意事件系统，这里没有单独使用动作栏 overlay 标志。
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receivedAt) ->
                GameplayAttentionMonitor.chat(
                        sender.getName(), sender.getId(), message.getString(), false));
        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
                GameplayAttentionMonitor.chat(null, null, message.getString(), true));
        // 注册本地 maicraft 状态与预览命令，不向服务器登记同名管理员命令。
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(PreviewCommands.attach(ClientCommandManager.literal("maicraft")
                        .then(ClientCommandManager.literal("status").executes(context ->
                                MaiCraftStatus.showInChat())))));
    }
}
