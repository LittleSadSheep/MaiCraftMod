// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mekanism;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** Complete single-operation outputs are simulated against native output storage using INTERNAL access. */
public final class MekanismOutputSpace {
    private static final String MACHINES = "mekanism.common.tile.machine.";
    private static final String BASIC = "mekanism.api.recipes.basic.", RECIPES = "mekanism.api.recipes.";
    private static final String SLOT = "mekanism.api.inventory.IInventorySlot";
    private static final String CHEMICAL_TANK = "mekanism.api.chemical.IChemicalTank";
    private static final String FLUID_TANK = "mekanism.api.fluid.IExtendedFluidTank";
    private record Target(Object storage, Object output, String api, String insertion, String name) {}

    private MekanismOutputSpace() {}

    public static JsonObject inspect(BlockEntity entity, Object recipe) {
        JsonObject evidence = result("unknown", "native_output_storage_not_identified");
        try {
            if (entity == null || entity.isRemoved() || !(entity.getLevel() instanceof ServerLevel level)
                    || !level.getServer().isSameThread() || !level.isLoaded(entity.getBlockPos())
                    || level.getBlockEntity(entity.getBlockPos()) != entity) return evidence;
            if (!(recipe instanceof Recipe<?> actual) || !NativeApi.is(entity, MekanismRecipeAccess.LOOKUP)) return evidence;
            Object provider = NativeApi.call(entity, MekanismRecipeAccess.LOOKUP, "getRecipeType");
            if (NativeApi.call(provider, MekanismRecipeAccess.PROVIDER, "getRecipeType") != actual.getType()) {
                return finish(evidence, "unknown", "target_recipe_type_not_supported_by_machine");
            }
            List<Target> targets = targets(entity, recipe, evidence);
            if (targets.isEmpty()) return evidence;
            Set<Object> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
            Object simulate = NativeApi.constant("mekanism.api.Action", "SIMULATE");
            Object internal = NativeApi.constant("mekanism.api.AutomationType", "INTERNAL");
            boolean all = true;
            int chars = 0;
            for (Target target : targets) {
                if (target.storage == null || !NativeApi.is(target.storage, target.api) || !distinct.add(target.storage)) {
                    return finish(evidence, "unknown", "output_storage_missing_or_aliased");
                }
                Object offered = MekanismResourceStacks.copy(target.output);
                Object reference = MekanismResourceStacks.copy(offered);
                long required = MekanismResourceStacks.amount(reference);
                if (required <= 0) return finish(evidence, "unknown", "nonpositive_single_operation_output");
                Object returned = NativeApi.call(target.storage, target.api, target.insertion, offered, simulate, internal);
                Object remainder = MekanismResourceStacks.copy(returned);
                long remaining = MekanismResourceStacks.amount(remainder);
                if (remaining < 0 || remaining > required || remaining > 0
                        && !MekanismResourceStacks.sameIdentity(reference, remainder, level.registryAccess())) {
                    return finish(evidence, "unknown", "native_simulation_returned_inconsistent_remainder");
                }
                JsonObject check = new JsonObject();
                check.addProperty("storage", target.name);
                check.addProperty("resource_id", ResourceIdentity.key(MekanismResourceStacks.identity(reference, level.registryAccess())));
                check.addProperty("required_amount", required); check.addProperty("accepted_amount", required - remaining);
                check.addProperty("status", remaining == 0 ? "verified" : "disabled");
                chars += check.toString().length();
                if (chars > 8000) return finish(evidence, "unknown", "output_space_evidence_budget_exceeded");
                evidence.getAsJsonArray("checks").add(check);
                all &= remaining == 0;
            }
            return finish(evidence, all ? "verified" : "disabled", all
                    ? "complete_single_operation_outputs_fit_native_storage" : "native_output_storage_cannot_accept_complete_output");
        } catch (RuntimeException | LinkageError unavailable) {
            return finish(evidence, "unknown", "native_output_space_api_unavailable_or_failed");
        }
    }

