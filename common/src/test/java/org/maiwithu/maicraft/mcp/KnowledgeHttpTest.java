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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.maiwithu.maicraft.core.integration.ponder.PonderFixture;
import org.maiwithu.maicraft.core.integration.ponder.PonderBlueprintStore;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;
import org.maiwithu.maicraft.mcp.knowledge.PonderKnowledgeSource;

/** Real JSON-RPC/HTTP checks against the actual embedded service, with no Minecraft world. */
public final class KnowledgeHttpTest {
    private final HttpClient client = HttpClient.newHttpClient();
    private URI endpoint;
    private String session;
    private int sequence;

    public static void main(String[] args) throws Exception { new KnowledgeHttpTest().run(); }

    private void run() throws Exception {
        PonderFixture.compiled = 0;
        var knowledge = new KnowledgeLibrary(new PonderKnowledgeSource(PonderFixture.access(), id -> "测试插件"));
        try (var server = new EmbeddedMcpService(McpConfig.local(0), new Runtime(knowledge))) {
            server.start(); endpoint = URI.create("http://127.0.0.1:" + server.port() + "/mcp");
            var initialized = send("initialize", json("{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"knowledge-test\",\"version\":\"1\"}}"));
            check(initialized.getAsJsonObject("result").get("instructions").getAsString().contains(KnowledgeLibrary.INDEX), "initial discovery hint");
            JsonObject list = send("resources/list", new JsonObject()).getAsJsonObject("result");
            String scene = null;
            boolean attention = false;
            for (var element : list.getAsJsonArray("resources")) {
                var row = element.getAsJsonObject();
                check(!row.has("text"), "resources/list must not preload tutorial bodies");
                String uri = row.get("uri").getAsString();
                attention |= uri.equals("maicraft://attention");
                if (uri.startsWith(PonderKnowledgeSource.SCENE)) scene = uri;
            }
            check(attention && scene != null && PonderFixture.compiled == 0, "keep attention and discover foreign Ponder scenes");
            var templates = send("resources/templates/list", new JsonObject()).getAsJsonObject("result");
            var templateMimes = new java.util.HashMap<String, String>();
            templates.getAsJsonArray("resourceTemplates").forEach(element -> {
                var row = element.getAsJsonObject(); templateMimes.put(row.get("name").getAsString(), row.get("mimeType").getAsString());
            });
            check("text/markdown".equals(templateMimes.get("ponder.component")) && "text/markdown".equals(templateMimes.get("ponder.scene"))
                    && "text/markdown".equals(templateMimes.get("ponder.replay")) && "application/json".equals(templateMimes.get("ponder.structure")),
                    "discover narration, replay and JSON structure templates by identity");
            JsonObject search = send("tools/call", json("{\"name\":\"perceive\",\"arguments\":{\"view\":\"knowledge\",\"focus\":\"addon:machine\"}}"));
            check(!search.getAsJsonObject("result").get("isError").getAsBoolean() && PonderFixture.compiled == 0, "model-driven metadata search without world access");
            var contents = send("resources/read", uri(scene)).getAsJsonObject("result").getAsJsonArray("contents");
            check(contents.get(0).getAsJsonObject().get("text").getAsString().contains("A source rule"), "standard resource Markdown");
            String structure = PonderBlueprintStore.put("http_fixture", json("{\"schema_version\":1,\"blocks\":[{\"offset\":[0,0,0],\"block_id\":\"minecraft:stone\",\"properties\":{}}],\"evidence\":{\"projection_complete\":true}}"));
            var structureContent = send("resources/read", uri(structure)).getAsJsonObject("result").getAsJsonArray("contents").get(0).getAsJsonObject();
            check(structureContent.get("mimeType").getAsString().equals("application/json")
                    && JsonParser.parseString(structureContent.get("text").getAsString()).getAsJsonObject().getAsJsonArray("blocks").size() == 1,
                    "structure resource survives HTTP as JSON with the common blueprint envelope");
            JsonObject args = new JsonObject(); args.addProperty("view", "knowledge"); args.addProperty("resource_uri", scene);
            JsonObject tool = new JsonObject(); tool.addProperty("name", "perceive"); tool.add("arguments", args);
            JsonObject fallback = send("tools/call", tool).getAsJsonObject("result");
            check(fallback.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().startsWith("# Custom"), "fallback returns direct Markdown, not escaped JSON");
            check(!fallback.getAsJsonObject("structuredContent").toString().contains("A source rule"), "do not duplicate long body in structured output");
            check(send("resources/read", uri("file:///private")).getAsJsonObject("error").get("code").getAsInt() == -32002, "unknown URI code");
            check(send("resources/list", json("{\"cursor\":\"broken\"}")).getAsJsonObject("error").get("code").getAsInt() == -32602, "invalid cursor code");
            check(send("resources/subscribe", uri("maicraft://attention")).has("result"), "existing subscriptions preserved");
            check(send("resources/read", uri("maicraft://attention")).getAsJsonObject("result").has("contents"), "attention reads preserved");
            var invalid = send("tools/call", json("{\"name\":\"perceive\",\"arguments\":{\"view\":\"situation\",\"resource_uri\":\"maicraft://knowledge/index\"}}"));
            check(invalid.getAsJsonObject("result").get("isError").getAsBoolean(), "knowledge fields cannot be silently ignored by other views");
        }
        System.out.println("KnowledgeHttpTest: passed");
    }

    private JsonObject send(String method, JsonObject params) throws Exception {
        JsonObject message = new JsonObject(); message.addProperty("jsonrpc", "2.0");
        message.addProperty("id", ++sequence); message.addProperty("method", method); message.add("params", params);
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(message.toString()));
        if (session != null) request.header("MCP-Session-Id", session).header("MCP-Protocol-Version", "2025-11-25");
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == 200, "HTTP status " + response.statusCode());
        response.headers().firstValue("MCP-Session-Id").ifPresent(value -> session = value);
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static JsonObject uri(String uri) { JsonObject value = new JsonObject(); value.addProperty("uri", uri); return value; }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }

    private record Runtime(KnowledgeLibrary library) implements RuntimeFacade {
        public CompletionStage<JsonElement> knowledge(JsonObject args) { return CompletableFuture.completedFuture(library.request(args)); }
        public CompletionStage<JsonElement> perceive(JsonObject args) { throw new AssertionError("World perception must not be used for knowledge"); }
        public CompletionStage<JsonElement> plan(JsonObject args) { throw new AssertionError("No planning"); }
        public CompletionStage<JsonElement> execute(JsonObject args) { throw new AssertionError("No execution"); }
        public CompletionStage<JsonElement> task(JsonObject args) { throw new AssertionError("No tasks"); }
        public CompletionStage<JsonElement> readAttention() { return CompletableFuture.completedFuture(new JsonObject()); }
        public AutoCloseable subscribeAttention(Consumer<JsonElement> listener) { return () -> {}; }
    }
}
