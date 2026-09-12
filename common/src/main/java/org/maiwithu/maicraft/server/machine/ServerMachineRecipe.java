// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.mekanism.MekanismRecipeAccess;

/** Exact installed recipe data. Equipment compatibility is checked through its native recipe selection. */
public final class ServerMachineRecipe {
    private static final String PROCESSING = "com.simibubi.create.content.processing.recipe.ProcessingRecipe";
    private static final String PRESS = "com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity";
    private ServerMachineRecipe() {}

    public static JsonObject inspect(ServerPlayer player, JsonObject body) {
        BlockEntity entity = ServerAccess.check(player, ServerAccess.position(body.getAsJsonObject("position")), false);
        ResourceLocation id = ResourceLocation.tryParse(ServerAccess.text(body, "recipe_id"));
        if (id == null) throw ServerAccess.denied("invalid_argument", "Invalid recipe identifier");
        RecipeHolder<?> holder = MekanismRecipeAccess.find(player, entity, id)
                .or(() -> player.serverLevel().getRecipeManager().byKey(id))
                .orElseThrow(() -> ServerAccess.denied("recipe_missing", "Recipe is absent from the server recipe manager"));
        var recipe = holder.value();
        if (org.maiwithu.maicraft.server.machine.create.CreateGrindingRecipes.supports(entity))
            return org.maiwithu.maicraft.server.machine.create.CreateGrindingRecipes.inspect(player, entity, holder, body);
        if (NativeApi.is(recipe, "mekanism.api.recipes.MekanismRecipe")) {
            JsonObject mekRecipe = MekanismMachineRecipe.inspect(player, entity, holder, body);
            if (!holder.id().equals(id)) mekRecipe.addProperty("requested_recipe_id", id.toString());
            return mekRecipe;
        }
        JsonObject result = new JsonObject();
        result.addProperty("schema", "maicraft.machine_recipe.v1");
        result.addProperty("id", id.toString());
        result.addProperty("recipe_id", id.toString());
        result.addProperty("type", BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString());
        result.addProperty("tick", player.serverLevel().getGameTime());
        result.add("position", body.get("position").deepCopy());
        result.addProperty("dimension", player.serverLevel().dimension().location().toString());
        result.addProperty("provenance", "server_recipe_manager_and_native_recipe_selection");
        JsonArray inputs = new JsonArray(), outputs = new JsonArray(), conditions = new JsonArray();
        List<ItemStack> nativeOutputs = new java.util.ArrayList<>();
        JsonArray power = new JsonArray(), unknown = new JsonArray();
        result.add("inputs", inputs); result.add("outputs", outputs); result.add("conditions", conditions);
        result.add("minimum_power", power); result.add("unknown", unknown);
        boolean complete = true;
        boolean compatible = false;
        int inputIndex = 0;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (++inputIndex > 32) { complete = false; unknown.add("input_limit"); break; }
            ItemStack[] samples = ingredient.getItems();
            JsonObject input = new JsonObject();
            input.addProperty("id", "input_" + inputIndex);
            input.addProperty("amount", 1);
            input.addProperty("consumed", true);
            JsonArray alternatives = new JsonArray();
            input.add("alternatives", alternatives);
            for (int i = 0; i < Math.min(samples.length, 128); i++) {
                if (samples[i].isEmpty()) continue;
                alternatives.add(resource(samples[i], player));
                if (NativeApi.is(entity, PRESS) && NativeApi.is(recipe, "com.simibubi.create.content.kinetics.press.PressingRecipe")) {
                    Optional<?> selected = (Optional<?>) NativeApi.call(entity, PRESS, "getRecipe", samples[i]);
                    if (selected.isPresent() && selected.get() instanceof RecipeHolder<?> nativeRecipe && nativeRecipe.id().equals(id)) compatible = true;
                }
            }
            if (samples.length > 128 || alternatives.isEmpty()) { complete = false; unknown.add("ingredient_alternatives_incomplete"); }
            if (NativeApi.present("net.neoforged.neoforge.common.crafting.ICustomIngredient")) {
                if (!NativeApi.truth(NativeApi.call(ingredient, "net.minecraft.world.item.crafting.Ingredient", "isSimple"))) {
                    complete = false; unknown.add("custom_ingredient_predicate_requires_native_match");
                }
            }
            inputs.add(input);
        }
        if (NativeApi.is(recipe, PROCESSING)) {
            int outputCount = 0;
            for (Object output : (List<?>) NativeApi.call(recipe, PROCESSING, "getRollableResults")) {
                if (++outputCount > 32) { complete = false; unknown.add("output_limit"); break; }
                ItemStack stack = (ItemStack) NativeApi.call(output, "com.simibubi.create.content.processing.recipe.ProcessingOutput", "getStack");
                if (stack.isEmpty()) continue;
                nativeOutputs.add(stack.copy());
                JsonObject value = new JsonObject();
                value.add("resource", resource(stack, player));
                value.addProperty("amount", stack.getCount());
                double chance = ((Number) NativeApi.call(output, null, "getChance")).doubleValue();
                value.addProperty("chance", chance);
                value.addProperty("guaranteed", chance == 1.0);
                outputs.add(value);
            }
            result.addProperty("processing_duration", NativeApi.number(NativeApi.call(recipe, PROCESSING, "getProcessingDuration")));
            String heat = String.valueOf(NativeApi.call(recipe, PROCESSING, "getRequiredHeat"));
            if (!heat.equals("NONE")) conditions.add("create_heat:" + heat);
            if (!((List<?>) NativeApi.call(recipe, PROCESSING, "getFluidIngredients")).isEmpty()
                    || !((List<?>) NativeApi.call(recipe, PROCESSING, "getFluidResults")).isEmpty()) {
                complete = false; unknown.add("fluid_recipe_details_not_decoded");
            }
            if (NativeApi.is(recipe, "com.simibubi.create.content.kinetics.press.PressingRecipe")) {
                conditions.add("create:nonzero_rotation");
                conditions.add("create:not_overstressed");
                conditions.add("create:press_above_depot_or_belt");
                JsonObject powerNeed = new JsonObject(), rotation = new JsonObject();
                rotation.addProperty("medium", "kinetic"); rotation.addProperty("id", "rpm");
                powerNeed.add("resource", rotation); powerNeed.addProperty("amount", 1);
                powerNeed.addProperty("provenance", "automation_design_minimum; native_fractional_rpm_is_allowed");
                power.add(powerNeed);
                JsonObject checks = new JsonObject();
                result.add("condition_checks", checks);
                if (NativeApi.is(entity, PRESS)) {
                    result.add("input_obstruction", org.maiwithu.maicraft.server.machine.create.CreatePressInputInspection
                            .inspect(player, entity, recipe.getIngredients(), nativeOutputs));
                    double speed = ((Number) NativeApi.call(entity, PRESS, "getSpeed")).doubleValue();
                    checks.addProperty("create:nonzero_rotation", speed != 0 ? "verified" : "disabled");
                    checks.addProperty("create:not_overstressed", NativeApi.truth(NativeApi.call(entity, PRESS, "isOverStressed")) ? "disabled" : "verified");
                    var receiver = entity.getBlockPos().below(2);
                    if (player.serverLevel().isLoaded(receiver) && player.serverLevel().isLoaded(receiver.above())) {
                        Object handler = NativeApi.call(null, "com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour", "get",
                                player.serverLevel(), receiver, NativeApi.constant("com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour", "TYPE"));
                        boolean blocked = NativeApi.truth(NativeApi.call(null, "com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour",
                                "isBlocked", player.serverLevel(), receiver));
                        checks.addProperty("create:press_above_depot_or_belt", handler != null && !blocked ? "verified" : "disabled");
                    } else checks.addProperty("create:press_above_depot_or_belt", "unknown");
                }
            } else { complete = false; unknown.add("equipment_conditions_not_decoded"); }
        } else {
            complete = false;
            unknown.add("recipe_type_not_decoded");
        }
        result.addProperty("compatible", compatible);
        result.addProperty("compatibility", compatible ? "verified" : "unknown");
        result.addProperty("complete", complete && !inputs.isEmpty() && !outputs.isEmpty());
        result.addProperty("production_verified", false);
        return result;
    }

    private static JsonObject resource(ItemStack stack, ServerPlayer player) {
        JsonObject identity = ResourceIdentity.item(stack, player.registryAccess());
        JsonObject result = new JsonObject();
        result.addProperty("medium", "items");
        result.addProperty("id", ResourceIdentity.key(identity));
        result.add("identity", identity);
        return result;
    }
}
