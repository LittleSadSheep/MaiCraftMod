// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.emi;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;

/** 用安装版本的原生序列化器读取配方定义；概率、时间及模组条件由原配方保留，不按产物或模组名重编规则。 */
public final class NativeRecipeDefinition {
    static final int MAX_BYTES = 32_768, MAX_NODES = 2048, MAX_DEPTH = 16;
    private NativeRecipeDefinition() {}

    public static JsonObject read(Recipe<?> recipe, HolderLookup.Provider registries) {
        JsonObject out = new JsonObject(); out.addProperty("provenance", "installed_native_recipe_serializer");
        // 编码完整只证明读到了序列化字段；动态条件、实际加工能力和产出仍须现场验证。
        out.addProperty("recipe_semantics_complete", false);
        try {
            JsonElement definition = encode(recipe, registries, out);
            if (!withinBudget(definition)) {
                out.addProperty("definition_status", "unknown"); out.addProperty("definition_issue", "native_definition_exceeds_budget");
            } else {
                out.addProperty("definition_status", "available"); out.add("definition", definition);
            }
        } catch (RuntimeException | LinkageError unavailable) {
            out.addProperty("definition_status", "unknown"); out.addProperty("definition_issue", "native_definition_not_encodable");
        }
        return out;
    }

    private static JsonElement encode(Recipe<?> recipe, HolderLookup.Provider registries, JsonObject evidence) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        var encoded = Recipe.CODEC.encodeStart(ops, recipe);
        if (encoded.result().isPresent()) return encoded.result().orElseThrow();
        // 原版有序配方的网络包保留宽高与逐格原料，却丢弃符号表；只为原版具体类恢复符号，不猜模组子类的条件。
        if (recipe.getClass() != ShapedRecipe.class) return encoded.getOrThrow();
        var shaped = (ShapedRecipe) recipe;
        int width = shaped.getWidth(), height = shaped.getHeight();
        if (width < 1 || height < 1 || width > 3 || height > 3 || shaped.getIngredients().size() != width * height)
            return encoded.getOrThrow();
        var key = new LinkedHashMap<Character, Ingredient>(); var rows = new ArrayList<String>();
        for (int y = 0; y < height; y++) {
            var row = new StringBuilder();
            for (int x = 0; x < width; x++) {
                int index = y * width + x; var ingredient = shaped.getIngredients().get(index);
                char symbol = ingredient.isEmpty() ? ' ' : (char) ('A' + index);
                row.append(symbol); if (symbol != ' ') key.put(symbol, ingredient);
            }
            rows.add(row.toString());
        }
        var pattern = ShapedRecipePattern.of(key, rows);
        if (pattern.width() != width || pattern.height() != height) return encoded.getOrThrow();
        // 产物组件、配方组、类别及通知标志仍取同步对象；恢复的只是等价格子映射，最终仍交给安装版本的序列化器。
        var copy = new ShapedRecipe(shaped.getGroup(), shaped.category(), pattern,
                shaped.getResultItem(registries).copy(), shaped.showNotification());
        var definition = Recipe.CODEC.encodeStart(ops, copy).getOrThrow();
        evidence.addProperty("provenance", "synchronized_vanilla_shaped_recipe");
        evidence.addProperty("symbol_mapping", "reconstructed_from_synchronized_grid");
        return definition;
    }

    static boolean withinBudget(JsonElement definition) {
        if (definition == null || !definition.isJsonObject()) return false;
        record Node(JsonElement value, int depth) {}
        ArrayDeque<Node> pending = new ArrayDeque<>(); pending.add(new Node(definition, 0));
        int nodes = 1, text = 0;
        while (!pending.isEmpty()) {
            Node node = pending.removeLast(); JsonElement value = node.value();
            if (node.depth() > MAX_DEPTH) return false;
            // 入队前先限制整个结构，避免巨型数组或深层嵌套仅在输出截断后才被发现。
            if (value.isJsonObject()) {
                if (value.getAsJsonObject().size() > MAX_NODES - nodes) return false;
                nodes += value.getAsJsonObject().size();
                for (var entry : value.getAsJsonObject().entrySet()) {
                    if (entry.getKey().length() > MAX_BYTES - text) return false;
                    text += entry.getKey().length(); pending.add(new Node(entry.getValue(), node.depth() + 1));
                }
            } else if (value.isJsonArray()) {
                if (value.getAsJsonArray().size() > MAX_NODES - nodes) return false;
                nodes += value.getAsJsonArray().size();
                for (JsonElement child : value.getAsJsonArray()) pending.add(new Node(child, node.depth() + 1));
            } else if (value.isJsonPrimitive()) {
                String literal = value.getAsString();
                if (literal.length() > MAX_BYTES - text) return false;
                text += literal.length();
                if (value.getAsJsonPrimitive().isNumber()) {
                    try { value.getAsBigDecimal(); } catch (NumberFormatException invalidNumber) { return false; }
                }
            }
        }
        // 最后检查真实UTF-8大小，包括JSON转义和键名；不截半份定义后宣称所有概率字段已读全。
        return definition.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
