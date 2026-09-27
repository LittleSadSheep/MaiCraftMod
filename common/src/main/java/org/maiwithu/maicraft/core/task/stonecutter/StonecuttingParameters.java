// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.stonecutter;

import com.google.gson.JsonObject;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;

/** 切石参数只接受输入、目标产物与有限次数；配方按产物在真实菜单里解析，不接收槽号或索引。 */
public record StonecuttingParameters(ResourceLocation input, ResourceLocation output, int count) {
    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 64;

    public static StonecuttingParameters parse(JsonObject parameters) {
        if (parameters == null || !Set.of("item_id", "output_item_id", "count").containsAll(parameters.keySet()))
            throw new IllegalArgumentException("stonecutting process accepts item_id, output_item_id and count only");
        var input = id(parameters, "item_id");
        var output = id(parameters, "output_item_id");
        var count = 1;
        if (parameters.has("count")) {
            try {
                count = parameters.get("count").getAsBigDecimal().intValueExact();
            } catch (NumberFormatException | ArithmeticException invalid) {
                throw new IllegalArgumentException("stonecutting count must be an integer");
            }
        }
        if (count < MIN_COUNT || count > MAX_COUNT)
            throw new IllegalArgumentException("stonecutting count must be " + MIN_COUNT + ".." + MAX_COUNT);
        return new StonecuttingParameters(input, output, count);
    }

    /** 组装 v2 原生工序参数；能力入口与 operate_machine 共用同一份机器工序契约。 */
    public JsonObject executionParameters() {
        var parameters = new JsonObject();
        parameters.addProperty("item_id", input.toString());
        parameters.addProperty("output_item_id", output.toString());
        parameters.addProperty("count", count);
        return parameters;
    }

    private static ResourceLocation id(JsonObject parameters, String field) {
        if (!parameters.has(field)) throw new IllegalArgumentException("stonecutting requires " + field);
        var id = ResourceLocation.tryParse(parameters.get(field).getAsString());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id) || BuiltInRegistries.ITEM.get(id) == Items.AIR)
            throw new IllegalArgumentException("stonecutting " + field + " must name a registered non-air item");
        return id;
    }
}
