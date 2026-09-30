// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.function.Function;

/** 配方大页先展示所有候选的工艺与原生定义，避免模型为排除标签页逐层翻阅同一份知识。 */
final class RecipeKnowledgeView {
    private RecipeKnowledgeView() {}

    static JsonObject present(JsonElement value, Function<String, String> uri) {
        if (!value.isJsonObject()) return null;
        JsonObject source = value.getAsJsonObject();
        String path = "";
        // 知识正文经传输层包成 json 时，链接仍指向归档中的真实位置，不重新查询配方或采样游戏。
        if (source.has("json") && source.get("json").isJsonObject()) {
            source = source.getAsJsonObject("json"); path = "/json";
        }
        if (!source.has("display_recipes") || !source.get("display_recipes").isJsonArray()) return null;
        JsonArray recipes = source.getAsJsonArray("display_recipes"), choices = new JsonArray();
        String recipesPath = JsonReadback.childPath(path, "display_recipes");
        for (int index = 0; index < recipes.size(); index++) {
            if (!recipes.get(index).isJsonObject()) continue;
            JsonObject recipe = recipes.get(index).getAsJsonObject(), row = new JsonObject();
            String rowPath = JsonReadback.childPath(recipesPath, Integer.toString(index));
            row.addProperty("index", index);
            for (String key : List.of("display_recipe_id", "category"))
                if (recipe.has(key)) row.add(key, JsonReadback.preview(recipe.get(key), JsonReadback.childPath(rowPath, key), 200, uri));
            if (recipe.has("backing_recipe") && recipe.get("backing_recipe").isJsonObject()) {
                JsonObject backing = recipe.getAsJsonObject("backing_recipe"), nativeRecipe = new JsonObject();
                String nativePath = JsonReadback.childPath(rowPath, "backing_recipe");
                // 只转录原生序列化结果，不替模型挑选配方、推断执行能力或省略失败概率与循环条件。
                for (String key : List.of("status", "id", "type", "definition_status", "recipe_semantics_complete", "definition"))
                    if (backing.has(key)) nativeRecipe.add(key, JsonReadback.preview(backing.get(key),
                            JsonReadback.childPath(nativePath, key), key.equals("definition") ? 1000 : 180, uri));
                row.add("native_recipe", nativeRecipe);
            }
            choices.add(row);
            if (!JsonReadback.fits(choices, 4200)) { choices.remove(choices.size() - 1); break; }
        }
        JsonObject result = new JsonObject();
        result.addProperty("knowledge_only", true);
        result.addProperty("execution_support", "not_inferred_from_recipe_visibility");
        result.addProperty("query_page_recipe_count", recipes.size());
        result.add("choices", choices);
        result.addProperty("choices_omitted", recipes.size() - choices.size());
        // 候选过多时保留整页直达入口；摘要没有展示的配方仍然未知，不能被当成不存在。
        result.addProperty("all_recipes_uri", uri.apply(recipesPath));
        return result;
    }
}
