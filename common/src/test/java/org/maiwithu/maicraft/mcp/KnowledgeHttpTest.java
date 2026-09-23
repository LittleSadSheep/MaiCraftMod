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
import java.util.HashMap;
import org.maiwithu.maicraft.core.blueprint.BuildingModelContract;
import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.List;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestFixture;
import org.maiwithu.maicraft.core.integration.ftbquests.FtbRewardFixture.Reward;
import org.maiwithu.maicraft.mcp.knowledge.FtbQuestsKnowledgeSource;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeDocument;

/** Real JSON-RPC/HTTP checks against the actual embedded service, with no Minecraft world. */
public final class KnowledgeHttpTest {
    private final HttpClient client = HttpClient.newHttpClient();
    private URI endpoint;
    private String session;
    private int sequence;

    public static void main(String[] args) throws Exception { new KnowledgeHttpTest().run(); }

    private void run() throws Exception {
        PonderFixture.compiled = 0;
        var quests = new FtbQuestFixture();
        var ponder = new PonderKnowledgeSource(PonderFixture.access(), id -> "测试插件");
        var ftb = new FtbQuestsKnowledgeSource(quests.access);
        // 在同一服务中发现教程和任务书，验证两种入口都不会误走角色操作接口。
        var knowledge = new KnowledgeLibrary(new KnowledgeLibrary.Source() {
            public List<KnowledgeDocument.Entry> entries() {
                var entries = new ArrayList<>(ponder.entries()); entries.addAll(ftb.entries()); return entries;
            }
            public KnowledgeDocument read(String uri) {
                return uri.startsWith(FtbQuestsKnowledgeSource.PREFIX) ? ftb.read(uri) : ponder.read(uri);
            }
            public JsonArray templates() { var templates = ponder.templates(); templates.addAll(ftb.templates()); return templates; }
        });
        try (var server = new EmbeddedMcpService(McpConfig.local(0), new Runtime(knowledge))) {
            server.start(); endpoint = URI.create("http://127.0.0.1:" + server.port() + "/mcp");
            var initialized = send("initialize", json("{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"knowledge-test\",\"version\":\"1\"}}"));
            check(initialized.getAsJsonObject("result").get("instructions").getAsString().contains(KnowledgeLibrary.INDEX), "initial discovery hint");
            JsonObject list = send("resources/list", new JsonObject()).getAsJsonObject("result");
            // 风格目录增长后第三方教程可能在下一页；按游标读完元数据，不靠首页位置发现资源。
            var allResources = list.getAsJsonArray("resources").deepCopy();
            var page = list;
            while (page.has("nextCursor")) {
                var cursor = new JsonObject(); cursor.add("cursor", page.get("nextCursor"));
                page = send("resources/list", cursor).getAsJsonObject("result");
                allResources.addAll(page.getAsJsonArray("resources"));
            }
            String scene = null;
            boolean attention = false;
            boolean chatflow = false;
            for (var element : allResources) {
                var row = element.getAsJsonObject();
                check(!row.has("text"), "resources/list must not preload tutorial bodies");
                String uri = row.get("uri").getAsString();
                attention |= uri.equals("maicraft://attention");
                chatflow |= uri.equals("maicraft://chatflow");
                if (uri.startsWith(PonderKnowledgeSource.SCENE)) scene = uri;
            }
            check(attention && chatflow && scene != null && PonderFixture.compiled == 0,
                    "keep attention and chatflow, and discover foreign Ponder scenes");
            verifyQuests(quests, allResources);
            // 标准HTTP发现与资源读取必须保留完整Schema；纯资料查询不会委派世界感知或开始施工。
            var contract = BuildingModelContract.current();
            check(allResources.asList().stream().anyMatch(value ->
                    value.getAsJsonObject().get("uri").getAsString().equals(BuildingModelContract.INDEX_URI)),
                    "building contract index must be discoverable over HTTP");
            var contractContent = send("resources/read",uri(BuildingModelContract.INDEX_URI))
                    .getAsJsonObject("result").getAsJsonArray("contents").get(0).getAsJsonObject();
            var contractIndex = json(contractContent.get("text").getAsString());
            check(contractIndex.get("design_schema_uri").getAsString().equals(contract.schemaUri()),"HTTP index must identify the exact current schema");
            // 每篇教材经标准 MCP 与四工具回退通道读出的正文完全相同，不能截成摘要或另套模板。
            for (var tutorial : contractIndex.getAsJsonArray("resources")) {
                String tutorialUri = tutorial.getAsJsonObject().get("uri").getAsString();
                String expected = knowledge.read(tutorialUri).text();
                var document = send("resources/read", uri(tutorialUri)).getAsJsonObject("result").getAsJsonArray("contents").get(0).getAsJsonObject();
                var fallbackRequest = json("{\"name\":\"perceive\",\"arguments\":{\"view\":\"knowledge\"}}");
                fallbackRequest.getAsJsonObject("arguments").addProperty("resource_uri", tutorialUri);
                var fallbackResult = send("tools/call", fallbackRequest).getAsJsonObject("result");
                check(document.get("text").getAsString().equals(expected) && !fallbackResult.get("isError").getAsBoolean()
                        && fallbackResult.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().equals(expected), "full tutorial over both HTTP routes");
            }
            var schemaContent = send("resources/read",uri(contract.schemaUri())).getAsJsonObject("result")
                    .getAsJsonArray("contents").get(0).getAsJsonObject();
            check(schemaContent.get("text").getAsString().equals(contract.schemaText())
                    && schemaContent.get("mimeType").getAsString().equals("application/schema+json"),"HTTP resource must retain the full versioned schema");
            var schemaRequest = json("{\"name\":\"perceive\",\"arguments\":{\"view\":\"knowledge\"}}");
            schemaRequest.getAsJsonObject("arguments").addProperty("resource_uri",contract.schemaUri());
            var schemaFallback = send("tools/call",schemaRequest).getAsJsonObject("result");
            check(!schemaFallback.get("isError").getAsBoolean() && schemaFallback.getAsJsonArray("content").get(0).getAsJsonObject()
                    .get("text").getAsString().equals(contract.schemaText()),"perceive fallback must return the same complete JSON schema");
            var templates = send("resources/templates/list", new JsonObject()).getAsJsonObject("result");
            var templateMimes = new HashMap<String, String>();
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
            check(send("resources/subscribe", uri("maicraft://chatflow")).has("result"), "chatflow subscription accepted");
            check(send("resources/read", uri("maicraft://chatflow")).getAsJsonObject("result").has("contents"), "chatflow reads preserved");
            var invalid = send("tools/call", json("{\"name\":\"perceive\",\"arguments\":{\"view\":\"situation\",\"resource_uri\":\"maicraft://knowledge/index\"}}"));
            check(invalid.getAsJsonObject("result").get("isError").getAsBoolean(), "knowledge fields cannot be silently ignored by other views");
        }
        System.out.println("KnowledgeHttpTest: passed");
    }

