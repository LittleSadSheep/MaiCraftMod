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

/** NeoForge 的客户端接线入口，与 Fabric 共用同一套功能注册、MCP 和任务运行时。 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public final class MaiCraftNeoForgeClient {
    public MaiCraftNeoForgeClient(IEventBus modBus) {
        modBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onChatReceived);
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
        NeoForge.EVENT_BUS.addListener(this::onGameShuttingDown);
    }

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

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(PreviewCommands.attach(Commands.literal("maicraft")
                .then(Commands.literal("status").executes(context ->
                        MaiCraftStatus.showInChat()))));
    }

    private void onGameShuttingDown(GameShuttingDownEvent event) {
        ClientRuntime.stop();
    }
}
