// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;

/** 物品共享相同原生说明时只在本次回执写一份，按具体配方和角色提供索引，保留全部适用关系。 */
final class KnowledgePitfalls {
    private KnowledgePitfalls() {}

    static void present(JsonObject report, JsonArray items) {
        JsonArray notes = new JsonArray(), pitfalls = new JsonArray(), queried = new JsonArray();
        Map<JsonElement, Integer> known = new LinkedHashMap<>();
        Map<String, JsonObject> recipes = new LinkedHashMap<>();
        for (JsonElement raw : items) {
            JsonObject item = raw.getAsJsonObject().deepCopy(); JsonArray indices = new JsonArray();
            for (JsonElement note : item.getAsJsonArray("notes")) {
                // 来源、版本、条件或读取地址有任一差异就保留独立说明，不能把相似文字合并成同一机制。
                Integer index = known.get(note);
                if (index == null) { index = notes.size(); notes.add(note); known.put(note, index); }
                if (!indices.asList().contains(new JsonPrimitive(index))) indices.add(index);
            }
            item.remove("notes"); item.add("note_indices", indices);
            int itemIndex = pitfalls.size();
            if (item.has("roles") && item.getAsJsonArray("roles").asList().stream()
                    .anyMatch(role -> role.getAsString().equals("queried_item"))) queried.add(itemIndex);
            JsonElement relationships = item.remove("recipe_roles");
            if (relationships != null) for (var relationship : relationships.getAsJsonObject().entrySet()) {
                JsonObject recipe = recipes.computeIfAbsent(relationship.getKey(), key -> {
                    JsonObject row = new JsonObject(); row.addProperty("recipe_index", Integer.parseInt(key));
                    row.add("roles", new JsonObject()); return row;
                });
                for (var role : relationship.getValue().getAsJsonArray()) {
                    JsonObject roles = recipe.getAsJsonObject("roles"); String name = role.getAsString();
                    if (!roles.has(name)) roles.add(name, new JsonArray());
                    roles.getAsJsonArray(name).add(itemIndex);
                }
            }
            pitfalls.add(item);
        }
        JsonObject index = new JsonObject(); JsonArray byRecipe = new JsonArray(); recipes.values().forEach(byRecipe::add);
        index.add("queried_items", queried); index.add("recipes", byRecipe);
        report.addProperty("pitfalls_schema_version", 2);
        report.add("pitfall_notes", notes); report.add("pitfalls", pitfalls); report.add("pitfall_index", index);
        report.addProperty("pitfalls_reference_scope", "note_indices index pitfall_notes; pitfall_index contains indices into pitfalls. "
                + "Every definition and item is present in this same response; no earlier response or extra read is required.");
    }
}
