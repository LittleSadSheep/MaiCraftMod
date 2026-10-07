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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.MachinePlanPreflight;

/**
 * 嵌套对象里猜错字段名时，拒收错误直接给出本层合法键，并附上请求里实际用到的嵌套参数的完整说明；
 * 只用标量参数的请求被拒时不附这些说明。规划只走语义契约检查，不驱动游戏角色。
 */
public final class NestedFieldCorrectionTest {
    private final HttpClient client = HttpClient.newHttpClient();
    private URI endpoint;
    private String session;
    private int sequence;

    public static void main(String[] args) throws Exception { new NestedFieldCorrectionTest().run(); }

    private void run() throws Exception {
        try (client; var service = new EmbeddedMcpService(McpConfig.local(0), new ContractRuntime())) {
            service.start(); endpoint = URI.create("http://127.0.0.1:" + service.port() + "/mcp");
            send("initialize", json("""
                    {"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"nested-field-test","version":"1"}}
                    """));
            // 设计审阅的蓝图方块把 offset 写成 pos：报错列出方块层合法键，并附 design_machine 的 blueprint 完整说明。
            JsonObject blueprint = error(call("""
                    {"goal":{"ability":"maicraft:design_machine","outcome":"审阅置物台",
                     "parameters":{"blueprint":{"schema_version":1,"blocks":[{"pos":[0,0,0],"block_id":"minecraft:stone"}]}}}}"""));
            check(blueprint.get("message").getAsString().contains("pos; accepted keys: block_id, nbt, offset, properties"),
                    "blueprint target lists accepted keys: " + blueprint.get("message"));
            check(contracts(blueprint).equals(List.of("maicraft:design_machine#blueprint")), "blueprint contract attached: " + contracts(blueprint));
            check(blueprint.getAsJsonArray("parameter_contracts").get(0).getAsJsonObject().get("description").getAsString()
                    .contains("offset"), "attached description names the nested fields");
            // 顺序任务里目的地把 y 写成 height：只附被拒的 travel 的 destination 说明，不带同序列里设计审阅的蓝图。
            JsonObject travel = error(call("""
                    {"goal":{"ability":"maicraft:sequence","outcome":"先走再审阅","children":[
                      {"ability":"maicraft:travel","outcome":"走到工地","parameters":{"destination":{"x":1,"height":64,"z":2}}},
                      {"ability":"maicraft:design_machine","outcome":"审阅布局",
                       "parameters":{"blueprint":{"schema_version":1,"blocks":[{"offset":[0,0,0],"block_id":"minecraft:stone"}]}}}]}}"""));
            check(travel.get("message").getAsString().contains("height; accepted keys: dimension, x, y, z"),
                    "destination lists accepted keys: " + travel.get("message"));
            check(contracts(travel).equals(List.of("maicraft:travel#destination")), "only the rejected ability's contract: " + contracts(travel));
            // 入口拒收（设计审阅只给勘察编号、没给同址目标）时还不知道哪一层出错，按目标树附上实际出现的嵌套参数说明。
            JsonObject entry = error(call("""
                    {"goal":{"ability":"maicraft:design_machine","outcome":"审阅布局","parameters":{"snapshot_id":"site-1",
                     "blueprint":{"schema_version":1,"blocks":[{"offset":[0,0,0],"block_id":"minecraft:stone"}]}}}}"""));
            check("invalid_arguments".equals(entry.get("code").getAsString()), "entry rejection: " + entry);
            check(contracts(entry).equals(List.of("maicraft:design_machine#blueprint")), "entry attaches nested contract: " + contracts(entry));
            // 只用标量参数被拒时没有可附的嵌套说明，错误保持精简，仍有顶层签名可用。
            JsonObject scalar = error(call("""
                    {"goal":{"ability":"maicraft:acquire_items","outcome":"取铁锭","parameters":{"item_id":"minecraft:iron_ingot","colour":"red"}}}"""));
            check(!scalar.has("parameter_contracts") && scalar.has("ability_signature"), "scalar rejection stays compact: " + scalar);
        }
        System.out.println("NestedFieldCorrectionTest: passed");
    }

    private static List<String> contracts(JsonObject error) {
        List<String> names = new ArrayList<>();
        if (error.has("parameter_contracts")) error.getAsJsonArray("parameter_contracts").forEach(value -> names.add(
                value.getAsJsonObject().get("ability").getAsString() + "#" + value.getAsJsonObject().get("parameter").getAsString()));
        return names;
    }

    private static JsonObject error(JsonObject result) {
        check(result.get("isError").getAsBoolean(), "request should be rejected: " + result);
        String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        return json(text).getAsJsonObject("error");
    }

    private JsonObject call(String arguments) throws Exception {
        var params = new JsonObject(); params.addProperty("name", "plan"); params.add("arguments", json(arguments));
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

    /** 规划只做真实运行时同样的语义契约检查（含嵌套蓝图与目的地解析），不观察世界、不登记任务。 */
    private static final class ContractRuntime implements RuntimeFacade {
        public CompletionStage<JsonElement> plan(JsonObject arguments) {
            try {
                return CompletableFuture.completedFuture(MachinePlanPreflight.review(Goal.fromJson(arguments.getAsJsonObject("goal"))));
            } catch (RuntimeException rejected) {
                return CompletableFuture.failedFuture(rejected);
            }
        }
        public CompletionStage<JsonElement> execute(JsonObject arguments) { throw new AssertionError("不应执行"); }
        public CompletionStage<JsonElement> task(JsonObject arguments) { throw new AssertionError("不应控制任务"); }
        public CompletionStage<JsonElement> perceive(JsonObject arguments) { throw new AssertionError("不应观察世界"); }
        public CompletionStage<JsonElement> readAttention() { return CompletableFuture.completedFuture(new JsonObject()); }
        public CompletionStage<JsonElement> readChat() { return CompletableFuture.completedFuture(new JsonObject()); }
        public AutoCloseable subscribeAttention(Consumer<JsonElement> listener) { return () -> {}; }
        public AutoCloseable subscribeChat(Consumer<JsonElement> listener) { return () -> {}; }
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError("nested field correction: " + message); }
}
