// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.task.TaskRecord;

/** 为整个原生流体转化配方族提供机器过程入口；物品、数量、条件均来自安装配方，没有按产物命名的特殊分支。 */
public final class WorldTransformProcessAdapter implements NativeProcessAdapter {
    @Override public String id() { return "ae2:transform"; }
    @Override public boolean available() { return NativeTransformRecipes.available(); }
    @Override public JsonObject contract() {
        JsonObject out = new JsonObject(); out.addProperty("process", id());
        out.addProperty("summary", "Run finite installed fluid-transformation batches using a bounded existing receiving area, exact carried inputs and attributed output collection.");
        JsonObject parameters = new JsonObject();
        parameters.addProperty("recipe_id", "Required synchronized recipe ID from this site's recipe observation; no item IDs or ingredient quantities are hardcoded by the executor.");
        parameters.addProperty("batches", "Integer 1..64, default 1. Each batch is supplied and collected before the next; all inputs and inventory capacity are checked before consumption.");
        out.add("parameters", parameters);
        out.addProperty("evidence", "Optional native events verify the exact recipe; client-only mode reports observed inputs and attributed output collection without claiming a native recipe event.");
        return out;
    }
    @Override public void validate(JsonObject parameters) {
        for (String key : parameters.keySet()) if (!Set.of("recipe_id", "batches").contains(key))
            throw new IllegalArgumentException("unexpected_world_process_parameter: " + key);
        recipeId(parameters); batches(parameters);
    }
    @Override public boolean matches(LocalPlayer player, BlockPos position) {
        if (!available() || player == null || !player.level().isLoaded(position)) return false;
        var fluid = player.level().getFluidState(position);
        return !fluid.isEmpty() && NativeTransformRecipes.recipes(player).stream().anyMatch(recipe -> recipe.supports(fluid));
    }
    @Override public JsonObject inspect(LocalPlayer player, BlockPos position) {
        JsonObject out = new JsonObject(); JsonArray recipes = new JsonArray();
        var matched = NativeTransformRecipes.recipes(player).stream().filter(recipe -> recipe.supports(player.level().getFluidState(position))).toList();
        // 完整配方只在查看当前加工环境时返回，默认能力说明不随整合包的配方数量增长。
        for (var recipe : matched) { if (recipes.size() >= 32) break; recipes.add(recipe.describe()); }
        out.add("recipes", recipes); out.addProperty("recipe_count", matched.size()); out.addProperty("truncated", matched.size() > recipes.size());
        out.addProperty("native_event_support", WorldProcessEvents.available()); out.addProperty("observation_only", true); return out;
    }
    @Override public TaskRecord createTask(String callId, long deadline, LocalPlayer player, BlockPos position, JsonObject parameters) {
        validate(parameters); var recipe = NativeTransformRecipes.find(player, recipeId(parameters));
        if (!recipe.supports(player.level().getFluidState(position))) throw new IllegalArgumentException("world_process_recipe_environment_mismatch");
        return new WorldTransformTaskRecord(callId, deadline, recipe.id(), position, batches(parameters));
    }
    private static ResourceLocation recipeId(JsonObject parameters) {
        var id = parameters.get("recipe_id");
        if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()
                || !id.getAsString().contains(":") || ResourceLocation.tryParse(id.getAsString()) == null)
            throw new IllegalArgumentException("world_process_requires_namespaced_recipe_id");
        return ResourceLocation.parse(id.getAsString());
    }
    private static int batches(JsonObject parameters) {
        // 原生配方的批数必须是明确整数，不能截断小数或把零解释成无限投料。
        if (!parameters.has("batches")) return 1;
        var value = parameters.get("batches");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("world_process_batches_requires_integer");
        try {
            int count = value.getAsBigDecimal().intValueExact();
            if (count < 1 || count > 64) throw new ArithmeticException();
            return count;
        } catch (ArithmeticException | NumberFormatException invalid) { throw new IllegalArgumentException("world_process_batches_requires_1_to_64"); }
    }
}
