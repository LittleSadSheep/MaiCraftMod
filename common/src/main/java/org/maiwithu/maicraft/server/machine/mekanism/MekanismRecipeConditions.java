// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mekanism;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** Native upgraded costs are distinct from having input material or having already produced output. */
public final class MekanismRecipeConditions {
    private static final String MACHINE = "mekanism.common.tile.base.TileEntityMekanism";
    private static final String ENERGY = "mekanism.common.capabilities.energy.MachineEnergyContainer";
    private static final Set<String> ONE_TICK_MACHINES = Set.of("TileEntityChemicalInfuser", "TileEntityChemicalWasher",
            "TileEntityElectrolyticSeparator", "TileEntityIsotopicCentrifuge", "TileEntityRotaryCondensentrator");
    private MekanismRecipeConditions() {}

    public static boolean add(BlockEntity entity, Object recipe, JsonObject result) {
        if (!NativeApi.is(entity, MACHINE)) return false;
        condition(result, "mekanism:can_function", NativeApi.truth(NativeApi.call(entity, MACHINE, "canFunction")));
        JsonObject outputSpace = MekanismOutputSpace.inspect(entity, recipe);
        result.add("output_space", outputSpace);
        result.getAsJsonArray("conditions").add("mekanism:output_space");
        String outputStatus = outputSpace.get("status").getAsString();
        result.getAsJsonObject("condition_checks").addProperty("mekanism:output_space", outputStatus);
        boolean outputKnown = !outputStatus.equals("unknown");
        String progress = "mekanism.common.tile.prefab.TileEntityProgressMachine";
        long duration = 0;
        if (NativeApi.is(entity, progress)) {
            duration = NativeApi.number(NativeApi.call(entity, progress, "getTicksRequired"));
        } else if (NativeApi.is(entity, "mekanism.common.tile.factory.TileEntityFactory")) {
            duration = NativeApi.number(NativeApi.call(entity, "mekanism.common.tile.factory.TileEntityFactory", "getTicksRequired"));
        } else if (ONE_TICK_MACHINES.stream().anyMatch(name -> entity.getClass().getName().equals("mekanism.common.tile.machine." + name))) {
            // These native builders retain CachedRecipe's one-tick default; the installed-jar test guards this contract.
            duration = 1;
        }
        if (duration <= 0) { result.getAsJsonArray("unknown").add("native_processing_duration_not_exposed"); return false; }
        result.addProperty("processing_duration", duration);
        List<?> containers = (List<?>) NativeApi.call(entity, MACHINE, "getEnergyContainers", (Direction) null);
        if (containers.size() > 16) return false;
        for (Object container : containers) {
            if (!NativeApi.is(container, ENERGY)) continue;
            long required = NativeApi.number(NativeApi.call(container, ENERGY, "getEnergyPerTick"));
            if (NativeApi.is(recipe, "mekanism.api.recipes.ElectrolysisRecipe")) {
                String separator = "mekanism.common.tile.machine.TileEntityElectrolyticSeparator";
                if (!NativeApi.is(entity, separator)) return false;
                Object attribute = NativeApi.call(null, ENERGY, "validateBlock", entity);
                long configuredBase = NativeApi.number(NativeApi.call(attribute, "mekanism.common.block.attribute.AttributeEnergy", "getUsage"));
                long multiplier = NativeApi.number(NativeApi.call(recipe, "mekanism.api.recipes.ElectrolysisRecipe", "getEnergyMultiplier"));
                required = Math.multiplyExact(configuredBase, multiplier);
                result.addProperty("recipe_energy_multiplier", multiplier);
            }
            if (required < 0) return false;
            if (required == 0) {
                result.addProperty("energy_per_tick_joules", 0);
                condition(result, "mekanism:energy_available", true);
                return outputKnown;
            }
            JsonObject power = new JsonObject(), resource = new JsonObject();
            JsonObject identity = ResourceIdentity.base("energy", "mekanism:joules");
            resource.addProperty("medium", "energy"); resource.addProperty("id", ResourceIdentity.key(identity)); resource.add("identity", identity);
            power.add("resource", resource); power.addProperty("amount", Math.multiplyExact(required, duration));
            power.addProperty("unit", "J/operation"); power.addProperty("scope", "one_operation_total_energy_budget");
            power.addProperty("rate", required); power.addProperty("rate_unit", "J/t"); power.addProperty("duration_ticks", duration);
            result.getAsJsonArray("minimum_power").add(power);
            long available = NativeApi.number(NativeApi.call(container, "mekanism.api.energy.IEnergyContainer", "getEnergy"));
            condition(result, "mekanism:energy_available", available >= required);
            result.addProperty("energy_per_tick_joules", required);
            result.addProperty("energy_supply_sustained_verified", false);
            return outputKnown;
        }
        result.getAsJsonArray("unknown").add("machine_energy_or_environment_requirements_not_exposed");
        return false;
    }

    private static void condition(JsonObject result, String name, boolean fulfilled) {
        result.getAsJsonArray("conditions").add(name);
        result.getAsJsonObject("condition_checks").addProperty(name, fulfilled ? "verified" : "disabled");
    }
}
