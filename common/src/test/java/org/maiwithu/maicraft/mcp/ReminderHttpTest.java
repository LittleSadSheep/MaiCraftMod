// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.client.runtime.LowLightCombatReminder;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 不订阅 attention，通过真实 HTTP 验证每条公开工具通道都附当前提醒，且不改写业务成败。 */
public final class ReminderHttpTest {
    private final HttpClient client = HttpClient.newHttpClient();
    private URI endpoint;
    private String session;
    private int sequence;

    public static void main(String[] args) throws Exception { new ReminderHttpTest().run(); }

    private void run() throws Exception {
        var runtime = new Runtime();
        try (client; var service = new EmbeddedMcpService(McpConfig.local(0), runtime)) {
            service.start(); endpoint = URI.create("http://127.0.0.1:" + service.port() + "/mcp");
            send("initialize", new JsonObject());
            for (int tick = 0; tick < 3; tick++) runtime.hit(tick);
            for (String name : List.of("perceive", "plan", "execute", "task")) {
                JsonObject result = call(name, switch (name) {
                    case "plan", "execute" -> json("""
                            {"goal":{"ability":"maicraft:travel","outcome":"前往工地"}}
                            """);
                    case "task" -> json("{\"action\":\"list\"}");
                    default -> json("{\"view\":\"situation\"}");
                });
                check(!result.get("isError").getAsBoolean(), "追加提醒不能把成功回执改成失败");
                checkReminders(result, runtime);
                if (name.equals("execute")) {
                    // 先冻结旧行动回执，再增加一次真实命中；读取旧回执时附的应是第四次命中后的当前提醒。
                    String uri = firstPayload(result).get("details_uri").getAsString();
                    runtime.hit(3);
                    JsonObject args = new JsonObject(); args.addProperty("resource_uri", uri);
                    JsonObject frozen = call("perceive", args);
                    checkReminders(frozen, runtime);
                    JsonObject page = firstPayload(frozen);
                    check(page.get("snapshot_only").getAsBoolean() && !page.getAsJsonObject("value").has("reminders")
                            && runtime.executions == 1, "旧回执的原件不混入当前提醒，也不重做行动");
                }
            }
            JsonObject invalid = call("plan", new JsonObject());
            check(invalid.get("isError").getAsBoolean()
                    && firstPayload(invalid).getAsJsonObject("error").get("code").getAsString().equals("invalid_arguments"),
                    "参数拒绝保持原错误码");
            checkReminders(invalid, runtime);
            runtime.fail = true;
            JsonObject failed = call("task", json("{\"action\":\"list\"}"));
            check(failed.get("isError").getAsBoolean(), "运行时失败仍如实报告");
            checkReminders(failed, runtime);
            // 原生知识正文没有普通 JSON 外壳，也必须经统一出口带上风险提醒，正文和结构化元信息保持原样。
            JsonObject knowledge = call("perceive", json("{\"resource_uri\":\"maicraft://knowledge/reminder-test\"}"));
            check(knowledge.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()
                    .equals("# 补光资料\n保持原文") && knowledge.has("structuredContent"), "知识正文格式不被提醒包装改变");
            checkReminders(knowledge, runtime);
            runtime.board.clear(); runtime.fail = false;
            check(!firstPayload(call("perceive", json("{\"view\":\"situation\"}"))).has("reminders"),
                    "现场提醒撤下后不再附旧提醒或空包装");
        }
        System.out.println("ReminderHttpTest: passed");
    }

    private static void checkReminders(JsonObject result, Runtime runtime) {
        JsonArray content = result.getAsJsonArray("content");
        // 普通回执的第一段 JSON 必须携带提醒；只有保持原文的知识正文使用附加文本块。
        boolean document = result.has("structuredContent");
        check(content.size() == (document ? 2 : 1), "普通回执仅一份 JSON，文档保持原文并另附提醒");
        JsonArray reminders = json(content.get(document ? 1 : 0).getAsJsonObject().get("text").getAsString()).getAsJsonArray("reminders");
        check(reminders.equals(runtime.reminders()), "返回完整且最新的证据，多次读取不消费提醒");
        check(reminders.get(0).getAsJsonObject().get("message").getAsString().equals(
                "当前亮度较低，你可能正频繁遭遇怪物攻击。可使用补光功能（maicraft:light_area）减少怪物刷新。"),
                "主提醒直接说明低光、频繁遭袭和可用的补光功能");
    }

    private JsonObject call(String name, JsonObject args) throws Exception {
        JsonObject params = new JsonObject(); params.addProperty("name", name); params.add("arguments", args);
        return send("tools/call", params);
    }

    private JsonObject send(String method, JsonObject params) throws Exception {
        JsonObject rpc = new JsonObject(); rpc.addProperty("jsonrpc", "2.0"); rpc.addProperty("id", ++sequence);
        rpc.addProperty("method", method); rpc.add("params", params);
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").header("Accept", "application/json");
        if (session != null) request.header("MCP-Session-Id", session).header("MCP-Protocol-Version", "2025-11-25");
        var response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(rpc.toString())).build(), HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == 200, "HTTP 请求成功");
        response.headers().firstValue("MCP-Session-Id").ifPresent(value -> session = value);
        JsonObject body = json(response.body()); check(!body.has("error"), "JSON-RPC 请求成功: " + body);
        return body.getAsJsonObject("result");
    }

    private static final class Runtime implements RuntimeFacade {
        final ReminderBoard board = new ReminderBoard(ignored -> {});
        final LowLightCombatReminder rule = new LowLightCombatReminder(board);
        int executions;
        boolean fail;
        void hit(long tick) {
            rule.observe(new LowLightCombatReminder.Observation(tick, "minecraft:overworld", BlockPos.ZERO, 0, 15, 0), "minecraft:zombie");
        }
        public JsonArray reminders() { return board.snapshot(); }
        public CompletionStage<JsonElement> perceive(JsonObject args) { return ready(new JsonObject()); }
        public CompletionStage<JsonElement> plan(JsonObject args) { return ready(new JsonObject()); }
        public CompletionStage<JsonElement> task(JsonObject args) {
            return fail ? CompletableFuture.failedFuture(new IllegalStateException("world unavailable")) : ready(new JsonObject());
        }
        public CompletionStage<JsonElement> execute(JsonObject args) {
            executions++; JsonObject value = new JsonObject(); value.addProperty("accepted", true);
            value.addProperty("observation", "完整现场证据".repeat(2000)); return ready(value);
        }
        public CompletionStage<JsonElement> knowledge(JsonObject args) {
            return ready(json("{\"contents\":[{\"uri\":\"maicraft://knowledge/reminder-test\",\"mimeType\":\"text/markdown\",\"text\":\"# 补光资料\\n保持原文\"}]}"));
        }
        public CompletionStage<JsonElement> readAttention() { return ready(new JsonObject()); }
        public CompletionStage<JsonElement> readChat() { return ready(new JsonObject()); }
        public AutoCloseable subscribeAttention(Consumer<JsonElement> listener) { return () -> {}; }
        public AutoCloseable subscribeChat(Consumer<JsonElement> listener) { return () -> {}; }
        private CompletionStage<JsonElement> ready(JsonObject value) { return CompletableFuture.completedFuture(value); }
    }

    private static JsonObject firstPayload(JsonObject result) {
        return json(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
    }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
