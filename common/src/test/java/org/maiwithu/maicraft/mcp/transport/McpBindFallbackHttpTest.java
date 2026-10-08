// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** 端口被占时的让行启动：真实绑定被占端口后按端口递增让行，全部被占时响亮失败；同机多开客户端各自拿到可用端点。 */
class McpBindFallbackHttpTest {
    @Test
    void freeConfiguredPortIsUsedAsIs() throws Exception {
        int free;
        try (ServerSocket spare = bindLocal()) {
            free = spare.getLocalPort();
        }
        try (EmbeddedMcpService service = EmbeddedMcpService.startWithFallback(McpConfig.local(free), 4)) {
            assertTrue(service.isRunning());
            assertEquals(free, service.port());
        }
    }

    @Test
    void occupiedBasePortFallsForwardAndStillServes() throws Exception {
        try (ServerSocket occupied = bindLocal()) {
            int base = occupied.getLocalPort();
            try (EmbeddedMcpService service = EmbeddedMcpService.startWithFallback(McpConfig.local(base), 4)) {
                assertTrue(service.isRunning());
                assertTrue(service.port() > base && service.port() <= base + 4,
                        "让行落到配置端口之后的空闲端口");
                // 让行后的端点必须真的在服务，而不只是绑定成功：initialize 往返拿到会话。
                HttpResponse<String> init = HttpClient.newHttpClient().send(
                        initialize(service.port()), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, init.statusCode());
                assertTrue(init.headers().firstValue("MCP-Session-Id").isPresent());
                assertTrue(init.body().contains("maicraft"));
            }
        }
    }

    @Test
    void exhaustedFallbackRangeFailsLoudly() throws Exception {
        try (ServerSocket first = bindLocal();
             ServerSocket second = bindAt(first.getLocalPort() + 1);
             ServerSocket third = bindAt(first.getLocalPort() + 2)) {
            // 全部候选端口都被占时不能启动成功；启动失败的候选由服务自己关闭。
            assertThrows(IOException.class,
                    () -> EmbeddedMcpService.startWithFallback(McpConfig.local(first.getLocalPort()), 2).close());
        }
    }

    @Test
    void stoppedTransportReleasesItsPortForTheNextService() throws Exception {
        int first;
        try (EmbeddedMcpService original = EmbeddedMcpService.startWithFallback(McpConfig.local(0), 0)) {
            first = original.port();
            original.stop();
            assertFalse(original.isRunning());
        }
        try (EmbeddedMcpService rebound = EmbeddedMcpService.startWithFallback(McpConfig.local(first), 0)) {
            assertEquals(first, rebound.port(), "关停后配置端口可被新服务重取");
            HttpResponse<String> init = HttpClient.newHttpClient().send(
                    initialize(rebound.port()), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, init.statusCode());
            assertTrue(init.headers().firstValue("MCP-Session-Id").isPresent());
        }
    }

    private static HttpRequest initialize(int port) {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 1);
        request.addProperty("method", "initialize");
        request.add("params", new JsonObject());
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                .build();
    }

    private static ServerSocket bindLocal() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.bind(new InetSocketAddress("127.0.0.1", 0));
        return socket;
    }

    private static ServerSocket bindAt(int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.bind(new InetSocketAddress("127.0.0.1", port));
        return socket;
    }
}
