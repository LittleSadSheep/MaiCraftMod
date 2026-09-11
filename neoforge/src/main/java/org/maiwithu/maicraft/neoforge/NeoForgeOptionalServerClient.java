// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.maiwithu.maicraft.client.server.ServerSessionRuntime;
import org.maiwithu.maicraft.network.ClientProtocolBridge;
import org.maiwithu.maicraft.network.OptionalServerPayload;

final class NeoForgeOptionalServerClient {
    private NeoForgeOptionalServerClient() {}

    static void install() {
        ClientProtocolBridge.installTransport(new ClientProtocolBridge.Transport() {
            @Override public boolean available() {
                var connection = Minecraft.getInstance().getConnection();
                return connection != null && connection.hasChannel(OptionalServerPayload.TYPE);
            }
            @Override public void send(JsonObject envelope) { PacketDistributor.sendToServer(OptionalServerPayload.of(envelope)); }
        });
        ServerSessionRuntime.install();
        NeoForge.EVENT_BUS.addListener(NeoForgeOptionalServerClient::login);
        NeoForge.EVENT_BUS.addListener(NeoForgeOptionalServerClient::logout);
    }

    private static void login(ClientPlayerNetworkEvent.LoggingIn event) { ClientProtocolBridge.connected(); }
    private static void logout(ClientPlayerNetworkEvent.LoggingOut event) { ClientProtocolBridge.disconnected(); }
}
