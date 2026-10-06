// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** 宿主不解析引用时也能发现对象参数；真实 HTTP 交接只记录目标，不驱动游戏角色。 */
public final class McpSchemaCompatibilityTest {
    private final HttpClient client = HttpClient.newHttpClient();
    private URI endpoint;
    private String session;
    private int sequence;

    public static void main(String[] args) throws Exception { new McpSchemaCompatibilityTest().run(); }

    private void run() throws Exception {
        var runtime = new RecordingRuntime();
        try (client; var service = new EmbeddedMcpService(McpConfig.local(0), runtime)) {
            service.start(); endpoint = URI.create("http://127.0.0.1:" + service.port() + "/mcp");
            send("initialize", json("""
                    {"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"schema-test","version":"1"}}
                    """));
            // 直接检查网络公布的定义，防止修好了本地模板却仍向第三方宿主发送引用。
            JsonArray tools = send("tools/list", new JsonObject()).getAsJsonArray("tools");
            check(tools.size() == 4, "四个公开入口仍可发现");
            for (JsonElement value : tools) {
                var tool = value.getAsJsonObject();
                var schema = tool.getAsJsonObject("inputSchema");
                assertInline(schema);
                if (!tool.get("name").getAsString().equals("perceive")) assertGoalShape(goalSchema(tool));
            }
            // 子目标允许继续嵌套；网络入口仍按每一层的游戏目标规则补默认值并检查地点。
            for (String tool : List.of("plan", "execute", "task")) {
                JsonObject request = arguments(tool, nestedGoal(2));
                JsonObject before = request.deepCopy();
                check(!call(tool, request).get("isError").getAsBoolean(), "对象目标应进入运行时: " + tool);
                check(runtime.last.equals(PublicToolCatalog.validateAndNormalize(tool, before)), "HTTP 交接保留完整目标");
                check(request.equals(before), "调用不能修改宿主持有的原始目标");
                check(!call(tool, arguments(tool, nestedGoal(32))).get("isError").getAsBoolean(), "原有深度上限内仍可提交");
                reject(tool, arguments(tool, nestedGoal(33)), runtime, "嵌套过深");
                reject(tool, arguments(tool, new JsonPrimitive(nestedGoal(0).toString())), runtime, "字符串冒充目标");
                JsonObject badChild = nestedGoal(1);
                badChild.getAsJsonArray("children").set(0, JsonParser.parseString("\"{}\""));
                reject(tool, arguments(tool, badChild), runtime, "字符串冒充子目标");
                // 宿主把整数坐标写成 "0" 时入口按声明类型还原后受理；无法还原成整数的写法仍拒收。
                JsonObject spelledTarget = nestedGoal(1);
                spelledTarget.getAsJsonArray("children").get(0).getAsJsonObject().getAsJsonObject("target")
                        .getAsJsonObject("position").addProperty("x", "0");
                check(!call(tool, arguments(tool, spelledTarget)).get("isError").getAsBoolean(), "字符串整数坐标还原后受理");
                JsonObject badTarget = nestedGoal(1);
                badTarget.getAsJsonArray("children").get(0).getAsJsonObject().getAsJsonObject("target")
                        .getAsJsonObject("position").addProperty("x", "zero");
                reject(tool, arguments(tool, badTarget), runtime, "子目标坐标仍须为整数");
                JsonObject badConstraint = nestedGoal(0);
                badConstraint.getAsJsonArray("constraints").get(0).getAsJsonObject().addProperty("hard", "true");
                reject(tool, arguments(tool, badConstraint), runtime, "约束布尔值不能字符串化");
            }
            // JSON 外观的聊天文本属于玩家要求的字面内容，不能因兼容宿主而擅自解析成对象。
            JsonObject literal = json("{\"ability\":\"maicraft:chat\",\"outcome\":\"发送原文\",\"parameters\":{}}");
            literal.getAsJsonObject("parameters").addProperty("message", "{\"keep\":\"text\"}");
            check(!call("execute", arguments("execute", literal)).get("isError").getAsBoolean(), "文本可正常交接");
            check(runtime.last.getAsJsonObject("goal").getAsJsonObject("parameters").get("message").equals(
                            literal.getAsJsonObject("parameters").get("message")),
                    "聊天文本保持字符串");
        }
        System.out.println("McpSchemaCompatibilityTest: passed");
    }

    static JsonObject goalSchema(JsonObject tool) {
        var properties = tool.getAsJsonObject("inputSchema").getAsJsonObject("properties");
        return tool.get("name").getAsString().equals("task")
                ? properties.getAsJsonObject("answer").getAsJsonArray("anyOf").get(1).getAsJsonObject()
                        .getAsJsonObject("properties").getAsJsonObject("details").getAsJsonObject("properties").getAsJsonObject("goal")
                : properties.getAsJsonObject("goal");
    }

