// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.UUID;

/** Public client-thread operation facade shared by semantic tasks and the MCP capability report. */
public final class ServerAssistClient {
    private ServerAssistClient() {}

    public static boolean supported(String operation) { return ServerSessionRuntime.router().supported(operation); }
    public static boolean renegotiating(String operation) { return ServerSessionRuntime.router().renegotiating(operation); }
    public static boolean takeExpiredReadForRefresh(UUID requestId) { return ServerSessionRuntime.router().takeExpiredReadForRefresh(requestId); }
    public static boolean serverSupported(String operation) {
        return ServerSessionRuntime.installed() && ServerSessionRuntime.router().serverSupported(operation);
    }
    public static boolean nativeFallbackAllowed(String operation) {
        return !ServerSessionRuntime.installed() || ServerSessionRuntime.router().nativeFallbackAllowed(operation);
    }

    public static ClientRequestReceipt submit(String operation, JsonObject arguments, boolean mutating) {
        return submit(operation, arguments, mutating, ServerSessionRuntime.submissionOwner());
    }

    /** Explicit ownership lets cancellation retire queued remote work before the next client tick. */
    public static ClientRequestReceipt submit(String operation, JsonObject arguments, boolean mutating, String ownerTaskId) {
        ClientRequestReceipt receipt = ServerSessionRuntime.router().submit(operation, arguments, mutating);
        ServerSessionRuntime.rememberOwner(receipt, ownerTaskId);
        return receipt;
    }

    public static Optional<ClientRequestReceipt.Snapshot> poll(UUID id) { return ServerSessionRuntime.router().poll(id); }
    public static Optional<ClientRequestReceipt.Snapshot> query(UUID id) { return ServerSessionRuntime.router().query(id); }
    public static void cancel(UUID id) { ServerSessionRuntime.router().cancel(id); }
    public static JsonObject capabilityReport() { return ServerSessionRuntime.router().capabilityReport(); }

    /** Register contracts before connecting; equivalent native backends may be attached afterward. */
    public static void register(ClientOperation operation) { ServerSessionRuntime.router().register(operation); }
}