    private static List<Target> targets(BlockEntity entity, Object recipe, JsonObject evidence) {
        String itemApi = itemApi(recipe);
        if (itemApi != null) return itemTargets(entity, definition(recipe, itemApi, "getOutputDefinition"), evidence);
        List<Target> result = new ArrayList<>();
        if (machine(entity, "TileEntityChemicalOxidizer") && basic(recipe, "BasicItemStackToChemicalRecipe")) {
            result.add(chemical(entity, "TileEntityChemicalOxidizer", "gasTank", definition(recipe, "ItemStackToChemicalRecipe", "getOutputDefinition")));
        } else if (machine(entity, "TileEntityChemicalInfuser") && basic(recipe, "BasicChemicalChemicalToChemicalRecipe")) {
            result.add(chemical(entity, "TileEntityChemicalInfuser", "centerTank", definition(recipe, "ChemicalChemicalToChemicalRecipe", "getOutputDefinition")));
        } else if (machine(entity, "TileEntityChemicalWasher") && basic(recipe, "BasicWashingRecipe")) {
            result.add(chemical(entity, "TileEntityChemicalWasher", "outputTank", definition(recipe, "FluidChemicalToChemicalRecipe", "getOutputDefinition")));
        } else if (machine(entity, "TileEntityIsotopicCentrifuge") && basic(recipe, "BasicChemicalToChemicalRecipe")) {
            result.add(chemical(entity, "TileEntityIsotopicCentrifuge", "outputTank", definition(recipe, "ChemicalToChemicalRecipe", "getOutputDefinition")));
        } else if (machine(entity, "TileEntityElectrolyticSeparator") && basic(recipe, "BasicElectrolysisRecipe")) {
            Object output = definition(recipe, "ElectrolysisRecipe", "getOutputDefinition");
            String api = RECIPES + "ElectrolysisRecipe$ElectrolysisRecipeOutput";
            result.add(chemical(entity, "TileEntityElectrolyticSeparator", "leftTank", NativeApi.call(output, api, "left")));
            result.add(chemical(entity, "TileEntityElectrolyticSeparator", "rightTank", NativeApi.call(output, api, "right")));
        } else if (machine(entity, "TileEntityRotaryCondensentrator") && basic(recipe, "BasicRotaryRecipe")) {
            boolean fluidToChemical = NativeApi.truth(NativeApi.call(entity, MACHINES + "TileEntityRotaryCondensentrator", "getMode"));
            if (!NativeApi.truth(NativeApi.call(recipe, RECIPES + "RotaryRecipe", fluidToChemical ? "hasFluidToChemical" : "hasChemicalToFluid"))) {
                finish(evidence, "disabled", "rotary_direction_not_supported_by_target_recipe"); return result;
            }
            if (fluidToChemical) result.add(chemical(entity, "TileEntityRotaryCondensentrator", "gasTank",
                    definition(recipe, "RotaryRecipe", "getChemicalOutputDefinition")));
            else result.add(new Target(NativeApi.field(entity, MACHINES + "TileEntityRotaryCondensentrator", "fluidTank"),
                    definition(recipe, "RotaryRecipe", "getFluidOutputDefinition"), FLUID_TANK, "insert", "fluidTank"));
            evidence.addProperty("mode", fluidToChemical ? "fluid_to_chemical" : "chemical_to_fluid");
        }
        return result;
    }

    private static List<Target> itemTargets(BlockEntity entity, Object output, JsonObject evidence) {
        boolean factory = NativeApi.is(entity, "mekanism.common.tile.factory.TileEntityFactory");
        boolean standalone = NativeApi.is(entity, "mekanism.common.tile.prefab.TileEntityElectricMachine")
                || machine(entity, "TileEntityCombiner") || machine(entity, "TileEntityChemicalCrystallizer")
                || machine(entity, "TileEntityMetallurgicInfuser");
        if ((!factory && !standalone) || !(output instanceof ItemStack)) return List.of();
        Object raw = NativeApi.call(entity, "mekanism.common.tile.base.TileEntityMekanism", "getInventorySlots", (Direction) null);
        if (!(raw instanceof List<?> slots) || slots.size() > 128) {
            finish(evidence, "unknown", "native_inventory_slot_scan_exceeds_budget"); return List.of();
        }
        List<Target> result = new ArrayList<>();
        for (Object slot : slots) {
            if (NativeApi.is(slot, "mekanism.common.inventory.slot.OutputInventorySlot")) {
                result.add(new Target(slot, output, SLOT, "insertItem", "native_output_slot_" + result.size()));
            }
        }
        if (result.isEmpty() || !factory && result.size() != 1) {
            finish(evidence, "unknown", "standalone_output_slot_not_unique_or_absent"); return List.of();
        }
        if (factory) {
            evidence.addProperty("scope", "all_factory_output_slots_one_operation_each");
            evidence.addProperty("lane_assignment_verified", false);
        }
        return result;
    }

    private static Target chemical(BlockEntity entity, String owner, String field, Object output) {
        if (!NativeApi.is(output, MekanismResourceStacks.CHEMICAL)) throw new IllegalArgumentException("Expected chemical output");
        return new Target(NativeApi.field(entity, MACHINES + owner, field), output, CHEMICAL_TANK, "insert", field);
    }
    private static Object definition(Object recipe, String api, String method) {
        Object raw = NativeApi.call(recipe, RECIPES + api, method);
        if (!(raw instanceof List<?> values) || values.size() != 1) throw new IllegalArgumentException("Output definition must be fixed");
        return values.getFirst();
    }
    private static String itemApi(Object recipe) {
        if (basic(recipe, "BasicItemStackToItemStackRecipe")) return "ItemStackToItemStackRecipe";
        if (basic(recipe, "BasicCombinerRecipe")) return "CombinerRecipe";
        if (basic(recipe, "BasicChemicalCrystallizerRecipe")) return "ChemicalCrystallizerRecipe";
        if (basic(recipe, "BasicItemStackChemicalToItemStackRecipe")) return "ItemStackChemicalToObjectRecipe";
        return null;
    }
    private static boolean machine(Object entity, String name) { return NativeApi.is(entity, MACHINES + name); }
    private static boolean basic(Object recipe, String name) { return NativeApi.is(recipe, BASIC + name); }
    private static JsonObject result(String status, String reason) {
        JsonObject result = new JsonObject();
        result.addProperty("check", "mekanism:output_space");
        result.addProperty("provenance", "mekanism_native_output_storage_simulate_internal");
        result.addProperty("scope", "one_operation_internal_output_capacity");
        result.addProperty("production_verified", false); result.add("checks", new JsonArray());
        return finish(result, status, reason);
    }
    private static JsonObject finish(JsonObject result, String status, String reason) {
        result.addProperty("status", status); result.addProperty("reason", reason); return result;
    }
}