    private static void assertInline(JsonElement value) {
        // 模拟只认识内联类型的宿主：任何层级留下引用都可能再次把坐标或目标当成字符串。
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            check(!object.has("$ref") && !object.has("$defs"), "公开参数不能依赖 $ref/$defs");
            object.entrySet().forEach(entry -> assertInline(entry.getValue()));
        } else if (value.isJsonArray()) value.getAsJsonArray().forEach(McpSchemaCompatibilityTest::assertInline);
    }

    private static void assertGoalShape(JsonObject goal) {
        check(goal.get("type").getAsString().equals("object"), "目标明确声明对象类型");
        check(goal.getAsJsonArray("required").equals(JsonParser.parseString("[\"ability\",\"outcome\"]")), "保留必填目标字段");
        var fields = goal.getAsJsonObject("properties");
        var target = fields.getAsJsonObject("target").getAsJsonArray("anyOf").get(0).getAsJsonObject();
        check(target.getAsJsonArray("oneOf").size() == 4, "保留地点规则");
        var position = target.getAsJsonObject("properties").getAsJsonObject("position").getAsJsonArray("anyOf").get(0).getAsJsonObject();
        check(position.getAsJsonObject("properties").getAsJsonObject("x").get("type").getAsString().equals("integer"), "坐标内联为整数");
        var constraint = fields.getAsJsonObject("constraints").getAsJsonObject("items");
        check(constraint.getAsJsonObject("properties").has("hard"), "约束保留字段定义");
        var child = fields.getAsJsonObject("children").getAsJsonObject("items");
        check(child.get("type").getAsString().equals("object") && child.getAsJsonArray("required").equals(goal.get("required")),
                "递归边界仍声明对象和必填字段");
        child.getAsJsonArray("required").forEach(key -> check(child.getAsJsonObject("properties").has(key.getAsString()),
                "宿主能在 properties 中找到子目标的必填字段"));
    }

    private static JsonObject nestedGoal(int depth) {
        JsonObject goal = json("""
                {"ability":"maicraft:find_block","outcome":"确认附近的切石机","target":{"kind":"coordinates",
                 "position":{"x":0,"y":65,"z":0}},"parameters":{"block_ids":["minecraft:stonecutter"]},
                 "constraints":[{"kind":"maicraft:avoid_danger","description":"保持安全"}]}
                """);
        for (int i = 0; i < depth; i++) {
            JsonObject parent = json("{\"ability\":\"maicraft:sequence\",\"outcome\":\"按顺序检查\"}");
            var children = new JsonArray(); children.add(goal); parent.add("children", children); goal = parent;
        }
        return goal;
    }

    private static JsonObject arguments(String tool, JsonElement goal) {
        if (tool.equals("task")) {
            JsonObject request = json("""
                    {"action":"answer","task_id":"00000000-0000-4000-8000-000000000001",
                     "answer":{"decision_id":"00000000-0000-4000-8000-000000000002","choice":"recover","details":{}}}
                    """);
            request.getAsJsonObject("answer").getAsJsonObject("details").add("goal", goal); return request;
        }
        var request = new JsonObject(); request.add("goal", goal);
        if (tool.equals("execute")) request.addProperty("request_key", "schema-compatibility");
        return request;
    }

    private void reject(String tool, JsonObject arguments, RecordingRuntime runtime, String reason) throws Exception {
        int calls = runtime.calls;
        check(call(tool, arguments).get("isError").getAsBoolean(), "应拒绝: " + reason);
        check(runtime.calls == calls, "非法参数不能进入游戏运行时: " + reason);
    }

    private JsonObject call(String tool, JsonObject arguments) throws Exception {
        var params = new JsonObject(); params.addProperty("name", tool); params.add("arguments", arguments);
        return send("tools/call", params);
    }

    private JsonObject send(String method, JsonObject params) throws Exception {
        var rpc = new JsonObject(); rpc.addProperty("jsonrpc", "2.0"); rpc.addProperty("id", ++sequence);
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

    private static final class RecordingRuntime implements RuntimeFacade {
        private JsonObject last;
        private int calls;
        // 用记录器截住规划、执行和恢复，确保测试覆盖交接但不会令玩家移动或发送聊天。
        private CompletionStage<JsonElement> record(JsonObject arguments) {
            last = arguments.deepCopy(); calls++; return CompletableFuture.completedFuture(new JsonObject());
        }
        public CompletionStage<JsonElement> plan(JsonObject arguments) { return record(arguments); }
        public CompletionStage<JsonElement> execute(JsonObject arguments) { return record(arguments); }
        public CompletionStage<JsonElement> task(JsonObject arguments) { return record(arguments); }
        public CompletionStage<JsonElement> perceive(JsonObject arguments) { throw new AssertionError("不应观察世界"); }
        public CompletionStage<JsonElement> readAttention() { return CompletableFuture.completedFuture(new JsonObject()); }
        public CompletionStage<JsonElement> readChat() { return CompletableFuture.completedFuture(new JsonObject()); }
        public AutoCloseable subscribeAttention(Consumer<JsonElement> listener) { return () -> {}; }
        public AutoCloseable subscribeChat(Consumer<JsonElement> listener) { return () -> {}; }
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
