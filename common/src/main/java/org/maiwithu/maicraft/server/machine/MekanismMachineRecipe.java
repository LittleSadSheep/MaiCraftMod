// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.machine.mekanism.MekanismRecipeAccess;
import org.maiwithu.maicraft.server.machine.mekanism.MekanismRecipeConditions;
import org.maiwithu.maicraft.server.machine.mekanism.MekanismRecipeData;

/** One Mekanism normalizer, using actual basic recipe classes and native machine recipe types. */
final class MekanismMachineRecipe {
    private static final String BASIC = "mekanism.api.recipes.basic.";
    private static final String API = "mekanism.api.recipes.";
    private static final Set<String> FIXED_DEFINITIONS = Set.of("BasicCrushingRecipe", "BasicEnrichingRecipe", "BasicSmeltingRecipe",
            "BasicCombinerRecipe", "BasicChemicalOxidizerRecipe", "BasicPigmentExtractingRecipe", "BasicChemicalConversionRecipe",
            "BasicChemicalInfuserRecipe", "BasicPigmentMixingRecipe", "BasicActivatingRecipe", "BasicCentrifugingRecipe",
            "BasicChemicalCrystallizerRecipe", "BasicElectrolysisRecipe", "BasicWashingRecipe", "BasicFluidToFluidRecipe",
            "BasicItemStackToFluidRecipe", "BasicMetallurgicInfuserRecipe", "BasicCompressingRecipe", "BasicPurifyingRecipe",
            "BasicInjectingRecipe", "BasicPaintingRecipe", "BasicRotaryRecipe");
    private MekanismMachineRecipe() {}

