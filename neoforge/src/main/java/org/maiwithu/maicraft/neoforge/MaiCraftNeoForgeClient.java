// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.client.command.MaiCraftStatus;
import org.maiwithu.maicraft.client.preview.PreviewCommands;
import org.maiwithu.maicraft.client.preview.PreviewController;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.MaiCraftCore;
import org.maiwithu.maicraft.mcp.MaiCraftRuntimeFacade;

/**
 * NeoForge 的客户端入口，作用与 Fabric 入口相同，只是事件类型和注册方式不同。
 * @Mod 的 dist=CLIENT 才是这份入口只在客户端执行的直接限制。
 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public final class MaiCraftNeoForgeClient {
    // 只在客户端加载；设置阶段挂到 Mod 事件总线，游戏每刻、聊天、命令和退出挂到全局事件总线。
    public MaiCraftNeoForgeClient(IEventBus modBus) {
        modBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onChatReceived);
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
        NeoForge.EVENT_BUS.addListener(this::onGameShuttingDown);
    }

    // 把初始化排到客户端工作队列，登记公共功能并启动 MCP 运行时。
    private void onClientSetup(FMLClientSetupEvent event) {
        // 初始化安排到客户端工作队列，先完成注册，再开放 MCP 接单。
        event.enqueueWork(() -> {
            MaiCraftCore.init();
            ClientRuntime.start(MaiCraftRuntimeFacade.instance());
        });
    }

    private void onClientTick(ClientTickEvent.Post event) {
        // 每次客户端 tick 结束后推进共享运行时，具体业务逻辑不放在加载器事件里。
        ClientRuntime.tick(Minecraft.getInstance());
        PreviewController.tick(Minecraft.getInstance());
    }

    // 系统提示没有发送者；普通消息尽量从当前连接查玩家名字，查不到时保留 UUID 和文字。
    private void onChatReceived(ClientChatReceivedEvent event) {
        var senderId = event.isSystem() ? null : event.getSender();
        String senderName = null;
        if (senderId != null && Minecraft.getInstance().getConnection() != null) {
            var info = Minecraft.getInstance().getConnection().getPlayerInfo(senderId);
            senderName = info == null ? null : info.getProfile().getName();
        }
        GameplayAttentionMonitor.chat(
                senderName, senderId, event.getMessage().getString(), event.isSystem());
    }

    // 本地命令只显示状态、处理预览，执行逻辑仍由公共 PreviewCommands 提供。
    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(PreviewCommands.attach(Commands.literal("maicraft")
                .then(Commands.literal("status").executes(context ->
                        MaiCraftStatus.showInChat()))));
    }

    // 退出时停止运行时与相关服务，避免继续保留玩家控制和监听。
    private void onGameShuttingDown(GameShuttingDownEvent event) {
        ClientRuntime.stop();
    }
}
