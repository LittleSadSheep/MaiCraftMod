// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.create;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** Complete ordinary Create 6.0.10 milling/crushing recipes; native calls, never recipe-name guesses. */
public final class CreateGrindingRecipes {
    private static final String PROCESSING = "com.simibubi.create.content.processing.recipe.ProcessingRecipe";
    private CreateGrindingRecipes() {}

    public static boolean supports(BlockEntity entity) {
        return NativeApi.is(entity, CreateGrindingRecipeAccess.MILL) || NativeApi.is(entity, CreateGrindingRecipeAccess.CRUSH);
    }

    public static JsonObject inspect(ServerPlayer player, BlockEntity entity, RecipeHolder<?> holder, JsonObject body) {
        JsonObject result = new JsonObject();
        result.addProperty("schema", "maicraft.machine_recipe.v1");
        result.addProperty("id", holder.id().toString()); result.addProperty("recipe_id", holder.id().toString());
        result.addProperty("type", BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()).toString());
        result.addProperty("tick", player.serverLevel().getGameTime()); result.add("position", body.get("position").deepCopy());
        result.addProperty("dimension", player.serverLevel().dimension().location().toString());
        result.addProperty("provenance", "server_recipe_manager_and_native_grinding_predicates");
        result.addProperty("production_verified", false);
        JsonArray inputs = new JsonArray(), outputs = new JsonArray(), unknown = new JsonArray();
        result.add("inputs", inputs); result.add("outputs", outputs); result.add("unknown", unknown);
        boolean crusher = NativeApi.is(entity, CreateGrindingRecipeAccess.CRUSH);
        boolean compatible = false;
        try {
            String recipeClass = holder.value().getClass().getName();
            if (!recipeClass.equals(CreateGrindingRecipeAccess.MILLING)
                    && !(crusher && recipeClass.equals(CreateGrindingRecipeAccess.CRUSHING))) {
                unknown.add("grinding_recipe_type_or_custom_conditions_not_decoded");
            } else {
                compatible = inputs(player, holder, crusher, inputs, outputs, unknown);
                outputs(player, holder.value(), outputs, unknown);
                result.addProperty("processing_duration", NativeApi.number(NativeApi.call(holder.value(), PROCESSING, "getProcessingDuration")));
                result.addProperty("duration_semantics", "native_work_units; elapsed_ticks_depend_on_actual_speed_and_batch_size");
                result.addProperty("batch_semantics", crusher ? "one_completion_per_admitted_stack; one_random_roll_per_consumed_item" : "one_consumed_item_per_completion");
                if (!((List<?>) NativeApi.call(holder.value(), PROCESSING, "getFluidIngredients")).isEmpty()
                        || !((List<?>) NativeApi.call(holder.value(), PROCESSING, "getFluidResults")).isEmpty()
                        || !"NONE".equals(String.valueOf(NativeApi.call(holder.value(), PROCESSING, "getRequiredHeat"))))
                    unknown.add("grinding_recipe_has_unmodeled_fluid_or_heat_fields");
            }
        } catch (RuntimeException | LinkageError unavailable) { unknown.add("native_grinding_recipe_api_unavailable"); }
        CreateGrindingConditions.describe(player, entity, crusher, result);
        result.addProperty("compatible", compatible);
        result.addProperty("compatibility", compatible ? "verified" : unknown.isEmpty() ? "disabled" : "unknown");
        result.addProperty("complete", unknown.isEmpty() && !inputs.isEmpty() && !outputs.isEmpty());
        return result;
    }

    private static boolean inputs(ServerPlayer player, RecipeHolder<?> holder, boolean crusher,
                                  JsonArray inputs, JsonArray outputs, JsonArray unknown) {
        if (holder.value().getIngredients().size() != 1) { unknown.add("grinding_requires_one_native_item_ingredient"); return false; }
        Ingredient ingredient = holder.value().getIngredients().getFirst();
        if (NativeApi.present("net.neoforged.neoforge.common.crafting.ICustomIngredient")
                && !NativeApi.truth(NativeApi.call(ingredient, "net.minecraft.world.item.crafting.Ingredient", "isSimple")))
            unknown.add("custom_ingredient_predicate_not_exhaustively_enumerable");
        ItemStack[] samples = ingredient.getItems();
        if (samples.length == 0 || samples.length > 128) unknown.add("ingredient_alternatives_incomplete");
        JsonObject input = new JsonObject(); JsonArray alternatives = new JsonArray();
        input.addProperty("id", "input_1"); input.addProperty("amount", 1); input.addProperty("consumed", true);
        input.add("alternatives", alternatives); inputs.add(input);
        boolean compatible = samples.length > 0;
        ItemStack remainder = null;
        for (int i = 0; i < Math.min(samples.length, 128); i++) {
            ItemStack sample = samples[i];
            if (sample.isEmpty()) { compatible = false; unknown.add("empty_ingredient_alternative"); continue; }
            alternatives.add(resource(sample, player));
            var selected = CreateGrindingRecipeAccess.select(player.serverLevel(), crusher, sample);
            if (!selected.complete()) { compatible = false; unknown.add("ambiguous_native_grinding_recipe_selection"); }
            else if (selected.holder() == null || !selected.holder().id().equals(holder.id())) compatible = false;
            ItemStack left = (ItemStack) NativeApi.call(sample, "net.minecraft.world.item.ItemStack", "getCraftingRemainingItem");
            if (remainder == null) remainder = left.copy();
            else if (!ItemStack.matches(remainder, left)) unknown.add("ingredient_dependent_crafting_remainder");
        }
        if (remainder != null && !remainder.isEmpty()) {
            if (crusher) unknown.add("crusher_crafting_remainder_is_per_stack_not_per_item");
            else outputs.add(output(remainder, 1, player));
        }
        return compatible;
    }

    private static void outputs(ServerPlayer player, Object recipe, JsonArray outputs, JsonArray unknown) {
        int count = 0;
        for (Object value : (List<?>) NativeApi.call(recipe, PROCESSING, "getRollableResults")) {
            if (++count > 32) { unknown.add("grinding_output_limit"); break; }
            ItemStack stack = (ItemStack) NativeApi.call(value, null, "getStack");
            double chance = ((Number) NativeApi.call(value, null, "getChance")).doubleValue();
            if (stack.isEmpty() || !Double.isFinite(chance) || chance <= 0 || chance > 1) {
                unknown.add("invalid_or_zero_chance_grinding_output"); continue;
            }
            outputs.add(output(stack, chance, player));
        }
    }

    private static JsonObject output(ItemStack stack, double chance, ServerPlayer player) {
        JsonObject output = new JsonObject(); output.add("resource", resource(stack, player));
        output.addProperty("amount", stack.getCount()); output.addProperty("chance", chance);
        output.addProperty("guaranteed", chance == 1); return output;
    }

    private static JsonObject resource(ItemStack stack, ServerPlayer player) {
        JsonObject identity = ResourceIdentity.item(stack, player.registryAccess()), resource = new JsonObject();
        resource.addProperty("medium", "items"); resource.addProperty("id", ResourceIdentity.key(identity));
        resource.add("identity", identity); return resource;
    }
}
