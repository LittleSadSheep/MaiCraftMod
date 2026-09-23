// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.emi;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.crafting.Recipe;

/** 用安装版本的原生序列化器读取配方定义；概率、时间及模组条件由原配方保留，不按产物或模组名重编规则。 */
public final class NativeRecipeDefinition {
    static final int MAX_BYTES = 32_768, MAX_NODES = 2048, MAX_DEPTH = 16;
    private NativeRecipeDefinition() {}

    public static JsonObject read(Recipe<?> recipe, HolderLookup.Provider registries) {
        JsonObject out = new JsonObject(); out.addProperty("provenance", "installed_native_recipe_serializer");
        // 编码完整只证明读到了序列化字段；动态条件、实际加工能力和产出仍须现场验证。
        out.addProperty("recipe_semantics_complete", false);
        try {
            JsonElement definition = Recipe.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), recipe).getOrThrow();
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
