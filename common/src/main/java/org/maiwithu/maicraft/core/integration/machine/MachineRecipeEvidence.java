// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.tools.RecipeProbe;

/**
 * 从当前客户端配方表找“展示产物是指定物品”的配方，并列出能读到的普通物品原料。
 * 最多检查四千零九十六条，输出十六条；模组的流体、化学品、动态产物和机器条件可能不在这些通用字段里。
 */
public final class MachineRecipeEvidence {
    public static final int MAX_EXAMINED_RECIPES = 4096;
    public static final int MAX_EMITTED_RECIPES = 16;
    public static final int MAX_INGREDIENTS = 16;
    public static final int MAX_ALTERNATIVES = 16;
    private static final int MAX_IDENTIFIER_LENGTH = 256;

    private MachineRecipeEvidence() {}

    /**
     * 在角色有效的客户端更新中只读查询。找不到展示产物不能断言没有配方，报告会保留扫描范围及未读全的原因。
     */
    public static JsonObject inspect(LocalPlayer player, String expectedOutputId) {
        JsonObject report = base();
        JsonArray matches = report.getAsJsonArray("recipes");
        if (expectedOutputId == null || expectedOutputId.length() > MAX_IDENTIFIER_LENGTH) {
            report.addProperty("status", "invalid_output_id");
            report.addProperty("issue", "An installed namespaced output item ID is required.");
            return report;
        }
        ResourceLocation outputId = ResourceLocation.tryParse(expectedOutputId);
        if (outputId == null || !expectedOutputId.contains(":")) {
            report.addProperty("status", "invalid_output_id");
            report.addProperty("issue", "An installed namespaced output item ID is required.");
            return report;
        }
        report.addProperty("expected_output", outputId.toString());
        int examined = 0;
        int matched = 0;
        int unreadable = 0;
        int withoutStaticResult = 0;
        boolean exhausted = false;
        boolean detailsTruncated = false;
        try {
            var context = ClientRuntime.requireContext(player);
            if (!BuiltInRegistries.ITEM.containsKey(outputId)) {
                report.addProperty("status", "invalid_output_id");
                report.addProperty("issue", "The requested output item is not in the installed item registry.");
                return report;
            }
            var target = BuiltInRegistries.ITEM.get(outputId);
            Iterator<RecipeHolder<?>> recipes = context.connection().getRecipeManager().getRecipes().iterator();
            report.addProperty("available", true);
            while (examined < MAX_EXAMINED_RECIPES && recipes.hasNext()) {
                RecipeHolder<?> holder = recipes.next();
                examined++;
                try {
                    Recipe<?> recipe = holder.value();
                    ItemStack output = RecipeProbe.resultOf(recipe, context.level().registryAccess());
                    if (output.isEmpty()) {
                        // RecipeProbe also returns EMPTY on unsupported/null/throwing display getters.
                        // Never interpret this as a recipe that cannot produce any output.
                        withoutStaticResult++;
                        continue;
                    }
                    if (output.getItem() != target) continue;
                    matched++;
                    if (matches.size() >= MAX_EMITTED_RECIPES) continue;
                    JsonObject evidence = describe(holder, recipe, outputId, output.getCount());
                    matches.add(evidence);
                    detailsTruncated |= evidence.get("details_truncated").getAsBoolean();
                } catch (RuntimeException | LinkageError unavailable) {
                    unreadable++;
                }
            }
            exhausted = !recipes.hasNext();
            report.addProperty("status", matches.isEmpty()
                    ? "no_matching_display_result_observed" : "matching_item_recipe_evidence");
        } catch (RuntimeException | LinkageError unavailable) {
            report.addProperty("status", "recipe_evidence_unavailable_or_interrupted");
            report.addProperty("issue", "The active client recipe manager or its traversal was unavailable; any already collected entries are partial evidence.");
        }
        report.addProperty("examined_recipe_count", examined);
        report.addProperty("matching_display_result_count", matched);
        report.addProperty("emitted_recipe_count", matches.size());
        report.addProperty("unreadable_recipe_count", unreadable);
        report.addProperty("without_static_item_result_count", withoutStaticResult);
        report.addProperty("scan_complete", exhausted);
        report.addProperty("scan_truncated", !exhausted && examined >= MAX_EXAMINED_RECIPES);
        report.addProperty("matching_results_truncated", matched > matches.size());
        report.addProperty("recipe_details_truncated", detailsTruncated);
        report.addProperty("truncated", (!exhausted && examined >= MAX_EXAMINED_RECIPES)
                || matched > matches.size() || detailsTruncated);
        return report;
    }

