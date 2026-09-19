// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.enchant;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

/** 把附魔意图转换成明确的单件、档位和成本限制；不把缺失预算或小数悄悄变成可消费数量。 */
public record EnchantParameters(ResourceLocation itemId, int offerTier, int maxLevelsSpent, int maxLapis, int searchRadius) {
    public static EnchantParameters parse(JsonObject values) {
        var item = values.get("item_id");
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()
                || !item.getAsString().matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
            throw new IllegalArgumentException("enchant requires a namespaced item_id");
        return new EnchantParameters(ResourceLocation.parse(item.getAsString()),
                integer(values,"offer_tier",1,1,3), integer(values,"max_levels_spent",null,0,3),
                integer(values,"max_lapis",null,0,3), integer(values,"search_radius",32,1,64));
    }

    public static int integer(JsonObject values, String key, Integer fallback, int minimum, int maximum) {
        if (!values.has(key) && fallback != null) return fallback;
        var value = values.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(key + " must be an integer from " + minimum + " to " + maximum);
        try {
            int parsed = value.getAsBigDecimal().intValueExact();
            if (parsed < minimum || parsed > maximum) throw new ArithmeticException();
            return parsed;
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException(key + " must be an integer from " + minimum + " to " + maximum);
        }
    }

    // 内部执行只带已经解析的选择与预算；附近搜索由语义目标完成，不把搜索坐标或菜单索引交给LLM。
    public JsonObject executionParameters() {
        JsonObject out = new JsonObject(); out.addProperty("item_id", itemId.toString());
        out.addProperty("offer_tier", offerTier); out.addProperty("max_levels_spent", maxLevelsSpent); out.addProperty("max_lapis", maxLapis);
        return out;
    }
}