    private void verifyQuests(FtbQuestFixture fixture, JsonArray resources) throws Exception {
        // 原生奖励定义经过同一个 HTTP 入口读取，模型取得候选信息时不会调用领取工具。
        fixture.quest.rewards.add(new Reward(10, "item"));
        String quest = FtbQuestsKnowledgeSource.QUEST + "FEDCBA9876543210";
        check(resources.asList().stream().anyMatch(value -> value.getAsJsonObject().get("uri").getAsString().equals(quest))
                && fixture.quest.bodyReads == 0, "HTTP 列举可见任务但不加载正文");
        var content = send("resources/read", uri(quest)).getAsJsonObject("result").getAsJsonArray("contents").get(0).getAsJsonObject();
        check(content.get("mimeType").getAsString().equals("application/json"), "FTB Resource 是结构化 JSON");
        var request = json("{\"name\":\"perceive\",\"arguments\":{\"view\":\"knowledge\"}}");
        request.getAsJsonObject("arguments").addProperty("resource_uri", quest);
        var result = send("tools/call", request).getAsJsonObject("result");
        var fallback = json(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
        check(!result.get("isError").getAsBoolean() && fallback.getAsJsonObject("quest")
                .equals(json(content.get("text").getAsString()).getAsJsonObject("quest")), "工具与资源入口返回相同任务事实");
        String rewardUri = fallback.getAsJsonObject("quest").getAsJsonObject("rewards").getAsJsonArray("entries")
                .get(0).getAsJsonObject().get("uri").getAsString();
        var rewardContent = send("resources/read", uri(rewardUri)).getAsJsonObject("result").getAsJsonArray("contents").get(0).getAsJsonObject();
        request.getAsJsonObject("arguments").addProperty("resource_uri", rewardUri);
        var rewardFallback = send("tools/call", request).getAsJsonObject("result");
        check(json(rewardContent.get("text").getAsString()).getAsJsonObject("rewards").equals(json(rewardFallback
                .getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()).getAsJsonObject("rewards")), "奖励内容也在 Resource 和工具入口保持一致");
        fixture.quest.visible = false;
        check(send("resources/read", uri(quest)).getAsJsonObject("error").get("code").getAsInt() == -32002,
                "任务隐藏后不能用旧 URI 继续读正文");
        check(send("tools/call", request).getAsJsonObject("result").get("isError").getAsBoolean(), "兼容工具同样拒绝隐藏任务");
        fixture.quest.visible = true;
        check(send("resources/subscribe", uri(FtbQuestsKnowledgeSource.INDEX)).getAsJsonObject("error").get("code").getAsInt() == -32602,
                "任务书明确按需读取，不假装支持订阅更新");
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
        public CompletionStage<JsonElement> readChat() { return CompletableFuture.completedFuture(new JsonObject()); }
        public AutoCloseable subscribeChat(Consumer<JsonElement> listener) { return () -> {}; }
    }
}