    // 每个配方最多列十六种原料，每种最多列十六个可替代物品样本；样本列表不是完整配方格、数量或附加组件要求。
    private static JsonObject describe(RecipeHolder<?> holder, Recipe<?> recipe, ResourceLocation output, int count) {
        JsonObject row = new JsonObject();
        JsonArray unknowns = new JsonArray();
        row.addProperty("source", "client_recipe_manager");
        row.addProperty("result_evidence", "generic_static_display_result");
        row.addProperty("recipe_semantics_complete", false);
        boolean truncated = putIdentifier(row, "recipe_id", holder.id(), unknowns);
        try {
            truncated |= putIdentifier(row, "type_id", BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()), unknowns);
        } catch (RuntimeException | LinkageError unavailable) {
            unknowns.add("recipe_type_unavailable");
        }
        try {
            truncated |= putIdentifier(row, "serializer_id", BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()), unknowns);
        } catch (RuntimeException | LinkageError unavailable) {
            unknowns.add("recipe_serializer_unavailable");
        }
        JsonObject result = new JsonObject();
        result.addProperty("item_id", output.toString());
        result.addProperty("count", count);
        result.addProperty("data_components_included", false);
        row.add("result", result);
        JsonArray ingredients = new JsonArray();
        row.add("ingredients", ingredients);
        try {
            List<Ingredient> inputs = recipe.getIngredients();
            if (inputs == null) {
                unknowns.add("generic_item_ingredients_unavailable");
            } else {
                row.addProperty("observed_ingredient_count", inputs.size());
                truncated |= inputs.size() > MAX_INGREDIENTS;
                for (int index = 0; index < Math.min(inputs.size(), MAX_INGREDIENTS); index++) {
                    JsonObject ingredient = new JsonObject();
                    ingredient.addProperty("ingredient_index", index);
                    JsonArray alternatives = new JsonArray();
                    ingredient.add("item_ids", alternatives);
                    ingredient.addProperty("generic_item_samples_complete", false);
                    try {
                        Ingredient input = inputs.get(index);
                        ItemStack[] samples = input == null ? null : input.getItems();
                        if (samples == null) {
                            ingredient.addProperty("unknown", "generic_item_alternatives_unavailable");
                        } else {
                            ingredient.addProperty("observed_alternative_stack_count", samples.length);
                            boolean alternativesTruncated = samples.length > MAX_ALTERNATIVES;
                            boolean unreadableAlternative = false;
                            Set<String> ids = new LinkedHashSet<>();
                            for (int alternative = 0; alternative < Math.min(samples.length, MAX_ALTERNATIVES); alternative++) {
                                ItemStack sample = samples[alternative];
                                if (sample == null || sample.isEmpty()) {
                                    unreadableAlternative = true;
                                    continue;
                                }
                                ResourceLocation id = BuiltInRegistries.ITEM.getKey(sample.getItem());
                                if (id == null || id.toString().length() > MAX_IDENTIFIER_LENGTH) {
                                    unreadableAlternative = true;
                                    alternativesTruncated |= id != null;
                                } else ids.add(id.toString());
                            }
                            ids.forEach(alternatives::add);
                            ingredient.addProperty("alternatives_truncated", alternativesTruncated);
                            ingredient.addProperty("generic_item_samples_complete", !alternativesTruncated && !unreadableAlternative);
                            if (samples.length == 0) ingredient.addProperty("empty_or_custom_ingredient", true);
                            if (unreadableAlternative) ingredient.addProperty("unknown", "one_or_more_alternative_items_unreadable");
                            truncated |= alternativesTruncated;
                        }
                    } catch (RuntimeException | LinkageError unavailable) {
                        ingredient.addProperty("unknown", "generic_item_alternatives_unavailable");
                    }
                    ingredients.add(ingredient);
                }
            }
        } catch (RuntimeException | LinkageError unavailable) {
            unknowns.add("generic_item_ingredients_unavailable_or_partial");
        }
        unknowns.add("fluid_chemical_heat_and_other_custom_ingredients");
        unknowns.add("custom_ingredient_counts_and_item_data_components");
        unknowns.add("dynamic_outputs_byproducts_chance_and_machine_conditions");
        row.add("unknowns", unknowns);
        row.addProperty("details_truncated", truncated);
        return row;
    }

    /** Returns whether a genuine identifier had to be omitted due to its bounded display length. */
    private static boolean putIdentifier(JsonObject row, String key, ResourceLocation id, JsonArray unknowns) {
        if (id == null) {
            unknowns.add(key + "_unavailable");
            return false;
        }
        String text = id.toString();
        if (text.length() > MAX_IDENTIFIER_LENGTH) {
            unknowns.add(key + "_exceeds_display_limit");
            return true;
        }
        row.addProperty(key, text);
        return false;
    }

    // 报告始终保留“配方语义未完整核实”和“机器可运行未验证”，避免把展示用信息变成可执行配方保证。
    private static JsonObject base() {
        JsonObject report = new JsonObject();
        report.addProperty("source", "client_recipe_manager");
        report.addProperty("read_only", true);
        report.addProperty("available", false);
        report.addProperty("scan_complete", false);
        report.addProperty("scan_truncated", false);
        report.addProperty("matching_results_truncated", false);
        report.addProperty("recipe_details_truncated", false);
        report.addProperty("truncated", false);
        report.addProperty("recipe_semantics_complete", false);
        report.addProperty("incomplete", true);
        report.addProperty("absence_proven", false);
        report.addProperty("machine_operability_verified", false);
        report.addProperty("examined_recipe_count", 0);
        report.addProperty("matching_display_result_count", 0);
        report.addProperty("emitted_recipe_count", 0);
        report.addProperty("unreadable_recipe_count", 0);
        report.addProperty("without_static_item_result_count", 0);
        report.addProperty("max_examined_recipes", MAX_EXAMINED_RECIPES);
        report.addProperty("max_emitted_recipes", MAX_EMITTED_RECIPES);
        report.addProperty("max_ingredients_per_recipe", MAX_INGREDIENTS);
        report.addProperty("max_item_alternatives_per_ingredient", MAX_ALTERNATIVES);
        report.addProperty("scan_scope", "scan_complete describes traversal of this client's recipe manager only, including recipes whose generic display outputs may be unavailable.");
        report.addProperty("ingredient_scope", "Each ingredient lists bounded sample item identities; this is not a verified crafting grid, quantity bill, custom predicate or complete processing input model.");
        report.addProperty("interpretation", "No matching display result does not prove that no recipe exists. Dynamic, chemical/fluid, mod-specific or server-only recipes may require specialized evidence; every reported recipe still needs machine and operating-condition verification.");
        report.add("recipes", new JsonArray());
        return report;
    }
}
