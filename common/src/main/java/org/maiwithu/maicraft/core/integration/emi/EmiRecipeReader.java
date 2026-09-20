// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.emi;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

/** 读取当前页展示配方的关系；输入、催化剂与工作站分开，避免把展示用途误当可消费材料清单。 */
final class EmiRecipeReader {
    static final int MAX_ENTRIES = 64, MAX_ALTERNATIVES = 32;
    private final EmiPublicApi api;
    private final Object manager;
    private final EmiStackReader stacks;
    private boolean complete = true;

    EmiRecipeReader(EmiPublicApi api, Object manager, HolderLookup.Provider registries) {
        this.api = api; this.manager = manager; stacks = new EmiStackReader(api, registries);
    }

    JsonObject read(Object recipe) {
        JsonObject out = new JsonObject(); Object id = api.call(api.recipe(), recipe, "getId");
        if (id == null) out.add("display_recipe_id", JsonNull.INSTANCE);
        else out.addProperty("display_recipe_id", id.toString());
        out.addProperty("id_kind", id == null ? "null" : id instanceof ResourceLocation location && location.getPath().startsWith("/") ? "synthetic" : "named");
        // 特殊展示可能只是染色、信息或任意变体；EMI不支持树计算时明确保留，不能按其列表递归推导消耗量。
        out.addProperty("supports_recipe_tree", Boolean.TRUE.equals(api.call(api.recipe(), recipe, "supportsRecipeTree")));
        out.add("backing_recipe", backing(recipe));
        Object category = api.call(api.recipe(), recipe, "getCategory");
        JsonObject categoryData = new JsonObject();
        categoryData.addProperty("id", api.call(api.category(), category, "getId").toString());
        Object name = api.call(api.category(), category, "getName");
        categoryData.addProperty("name", name instanceof Component text ? text.getString() : ""); out.add("category", categoryData);
        ingredients(out, "inputs", api.list(api.recipe(), recipe, "getInputs"));
        ingredients(out, "catalysts", api.list(api.recipe(), recipe, "getCatalysts"));
        Object workstations = api.call(api.manager(), manager, "getWorkstations", new Class<?>[]{api.category()}, category);
        if (!(workstations instanceof List<?> values)) throw new IllegalStateException("EMI workstations are not a list");
        ingredients(out, "workstations", values);
        outputs(out, api.list(api.recipe(), recipe, "getOutputs"));
        out.addProperty("details_complete", complete && stacks.complete());
        out.addProperty("widget_conditions", "not_read"); out.addProperty("knowledge_only", true);
        out.addProperty("execution_support", "not_inferred_from_emi"); return out;
    }

    private JsonObject backing(Object recipe) {
        JsonObject out = new JsonObject();
        try {
            Object nativeRecipe = api.call(api.recipe(), recipe, "getBackingRecipe");
            if (nativeRecipe == null) { out.addProperty("status", "none"); return out; }
            if (!(nativeRecipe instanceof RecipeHolder<?> holder)) throw new IllegalStateException("unknown backing recipe type");
            out.addProperty("status", "present"); out.addProperty("id", holder.id().toString());
            ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
            ResourceLocation serializer = BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer());
            if (type == null) { out.add("type", JsonNull.INSTANCE); complete = false; } else out.addProperty("type", type.toString());
            if (serializer == null) { out.add("serializer", JsonNull.INSTANCE); complete = false; } else out.addProperty("serializer", serializer.toString());
        } catch (RuntimeException | LinkageError unreadable) {
            out = new JsonObject(); out.addProperty("status", "unreadable"); complete = false;
        }
        return out;
    }

    private void ingredients(JsonObject out, String key, List<?> ingredients) {
        JsonArray result = new JsonArray();
        for (int i = 0; i < Math.min(ingredients.size(), MAX_ENTRIES); i++) {
            Object ingredient = ingredients.get(i); JsonObject row = new JsonObject(); row.addProperty("index", i);
            row.addProperty("amount", ((Number) api.call(api.ingredient(), ingredient, "getAmount")).longValue());
            float chance = ((Number) api.call(api.ingredient(), ingredient, "getChance")).floatValue();
            if (!Float.isFinite(chance)) throw new IllegalStateException("non-finite EMI ingredient chance");
            row.addProperty("chance", chance); List<?> alternatives = api.list(api.ingredient(), ingredient, "getEmiStacks");
            JsonArray options = new JsonArray();
            for (int a = 0; a < Math.min(alternatives.size(), MAX_ALTERNATIVES) && stacks.capacityAvailable(); a++) options.add(stacks.read(alternatives.get(a)));
            row.add("alternatives", options); row.addProperty("alternatives_count", alternatives.size());
            row.addProperty("alternatives_truncated", alternatives.size() > options.size());
            complete &= alternatives.size() == options.size(); result.add(row);
        }
        out.add(key, result); out.addProperty(key + "_count", ingredients.size());
        out.addProperty(key + "_truncated", ingredients.size() > result.size()); complete &= ingredients.size() == result.size();
    }

    private void outputs(JsonObject out, List<?> outputs) {
        JsonArray result = new JsonArray();
        for (int i = 0; i < Math.min(outputs.size(), MAX_ENTRIES) && stacks.capacityAvailable(); i++) result.add(stacks.read(outputs.get(i)));
        out.add("outputs", result); out.addProperty("outputs_count", outputs.size());
        out.addProperty("outputs_truncated", outputs.size() > result.size()); complete &= outputs.size() == result.size();
    }
}