    static JsonObject inspect(ServerPlayer player, BlockEntity entity, RecipeHolder<?> holder, JsonObject body) {
        Object recipe = holder.value();
        JsonObject result = new JsonObject();
        result.addProperty("schema", "maicraft.machine_recipe.v1");
        result.addProperty("id", holder.id().toString()); result.addProperty("recipe_id", holder.id().toString());
        result.addProperty("type", BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()).toString());
        result.add("position", body.get("position").deepCopy());
        result.addProperty("dimension", player.serverLevel().dimension().location().toString());
        result.addProperty("tick", player.serverLevel().getGameTime());
        result.addProperty("provenance", "mekanism_native_recipe_definition_and_machine_recipe_type");
        for (String key : new String[]{"inputs", "outputs", "unknown", "conditions", "minimum_power"}) result.add(key, new JsonArray());
        result.add("condition_checks", new JsonObject());
        result.addProperty("production_verified", false);
        MekanismRecipeData data = new MekanismRecipeData(player, result);
        try {
            boolean fixedNativeDefinition = FIXED_DEFINITIONS.stream().anyMatch(name -> recipe.getClass().getName().equals(BASIC + name));
            if (!fixedNativeDefinition) data.unknown("custom_recipe_output_function_not_exhaustively_normalized");
            decode(entity, recipe, data, result);
            boolean compatible = MekanismRecipeAccess.compatible(entity, holder);
            result.addProperty("compatible", compatible);
            result.addProperty("compatibility", compatible ? "verified" : "unknown");
            result.addProperty("compatibility_basis", "native_recipe_type_identity_independent_of_current_inputs");
            if (compatible && !MekanismRecipeConditions.add(entity, recipe, result)) data.unknown("native_machine_conditions_incomplete");
            if (NativeApi.truth(NativeApi.call(recipe, API + "MekanismRecipe", "isIncomplete"))) data.unknown("native_recipe_has_missing_ingredients");
        } catch (RuntimeException | LinkageError unavailable) {
            data.unknown("native_recipe_api_unavailable_or_changed");
            result.addProperty("compatible", false); result.addProperty("compatibility", "unknown");
        }
        result.addProperty("complete", data.complete() && !result.getAsJsonArray("inputs").isEmpty() && !result.getAsJsonArray("outputs").isEmpty());
        return result;
    }

    private static void decode(BlockEntity entity, Object recipe, MekanismRecipeData data, JsonObject report) {
        String api;
        if (NativeApi.is(recipe, BASIC + "BasicItemStackToItemStackRecipe")) {
            api = API + "ItemStackToItemStackRecipe";
            data.input(NativeApi.call(recipe, api, "getInput"), "items", "input");
        } else if (NativeApi.is(recipe, BASIC + "BasicCombinerRecipe")) {
            api = API + "CombinerRecipe";
            data.input(NativeApi.call(recipe, api, "getMainInput"), "items", "main");
            data.input(NativeApi.call(recipe, api, "getExtraInput"), "items", "extra");
        } else if (NativeApi.is(recipe, BASIC + "BasicItemStackChemicalToItemStackRecipe")) {
            api = API + "ItemStackChemicalToObjectRecipe";
            data.input(NativeApi.call(recipe, api, "getItemInput"), "items", "item");
            data.input(NativeApi.call(recipe, api, "getChemicalInput"), "chemicals", "chemical");
            if (NativeApi.truth(NativeApi.call(recipe, api, "perTickUsage"))) data.unknown("chemical_usage_is_per_tick_not_a_fixed_recipe_total");
        } else if (NativeApi.is(recipe, BASIC + "BasicItemStackToChemicalRecipe")) {
            api = API + "ItemStackToChemicalRecipe";
            data.input(NativeApi.call(recipe, api, "getInput"), "items", "input");
        } else if (NativeApi.is(recipe, BASIC + "BasicChemicalChemicalToChemicalRecipe")) {
            api = API + "ChemicalChemicalToChemicalRecipe";
            data.input(NativeApi.call(recipe, api, "getLeftInput"), "chemicals", "left");
            data.input(NativeApi.call(recipe, api, "getRightInput"), "chemicals", "right");
        } else if (NativeApi.is(recipe, BASIC + "BasicWashingRecipe")) {
            api = API + "FluidChemicalToChemicalRecipe";
            data.input(NativeApi.call(recipe, api, "getFluidInput"), "fluids", "fluid");
            data.input(NativeApi.call(recipe, api, "getChemicalInput"), "chemicals", "chemical");
        } else if (NativeApi.is(recipe, BASIC + "BasicChemicalToChemicalRecipe")) {
            api = API + "ChemicalToChemicalRecipe";
            data.input(NativeApi.call(recipe, api, "getInput"), "chemicals", "input");
        } else if (NativeApi.is(recipe, BASIC + "BasicChemicalCrystallizerRecipe")) {
            api = API + "ChemicalCrystallizerRecipe";
            data.input(NativeApi.call(recipe, api, "getInput"), "chemicals", "input");
        } else if (NativeApi.is(recipe, BASIC + "BasicElectrolysisRecipe")) {
            api = API + "ElectrolysisRecipe";
            data.input(NativeApi.call(recipe, api, "getInput"), "fluids", "input");
        } else if (NativeApi.is(recipe, BASIC + "BasicFluidToFluidRecipe")) {
            api = API + "FluidToFluidRecipe";
            data.input(NativeApi.call(recipe, api, "getInput"), "fluids", "input");
        } else if (NativeApi.is(recipe, BASIC + "BasicItemStackToFluidRecipe")) {
            api = API + "ItemStackToFluidRecipe";
            data.input(NativeApi.call(recipe, api, "getInput"), "items", "input");
        } else if (NativeApi.is(recipe, BASIC + "BasicRotaryRecipe")) {
            rotary(entity, recipe, data, report); return;
        } else { data.unknown("mekanism_recipe_type_not_decoded"); return; }
        data.fixedOutputs(recipe, api, "getOutputDefinition");
    }

    private static void rotary(BlockEntity entity, Object recipe, MekanismRecipeData data, JsonObject report) {
        String machine = "mekanism.common.tile.machine.TileEntityRotaryCondensentrator";
        if (!NativeApi.is(entity, machine)) { data.unknown("rotary_machine_mode_required"); return; }
        boolean fluidToChemical = NativeApi.truth(NativeApi.call(entity, machine, "getMode"));
        String api = API + "RotaryRecipe";
        if (!NativeApi.truth(NativeApi.call(recipe, api, fluidToChemical ? "hasFluidToChemical" : "hasChemicalToFluid"))) {
            data.unknown("native_rotary_mode_has_no_recipe_direction"); return;
        }
        data.input(NativeApi.call(recipe, api, fluidToChemical ? "getFluidInput" : "getChemicalInput"),
                fluidToChemical ? "fluids" : "chemicals", "input");
        data.fixedOutputs(recipe, api, fluidToChemical ? "getChemicalOutputDefinition" : "getFluidOutputDefinition");
        report.addProperty("mode", fluidToChemical ? "fluid_to_chemical" : "chemical_to_fluid");
    }
}
