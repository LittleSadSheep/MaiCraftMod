// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.maiwithu.maicraft.mcp.knowledge.web.WebFetch;
import org.maiwithu.maicraft.mcp.knowledge.web.WebKnowledgeService;

/** 从真实 MCP HTTP 入口读取外部资料，证明未进世界也能查阅，且不会转成角色动作。 */
public final class WebKnowledgeHttpTest {
    private URI endpoint;
    private String session;
    private int sequence;
    private final HttpClient client = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception { new WebKnowledgeHttpTest().run(); }

    private void run() throws Exception {
        var runtime = new ReferenceRuntime();
        try (client; var server = new EmbeddedMcpService(McpConfig.local(0), runtime)) {
            server.start(); endpoint = URI.create("http://127.0.0.1:" + server.port() + "/mcp");
            send("initialize", json("{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"web-test\",\"version\":\"1\"}}"));
            var tools = send("tools/list", new JsonObject()).getAsJsonArray("tools");
            check(tools.size() == 4 && tools.toString().contains("web_knowledge") && tools.toString().contains("subject_id"), "public discovery");
            var result = call(json("{\"url\":\"https://www.mcmod.cn/item/42.html\",\"subject_id\":\"minecraft:stone\"}"));
            check(!result.get("isError").getAsBoolean(), "successful read");
            var body = json(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
            check(body.get("status").getAsString().equals("ok") && body.getAsJsonArray("documents").get(0).getAsJsonObject()
                    .get("text").getAsString().contains("先接动力，再放原料"), "body reaches model text channel");
            check(runtime.last.get("view").getAsString().equals("web_knowledge") && runtime.last.get("limit").getAsInt() == 3, "normalized view and budget");
            for (String input : new String[]{"{\"view\":\"web_knowledge\"}",
                    "{\"view\":\"web_knowledge\",\"query\":\"stone\",\"url\":\"https://www.mcmod.cn/item/42.html\"}",
                    "{\"view\":\"knowledge\",\"url\":\"https://www.mcmod.cn/item/42.html\"}",
                    "{\"view\":\"web_knowledge\",\"query\":\"stone\",\"limit\":6}",
                    "{\"view\":\"web_knowledge\",\"query\":\"stone\",\"limit\":1.5}",
                    "{\"view\":\"web_knowledge\",\"query\":\"stone\",\"language\":\"fr\"}",
                    "{\"view\":\"web_knowledge\",\"query\":\"stone\",\"resource_uri\":\"maicraft://knowledge/index\"}",
                    "{\"view\":\"web_knowledge\",\"query\":\"stone\",\"focus\":\"minecraft:stone\"}",
                    "{\"url\":\"file:///private\"}"}) {
                int before = runtime.calls;
                check(call(json(input)).get("isError").getAsBoolean() && runtime.calls == before, "invalid query never reaches fetcher: " + input);
            }
        }
        System.out.println("WebKnowledgeHttpTest: passed");
    }

    private JsonObject call(JsonObject args) throws Exception {
        JsonObject params = new JsonObject(); params.addProperty("name", "perceive"); params.add("arguments", args);
        return send("tools/call", params);
    }
    private JsonObject send(String method, JsonObject params) throws Exception {
        JsonObject rpc = new JsonObject(); rpc.addProperty("jsonrpc", "2.0"); rpc.addProperty("id", ++sequence);
        rpc.addProperty("method", method); rpc.add("params", params);
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").header("Accept", "application/json");
        if (session != null) request.header("MCP-Session-Id", session).header("MCP-Protocol-Version", "2025-11-25");
        var response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(rpc.toString())).build(), HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == 200, "HTTP status");
        response.headers().firstValue("MCP-Session-Id").ifPresent(value -> session = value);
        JsonObject body = json(response.body()); check(!body.has("error"), "JSON-RPC: " + body);
        return body.getAsJsonObject("result");
    }

    private static final class ReferenceRuntime implements RuntimeFacade {
        JsonObject last; int calls;
        final WebKnowledgeService web = new WebKnowledgeService((uri, deadline) -> new WebFetch.Page(uri,
                "<title>示例教程</title><div class='item-content'>先接动力，再放原料。</div>", Instant.now(), "", false));
        public CompletionStage<JsonElement> perceive(JsonObject args) { last = args; calls++; return web.request(args); }
        public CompletionStage<JsonElement> plan(JsonObject args) { throw new AssertionError("must not plan actions"); }
        public CompletionStage<JsonElement> execute(JsonObject args) { throw new AssertionError("must not move player"); }
        public CompletionStage<JsonElement> task(JsonObject args) { throw new AssertionError("must not mutate tasks"); }
        public CompletionStage<JsonElement> readAttention() { return CompletableFuture.completedFuture(new JsonObject()); }
        public CompletionStage<JsonElement> readChat() { return CompletableFuture.completedFuture(new JsonObject()); }
        public AutoCloseable subscribeAttention(Consumer<JsonElement> listener) { return () -> {}; }
        public AutoCloseable subscribeChat(Consumer<JsonElement> listener) { return () -> {}; }
    }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
