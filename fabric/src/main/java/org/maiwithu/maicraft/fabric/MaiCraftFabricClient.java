// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.core.MaiCraftCore;
import org.maiwithu.maicraft.mcp.MaiCraftRuntimeFacade;

/** Fabric bootstrap for the single MaiCraft client runtime. */
public final class MaiCraftFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MaiCraftCore.init();
        ClientLifecycleEvents.CLIENT_STARTED.register(client ->
                ClientRuntime.start(MaiCraftRuntimeFacade.instance()));
        ClientTickEvents.END_CLIENT_TICK.register(ClientRuntime::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClientRuntime.stop());
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receivedAt) ->
                GameplayAttentionMonitor.chat(
                        sender.getName(), sender.getId(), message.getString(), false));
        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
                GameplayAttentionMonitor.chat(null, null, message.getString(), true));
    }
}
