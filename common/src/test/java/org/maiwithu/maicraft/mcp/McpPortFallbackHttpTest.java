// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** 端口被占时的让行启动：真实绑定被占端口后按端口递增让行，全部被占时响亮失败。同机多开客户端靠它各自拿到可用端点。 */
public final class McpPortFallbackHttpTest {
    public static void main(String[] args) throws Exception {
        // 空闲端口上不做任何让行：配置端口就是实际端口，行为与旧的单次启动一致。
        int free;
        try (ServerSocket spare = bindLocal()) {
            free = spare.getLocalPort();
        }
        try (EmbeddedMcpService service = EmbeddedMcpService.startWithFallback(
                McpConfig.local(free), new StubRuntime(), 4)) {
            check(service.isRunning() && service.port() == free,
                    "free configured port is used as-is");
        }

        // 预占配置端口，模拟先启动的另一个客户端；让行落到后续空闲端口且真实可服务。
        try (ServerSocket occupied = bindLocal()) {
            int base = occupied.getLocalPort();
            try (EmbeddedMcpService service = EmbeddedMcpService.startWithFallback(
                    McpConfig.local(base), new StubRuntime(), 4)) {
                check(service.isRunning() && service.port() > base && service.port() <= base + 4,
                        "occupied base port falls forward to a nearby free port");
                // 让行后的端点必须真的在服务，而不只是绑定成功：initialize 往返拿到会话。
                HttpResponse<String> init = HttpClient.newHttpClient().send(initialize(service.port()),
                        HttpResponse.BodyHandlers.ofString());
                check(init.statusCode() == 200 && init.headers().firstValue("MCP-Session-Id").isPresent()
                                && init.body().contains("\"maicraft\""),
                        "fallen-back endpoint serves a real MCP initialize");
            }
        }

        // 让行窗口内的连续端口全部被占时抛出绑定异常，不无声吞掉启动失败。
        try (ServerSocket first = bindLocal();
             ServerSocket second = bindAt(first.getLocalPort() + 1);
             ServerSocket third = bindAt(first.getLocalPort() + 2)) {
            try (EmbeddedMcpService ignored = EmbeddedMcpService.startWithFallback(
                    McpConfig.local(first.getLocalPort()), new StubRuntime(), 2)) {
                throw new AssertionError("exhausted fallback range must fail, not start a service");
            } catch (IOException expected) {
                check(expected.getLocalizedMessage() != null, "exhausted fallback carries the bind failure");
            }
        }
        // 传输整体搬移：旧服务关停后配置端口可被新服务重取，initialize 往返证明新端点真实可服务。
        int first;
        try (EmbeddedMcpService original = EmbeddedMcpService.startWithFallback(
                McpConfig.local(0), new StubRuntime(), 0)) {
            first = original.port();
            original.stop();
            check(!original.isRunning(), "stopped transport reports not running");
        }
        try (EmbeddedMcpService rebound = EmbeddedMcpService.startWithFallback(
                McpConfig.local(first), new StubRuntime(), 0)) {
            check(rebound.port() == first, "rebind reuses the freed configured port");
            HttpResponse<String> init = HttpClient.newHttpClient().send(initialize(rebound.port()),
                    HttpResponse.BodyHandlers.ofString());
            check(init.statusCode() == 200 && init.headers().firstValue("MCP-Session-Id").isPresent(),
                    "rebound endpoint serves a fresh initialize");
        }
        System.out.println("McpPortFallbackHttpTest: passed");
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

    /** 启动路径只用到订阅挂载；其余入口被调用即测试失败。 */
    private static final class StubRuntime implements RuntimeFacade {
        @Override
        public CompletionStage<JsonElement> perceive(JsonObject args) { throw new AssertionError("no perceive"); }
        @Override
        public CompletionStage<JsonElement> readAttention() { throw new AssertionError("no attention read"); }
        @Override
        public AutoCloseable subscribeAttention(Consumer<JsonElement> listener) { return () -> {}; }
        @Override
        public CompletionStage<JsonElement> readChat() { throw new AssertionError("no chat read"); }
        @Override
        public AutoCloseable subscribeChat(Consumer<JsonElement> listener) { return () -> {}; }
        @Override
        public CompletionStage<JsonElement> plan(JsonObject args) { throw new AssertionError("no plan"); }
        @Override
        public CompletionStage<JsonElement> execute(JsonObject args) { throw new AssertionError("no execute"); }
        @Override
        public CompletionStage<JsonElement> task(JsonObject args) { throw new AssertionError("no task"); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
