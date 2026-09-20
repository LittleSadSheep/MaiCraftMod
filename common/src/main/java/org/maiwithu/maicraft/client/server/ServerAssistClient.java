// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.UUID;

/** 供语义任务和 MCP 能力查询共用的客户端线程操作入口。 */
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

    /** 明确记录请求所属任务，使取消任务时能在下一游戏刻前撤下排队的远端工作。 */
    public static ClientRequestReceipt submit(String operation, JsonObject arguments, boolean mutating, String ownerTaskId) {
        ClientRequestReceipt receipt = ServerSessionRuntime.router().submit(operation, arguments, mutating);
        ServerSessionRuntime.rememberOwner(receipt, ownerTaskId);
        return receipt;
    }

    public static Optional<ClientRequestReceipt.Snapshot> poll(UUID id) { return ServerSessionRuntime.router().poll(id); }
    public static Optional<ClientRequestReceipt.Snapshot> query(UUID id) { return ServerSessionRuntime.router().query(id); }
    public static void cancel(UUID id) { ServerSessionRuntime.router().cancel(id); }
    public static JsonObject capabilityReport() { return ServerSessionRuntime.router().capabilityReport(); }

    /** 连接前登记协议契约；行为等价的客户端原生后端可以稍后接入。 */
    public static void register(ClientOperation operation) { ServerSessionRuntime.router().register(operation); }
}
