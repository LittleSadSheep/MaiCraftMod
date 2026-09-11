// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import static org.maiwithu.maicraft.client.server.ClientRequestReceipt.Backend;

/** Decide support first; evaluate current conditions separately for each operation. */
final class ClientBackendSelection {
    record Choice(Backend backend, boolean supported, boolean ready, String reason) {
        JsonObject report(ClientOperation operation) {
            JsonObject value = new JsonObject();
            value.addProperty("registered", true);
            value.addProperty("supported", supported);
            value.addProperty("version", operation.version());
            value.addProperty("mutating", operation.mutating());
            value.addProperty("backend", backend.name().toLowerCase(java.util.Locale.ROOT));
            if (backend == Backend.SERVER && ready) {
                value.add("available", JsonNull.INSTANCE);
                value.addProperty("execution_conditions", "requires_server_validation");
            } else value.addProperty("available", ready);
            value.addProperty("reason", reason);
            return value;
        }
    }

    static Choice choose(ClientOperation operation, JsonObject arguments, ServerSessionConnection session,
                         boolean unresolvedMutation, boolean forceClient) {
        ServerCapabilityState caps = session.capabilities;
        if (caps.policyDenied(operation)) return unavailable("server_policy_denied");
        if (session.connection < 0) return unavailable("no_world");
        if (!forceClient && caps.state == ServerCapabilityState.State.NEGOTIATING)
            return unavailable("negotiating");
        boolean server = !forceClient && caps.supports(operation);
        boolean client = clientSupported(operation);
        if (!server && !client) return unavailable("operation_unsupported");
        Backend backend = server ? Backend.SERVER : Backend.CLIENT;
        if (operation.mutating() && unresolvedMutation)
            return new Choice(backend, true, false, "unresolved_mutation");
        if (operation.mutating() && !session.allowed)
            return new Choice(backend, true, false, "control_unavailable");
        if (server) return new Choice(backend, true, !operation.mutating() || session.mutationPermitted(),
                operation.mutating() && !session.mutationPermitted() ? "awaiting_control_ack" : "");
        try {
            ClientFallback.Availability local = operation.fallback().availability(arguments.deepCopy());
            return new Choice(backend, true, local.available(), local.reason());
        } catch (RuntimeException unavailable) {
            return new Choice(backend, true, false, "client_conditions_unavailable");
        }
    }

    static boolean clientSupported(ClientOperation operation) {
        try { return operation.fallback() != null && operation.fallback().supported(); }
        catch (RuntimeException unavailable) { return false; }
    }

    private static Choice unavailable(String reason) { return new Choice(Backend.UNSELECTED, false, false, reason); }
}
