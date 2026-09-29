// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.emi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 宽原料标签先共享重复展示数据；仍过大时保留配方身份与原生定义，不把整条配方抹成无名错误。 */
final class EmiRecipeCompaction {
    private EmiRecipeCompaction() {}

    static JsonObject fit(JsonObject original, int budget) {
        if (EmiRecipeKnowledge.encodedBytes(original) <= budget) return original;
        JsonObject compact = original.deepCopy(); JsonArray groups = new JsonArray();
        Map<JsonElement, Integer> known = new LinkedHashMap<>();
        for (String section : List.of("inputs", "catalysts", "workstations")) {
            if (!compact.has(section) || !compact.get(section).isJsonArray()) continue;
            for (JsonElement value : compact.getAsJsonArray(section)) {
                if (!value.isJsonObject()) continue;
                JsonObject input = value.getAsJsonObject(); JsonElement alternatives = input.get("alternatives");
                if (alternatives == null || !alternatives.isJsonArray()) continue;
                Integer index = known.get(alternatives);
                if (index == null) { index = groups.size(); known.put(alternatives, index); groups.add(alternatives); }
                input.remove("alternatives"); input.addProperty("alternatives_group", index);
            }
        }
        compact.add("alternative_groups", groups);
        // 共享的是已展示前缀；各原料自己的总数和截断标记保留，不能借相同前缀认定完整原料条件相同。
        compact.addProperty("alternative_group_semantics", "alternatives_group indexes this recipe's alternative_groups; only displayed alternatives are shared. Per-input counts and truncation remain authoritative for EMI display coverage.");
        if (EmiRecipeKnowledge.encodedBytes(compact) <= budget) return compact;

        JsonObject retained = new JsonObject();
        retained.addProperty("status", "partial"); retained.addProperty("details_complete", false);
        retained.addProperty("issue", "recipe_display_details_exceed_description_budget");
        // 原生规则已另有独立预算；优先保留它和配方编号，展示输入过大也不让普通箱子配方从目录消失。
        for (String key : List.of("display_recipe_id", "id_kind", "backing_recipe", "category", "supports_recipe_tree",
                "outputs", "outputs_count", "outputs_truncated", "inputs_count", "catalysts_count", "workstations_count",
                "recipe_semantics_complete", "display_chance_scope", "widget_conditions", "knowledge_only", "execution_support")) {
            if (!original.has(key)) continue;
            retained.add(key, original.get(key).deepCopy());
            if (EmiRecipeKnowledge.encodedBytes(retained) > budget - 2048) retained.remove(key);
        }
        JsonArray omitted = new JsonArray(); int omittedCount = 0;
        for (String key : original.keySet()) if (!retained.has(key)) {
            omittedCount++; if (omitted.size() < 16 && key.length() <= 64) omitted.add(key);
        }
        retained.add("omitted_fields", omitted);
        retained.addProperty("omitted_field_count", omittedCount);
        retained.addProperty("omitted_fields_truncated", omittedCount > omitted.size());
        return retained;
    }
}
