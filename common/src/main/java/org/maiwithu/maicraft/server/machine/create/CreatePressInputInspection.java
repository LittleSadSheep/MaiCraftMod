// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.create;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** Read-only Create 6 depot occupancy and native press recipe selection; no insertion or cleanup. */
public final class CreatePressInputInspection {
    private static final String DEPOT = "com.simibubi.create.content.logistics.depot.DepotBlockEntity";
    private static final String PRESS = "com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity";
    private static final String PROVENANCE = "native_create_depot_held_item_and_press_recipe_selection";
    private CreatePressInputInspection() {}

    public static JsonObject inspect(ServerPlayer player, BlockEntity press, List<Ingredient> inputs, List<ItemStack> outputs) {
        var position = press.getBlockPos().below(2);
        JsonObject result;
        try {
            if (!player.serverLevel().isLoaded(position)) result = status("unknown", "receiver_unloaded");
            else {
                Object receiver = player.serverLevel().getBlockEntity(position);
                if (!NativeApi.is(receiver, DEPOT)) result = status("unknown", "receiver_is_not_an_observed_depot");
                else result = inspect((ItemStack) NativeApi.call(receiver, DEPOT, "getHeldItem"), inputs, outputs,
                        player.registryAccess(), stack -> (Optional<?>) NativeApi.call(press, PRESS, "getRecipe", stack));
            }
        } catch (RuntimeException | LinkageError unavailable) { result = status("unknown", "native_input_inspection_unavailable"); }
        JsonObject at = new JsonObject(); at.addProperty("x", position.getX());
        at.addProperty("y", position.getY()); at.addProperty("z", position.getZ()); result.add("receiver_position", at);
        return result;
    }

    /** Native stack/ingredient rules with a recipe lookup seam for deterministic policy regressions. */
    static JsonObject inspect(ItemStack held, List<Ingredient> inputs, List<ItemStack> outputs,
                              HolderLookup.Provider registries, Function<ItemStack, Optional<?>> recipes) {
        try {
            ItemStack sample = held.copy();
            if (sample.isEmpty()) return status("not_blocked", "depot_empty");
            if (inputs.size() > 32 || outputs.size() > 32) return status("unknown", "recipe_inspection_budget_exceeded");
            JsonObject result;
            if (inputs.stream().anyMatch(input -> input.test(sample))) result = status("not_blocked", "requested_recipe_input_present");
            else if (outputs.stream().anyMatch(output -> ItemStack.isSameItemSameComponents(output, sample)))
                result = status("not_blocked", "declared_output_awaiting_extraction");
            else if (recipes.apply(sample.copy()).isPresent()) result = status("not_blocked", "native_press_recipe_available");
            else result = status("blocked", "unrelated_unprocessable_depot_item");
            JsonObject identity = ResourceIdentity.item(sample, registries);
            result.add("identity", identity); result.addProperty("resource_id", ResourceIdentity.key(identity));
            result.addProperty("amount", sample.getCount());
            return result;
        } catch (RuntimeException | LinkageError unavailable) { return status("unknown", "native_input_inspection_unavailable"); }
    }

    private static JsonObject status(String state, String reason) {
        JsonObject result = new JsonObject(); result.addProperty("status", state); result.addProperty("reason", reason);
        result.addProperty("provenance", PROVENANCE); return result;
    }
}
