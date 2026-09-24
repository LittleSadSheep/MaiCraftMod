// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonObject;

/** 验证能力搜索只交付小候选，不把所有参数契约或现场可用性混入发现结果。 */
public final class AbilitySearchTest {
    public static void main(String[] args) {
        var result = AbilitySearch.search("maicraft:build_machien", 5);
        var rows = result.getAsJsonArray("semantic_abilities");
        check(!rows.isEmpty() && rows.get(0).getAsJsonObject().get("ability").getAsString().equals("maicraft:build_machine"), "typo resolves to the exact registered operation");
        check(!result.get("contract_loaded").getAsBoolean() && !result.has("server_assistance"), "no full or live capability report");
        for (var value : rows) {
            var row = value.getAsJsonObject();
            check(!row.has("contract") && !row.has("parameters") && !row.has("supported"), "summary is not an executable contract");
            PublicToolCatalog.validateAndNormalize("perceive", row.get("read_arguments"));
        }
        var limited = AbilitySearch.search("machine", 1);
        // 动力候选由专用只读视图在区块索引内筛选，参数范围不能顺带放宽施工几何勘测。
        var power = new JsonObject(); power.addProperty("view", "kinetic_sources"); power.addProperty("query", "锁链传动轮");
        var normalizedPower = PublicToolCatalog.validateAndNormalize("perceive", power);
        check(normalizedPower.get("radius").getAsInt() == 32, "native power search has its own horizontal scope");
        for (String view : new String[]{"kinetic_sources", "construction_site"}) {
            var invalid = new JsonObject(); invalid.addProperty("view", view); invalid.addProperty("radius", view.equals("kinetic_sources") ? 65 : 9);
            try { PublicToolCatalog.validateAndNormalize("perceive", invalid); throw new AssertionError("unbounded survey accepted"); }
            catch (IllegalArgumentException expected) { /* 明确拒绝超界，不替模型扩大到别的楼层或整个世界。 */ }
        }
        // 原生产请求中的接线子操作须能从契约参数召回；过长的零命中查询则提示分开搜索。
        var operations = AbilitySearch.search("connect_external_input", 20).getAsJsonArray("semantic_abilities");
        check(operations.asList().stream().anyMatch(row -> row.getAsJsonObject().get("ability").getAsString().equals("maicraft:modify_machine")), "nested operation is discoverable without loading every ability");
        var empty = AbilitySearch.search("notrealxyz missingabcdef", 5);
        check(empty.get("total_matches").getAsInt() == 0 && empty.getAsJsonArray("suggested_queries").size() == 2,
                "zero results recommend individual terms, not another catalog dump");
        check(limited.get("total_matches").getAsInt() > 1 && limited.get("truncated").getAsBoolean(), "candidate limit is explicit");
        var query = new JsonObject(); query.addProperty("view", "abilities"); query.addProperty("query", "build machien");
        PublicToolCatalog.validateAndNormalize("perceive", query);
        for (String key : new String[]{"focus", "resource_uri"}) {
            var mixed = query.deepCopy(); mixed.addProperty(key, key.equals("focus") ? "maicraft:build_machine" : "maicraft://knowledge/index");
            reject(mixed);
        }
        query.addProperty("view", "situation"); reject(query);
        check(AbilitySearch.search("精密构件", 5).getAsJsonArray("semantic_abilities").isEmpty(), "a product is not invented as an operation");
        System.out.println("AbilitySearchTest: passed");
    }
    private static void reject(JsonObject query) {
        try { PublicToolCatalog.validateAndNormalize("perceive", query); throw new AssertionError("ambiguous query accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("query"), "specific discovery argument error"); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
