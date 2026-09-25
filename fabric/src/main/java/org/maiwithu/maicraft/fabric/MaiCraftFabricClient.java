// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.chat.ChatMonitor;
import org.maiwithu.maicraft.client.command.MaiCraftStatus;
import org.maiwithu.maicraft.client.preview.PreviewCommands;
import org.maiwithu.maicraft.client.preview.PreviewController;
import org.maiwithu.maicraft.core.MaiCraftCore;
import org.maiwithu.maicraft.mcp.MaiCraftRuntimeFacade;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.core.build.BuildingBudgets;
import org.maiwithu.maicraft.core.pathing.settings.ClearanceWhitelist;

/**
 * Fabric 的客户端接线入口，把加载器事件连接到公共运行时、预览和消息观察。
 * 它不另写一套任务调度或身体动作，两个加载器尽量共用同一实现。
 */
public final class MaiCraftFabricClient implements ClientModInitializer {
    @Override
    // 先登记公共工具和任务，再把启动、每刻更新、退出接到 Fabric 事件上；真正的玩法逻辑仍在公共模块。
    public void onInitializeClient() {
        // 先读取本实例的建筑预算，再登记工具；大模型的公开限制与随后施工使用同一份启动配置。
        BuildingBudgets.initialize(
                Minecraft.getInstance().gameDirectory.toPath());
        // 接单前载入清障类型白名单，让挖路和施工第一次下手就遵守同一份玩家配置。
        ClearanceWhitelist.initialize(Minecraft.getInstance().gameDirectory.toPath());
        FabricOptionalServerClient.install();
        // 先建立工具和任务执行器的对应关系，客户端启动后再开放 MCP 接单。
        MaiCraftCore.init();
        ClientLifecycleEvents.CLIENT_STARTED.register(client ->
                ClientRuntime.start(MaiCraftRuntimeFacade.instance()));
        // 持续任务由游戏 tick 推进；网络请求本身只负责提交和查询任务。
        ClientTickEvents.END_CLIENT_TICK.register(ClientRuntime::tick);
        ClientTickEvents.END_CLIENT_TICK.register(PreviewController::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClientRuntime.stop());
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                ScreenEvents.afterRender(screen).register((rendered, graphics, mouseX, mouseY, delta) ->
                        MenuVisibility.rendered(rendered)));
        // 聊天区消息进入独立聊天流，保留发送者；动作栏提示不混入玩家对话。
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receivedAt) -> {
            // Fabric 允许资料或签名消息缺失；有签名身份时仍保留它，不能因未同步名字而丢掉整条聊天。
            var senderId = sender != null ? sender.getId() : signedMessage == null ? null : signedMessage.sender();
            ChatMonitor.player(sender == null ? null : sender.getName(), senderId, message.getString());
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
                ChatMonitor.system(message.getString(), overlay));
        // 注册本地 maicraft 状态与预览命令，不向服务器登记同名管理员命令。
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(PreviewCommands.attach(ClientCommandManager.literal("maicraft")
                        .then(ClientCommandManager.literal("status").executes(context ->
                                MaiCraftStatus.showInChat())))));
    }
}
