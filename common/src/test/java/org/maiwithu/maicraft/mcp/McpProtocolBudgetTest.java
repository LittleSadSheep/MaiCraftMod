package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** 工具发现保留字段契约与恢复入口，防止把每项能力的说明再次塞回所有请求的共享前缀。 */
public final class McpProtocolBudgetTest {
    public static void main(String[] args) {
        var definitions = PublicToolCatalog.definitions(); int chars = definitions.toString().length();
        System.out.println("McpProtocolBudgetTest: tool definitions=" + chars + " chars");
        check(chars < 18000, "tool discovery exceeds the shared-context budget");
        for (var value : definitions) {
            var tool = value.getAsJsonObject(); var schema = tool.getAsJsonObject("inputSchema");
            check(!tool.has("outputSchema"), "single text payload does not advertise a structured output schema");
            check(!schema.has("oneOf") && !schema.has("allOf"), "ordinary object tools remain discoverable by hosts");
        }
        JsonObject get = JsonParser.parseString("{\"action\":\"get\",\"task_id\":\"00000000-0000-4000-8000-000000000001\"}").getAsJsonObject();
        check(PublicToolCatalog.validateAndNormalize("task", get).get("limit").getAsInt() == 5, "small default history page");
        var index = JsonParser.parseString("{\"view\":\"abilities\",\"offset\":5}").getAsJsonObject();
        PublicToolCatalog.validateAndNormalize("perceive", index);
        index.addProperty("query", "build");
        try { PublicToolCatalog.validateAndNormalize("perceive", index); throw new AssertionError("search silently ignored index offset"); }
        catch (IllegalArgumentException expected) { /* 同一次请求只能读取一种页，不能悄悄忽略页码。 */ }
        System.out.println("McpProtocolBudgetTest: passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
