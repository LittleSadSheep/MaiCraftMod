// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonObject;
import java.util.HashSet;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.SemanticAbilityCatalog;

/** 验证能力名一次交付且可直接 focus；概要与搜索完整返回，参数只随选定契约读取。 */
public final class AbilitySearchTest {
    public static void main(String[] args) {
        verifyCompleteCatalog();
        var result = AbilitySearch.search("maicraft:build_machien");
        var rows = result.getAsJsonArray("semantic_abilities");
        check(!rows.isEmpty() && rows.get(0).getAsJsonObject().get("ability").getAsString().equals("maicraft:build_machine"), "typo resolves to the exact registered operation");
        check(!result.get("contract_loaded").getAsBoolean() && !result.has("server_assistance"), "no full or live capability report");
        for (var value : rows) {
            var row = value.getAsJsonObject();
            check(!row.has("contract") && !row.has("parameters") && !row.has("supported"), "summary is not an executable contract");
            PublicToolCatalog.validateAndNormalize("perceive", row.get("read_arguments"));
        }
        var matches = AbilitySearch.search("machine");
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
        var operations = AbilitySearch.search("connect_external_input").getAsJsonArray("semantic_abilities");
        check(operations.asList().stream().anyMatch(row -> row.getAsJsonObject().get("ability").getAsString().equals("maicraft:modify_machine")), "nested operation is discoverable without loading every ability");
        var empty = AbilitySearch.search("notrealxyz missingabcdef");
        check(empty.get("total_matches").getAsInt() == 0 && empty.getAsJsonArray("suggested_queries").size() == 2,
                "zero results recommend individual terms, not another catalog dump");
        check(matches.get("total_matches").getAsInt() > 1
                && matches.getAsJsonArray("semantic_abilities").size() == matches.get("total_matches").getAsInt()
                && !matches.has("truncated") && !matches.has("next_offset"), "every ranked candidate is returned together");
        var query = new JsonObject(); query.addProperty("view", "abilities"); query.addProperty("query", "build machien");
        PublicToolCatalog.validateAndNormalize("perceive", query);
        for (String key : new String[]{"focus", "resource_uri"}) {
            var mixed = query.deepCopy(); mixed.addProperty(key, key.equals("focus") ? "maicraft:build_machine" : "maicraft://knowledge/index");
            reject(mixed);
        }
        query.addProperty("view", "situation"); reject(query);
        check(AbilitySearch.search("精密构件").getAsJsonArray("semantic_abilities").isEmpty(), "a product is not invented as an operation");
        System.out.println("AbilitySearchTest: passed");
    }

    private static void verifyCompleteCatalog() {
        // 模型先看全部动作名，第二次就能读取任一契约；旧页长不能藏掉末尾能力，也不提前混入用途。
        var request = new JsonObject(); request.addProperty("view", "abilities"); request.addProperty("limit", 1);
        var normalized = PublicToolCatalog.validateAndNormalize("perceive", request);
        var catalog = AbilitySearch.index(normalized);
        var names = catalog.getAsJsonArray("semantic_abilities");
        check(catalog.size() == 1 && names.size() > 20, "default response contains only the complete names array");
        var discovered = new HashSet<String>(); String previous = "";
        for (var name : names) {
            check(name.isJsonPrimitive() && name.getAsJsonPrimitive().isString(), "name is a string without metadata");
            String id = name.getAsString();
            check(discovered.add(id) && previous.compareTo(id) < 0, "names are unique and sorted"); previous = id;
            var focus = new JsonObject(); focus.addProperty("view", "abilities"); focus.addProperty("focus", id);
            var selected = PublicToolCatalog.validateAndNormalize("perceive", focus);
            check(SemanticAbilityCatalog.describe(selected.get("focus").getAsString()).has("parameters"),
                    "every returned name directly selects a parameter contract");
        }
        for (String id : IntentRuntime.KNOWN_ABILITIES)
            check(discovered.contains(id) != SemanticAbilityCatalog.compatibilityAlias(id), "every public ability is present");
        // 需要比较用途时仍一次给全；所有概要与名称层一一对应，兼容别名继续只由显式 focus 读取。
        request.addProperty("detail", "summary");
        var summaries = AbilitySearch.index(PublicToolCatalog.validateAndNormalize("perceive", request));
        var rows = summaries.getAsJsonArray("semantic_abilities");
        check(rows.size() == names.size() && !summaries.has("next_offset"), "summaries are complete despite limit=1");
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i).getAsJsonObject();
            check(row.size() == 2 && row.get("ability").equals(names.get(i)) && row.has("summary"), "summary layer keeps ID and purpose only");
        }
        // 目录层级不能混入其他观察或精确契约读取，避免参数被悄悄忽略后返回错误层级。
        for (String detail : new String[]{"full", ""}) {
            request.addProperty("detail", detail);
            rejectDetail(request);
        }
        request.addProperty("detail", "summary"); request.addProperty("focus", "maicraft:acquire"); rejectDetail(request);
        request.remove("focus"); request.addProperty("query", "machine"); rejectDetail(request);
        request.remove("query"); request.addProperty("view", "situation"); rejectDetail(request);
    }

    private static void rejectDetail(JsonObject request) {
        try { PublicToolCatalog.validateAndNormalize("perceive", request); throw new AssertionError("invalid catalog detail accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("detail"), "detail error identifies the field"); }
    }
    private static void reject(JsonObject query) {
        try { PublicToolCatalog.validateAndNormalize("perceive", query); throw new AssertionError("ambiguous query accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("query"), "specific discovery argument error"); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
