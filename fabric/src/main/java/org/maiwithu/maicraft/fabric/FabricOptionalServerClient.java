// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.fabric;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.maiwithu.maicraft.client.server.ServerSessionRuntime;
import org.maiwithu.maicraft.network.ClientProtocolBridge;
import org.maiwithu.maicraft.network.OptionalServerPayload;

final class FabricOptionalServerClient {
    private FabricOptionalServerClient() {}

    static void install() {
        ClientProtocolBridge.installTransport(new ClientProtocolBridge.Transport() {
            @Override public boolean available() { return ClientPlayNetworking.canSend(OptionalServerPayload.TYPE); }
            @Override public void send(JsonObject envelope) { ClientPlayNetworking.send(OptionalServerPayload.of(envelope)); }
        });
        ServerSessionRuntime.install();
        ClientPlayNetworking.registerGlobalReceiver(OptionalServerPayload.TYPE, (payload, context) ->
                ClientProtocolBridge.receive(payload.json()));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ClientProtocolBridge.connected());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientProtocolBridge.disconnected());
    }
}
