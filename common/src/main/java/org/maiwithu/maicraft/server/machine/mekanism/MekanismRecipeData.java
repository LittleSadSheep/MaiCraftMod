// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mekanism;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** Bounded input alternatives preserve amounts and never turn incomplete predicates into exact recipes. */
public final class MekanismRecipeData {
    private final ServerPlayer player;
    private final JsonObject report;
    private boolean complete = true;
    private int bytes;

    public MekanismRecipeData(ServerPlayer player, JsonObject report) { this.player = player; this.report = report; }
    public boolean complete() { return complete; }
    public void unknown(String reason) { complete = false; report.getAsJsonArray("unknown").add(reason); }

    public void input(Object nativeInput, String medium, String name) {
        String api = "mekanism.api.recipes.ingredients.InputIngredient";
        List<?> candidates = (List<?>) NativeApi.call(nativeInput, api, "getRepresentations");
        JsonObject input = new JsonObject(); JsonArray alternatives = new JsonArray();
        input.addProperty("id", name); input.addProperty("consumed", true); input.add("alternatives", alternatives);
        if (candidates.isEmpty() || candidates.size() > 128) unknown(name + ":ingredient_alternatives_incomplete");
        long amount = 0;
        for (Object candidate : candidates.subList(0, Math.min(128, candidates.size()))) {
            if (!NativeApi.truth(NativeApi.call(nativeInput, api, "test", candidate))) {
                unknown(name + ":display_sample_does_not_match_native_predicate"); continue;
            }
            long needed = NativeApi.number(NativeApi.call(nativeInput, api, "getNeededAmount", candidate));
            if (needed <= 0) { unknown(name + ":nonpositive_input_amount"); continue; }
            if (amount != 0 && amount != needed) unknown(name + ":input_amount_varies_by_alternative");
            amount = Math.max(amount, needed);
            JsonObject resource = MekanismResourceStacks.resource(candidate, player.registryAccess());
            if (!medium.equals(resource.get("medium").getAsString())) { unknown(name + ":ingredient_medium_mismatch"); continue; }
            if (accept(resource)) alternatives.add(resource);
        }
        input.addProperty("amount", amount);
        report.getAsJsonArray("inputs").add(input);
        if (amount <= 0 || alternatives.isEmpty()) unknown(name + ":missing_usable_input_alternative");
        if (medium.equals("items") || medium.equals("fluids")) {
            String ingredientApi = "mekanism.api.recipes.ingredients." + (medium.equals("items") ? "ItemStackIngredient" : "FluidStackIngredient");
            Object sized = NativeApi.call(nativeInput, ingredientApi, "ingredient");
            String sizedApi = medium.equals("items") ? "net.neoforged.neoforge.common.crafting.SizedIngredient"
                    : "net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient";
            Object ingredient = NativeApi.call(sized, sizedApi, "ingredient");
            String predicateApi = medium.equals("items") ? "net.minecraft.world.item.crafting.Ingredient"
                    : "net.neoforged.neoforge.fluids.crafting.FluidIngredient";
            if (!NativeApi.truth(NativeApi.call(ingredient, predicateApi, "isSimple"))) {
                unknown(name + ":custom_component_predicate_not_exhaustively_normalized");
            }
        }
    }

    public void fixedOutputs(Object recipe, String api, String method) {
        List<?> definitions = (List<?>) NativeApi.call(recipe, api, method);
        if (definitions.size() != 1) { unknown("context_dependent_output_definition"); return; }
        Object output = definitions.getFirst();
        if (NativeApi.is(output, "mekanism.api.recipes.ElectrolysisRecipe$ElectrolysisRecipeOutput")) {
            output(NativeApi.call(output, "mekanism.api.recipes.ElectrolysisRecipe$ElectrolysisRecipeOutput", "left"));
            output(NativeApi.call(output, "mekanism.api.recipes.ElectrolysisRecipe$ElectrolysisRecipeOutput", "right"));
        } else output(output);
    }

    private void output(Object stack) {
        long amount = MekanismResourceStacks.amount(stack);
        if (amount <= 0) { unknown("nonpositive_output_amount"); return; }
        JsonObject value = new JsonObject(); value.add("resource", MekanismResourceStacks.resource(stack, player.registryAccess()));
        value.addProperty("amount", amount); value.addProperty("chance", 1.0); value.addProperty("guaranteed", true);
        if (accept(value)) report.getAsJsonArray("outputs").add(value);
    }

    private boolean accept(JsonObject value) {
        int size = value.toString().length();
        if (bytes + size > 36_000) { unknown("recipe_resource_evidence_budget_exceeded"); return false; }
        bytes += size; return true;
    }
}
