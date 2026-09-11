// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import org.maiwithu.maicraft.server.inventory.NativeItemPort;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;

final class NativePortSnapshot {
    private NativePortSnapshot() {}

    static void inspect(ServerPlayer player, BlockPos pos, Direction side, JsonObject observation, SnapshotBudget budget) {
        for (String medium : new String[]{"items", "fluids", "energy", "chemicals", "mekanism_energy"}) {
            JsonObject port = new JsonObject();
            port.addProperty("side", side.getSerializedName());
            port.addProperty("medium", medium.equals("mekanism_energy") ? "energy" : medium);
            port.addProperty("api", medium);
            port.addProperty("input", "unknown");
            port.addProperty("output", "unknown");
            port.addProperty("resource_compatibility", "unknown");
            observation.getAsJsonArray("ports").add(port);
            try {
                if (medium.equals("items")) items(player, pos, side, port, observation, budget);
                else {
                    Object handler = NativeItemPort.capability(player.serverLevel(), pos, side, medium);
                    port.addProperty("status", handler == null ? "absent" : "observed");
                    if (handler == null) continue;
                    switch (medium) {
                        case "energy" -> energy(pos, side, handler, port, observation, budget);
                        case "fluids" -> fluids(player, pos, side, handler, port, observation, budget);
                        case "chemicals" -> chemicals(pos, side, handler, port, observation, budget);
                        case "mekanism_energy" -> strictEnergy(pos, side, handler, port, observation, budget);
                        default -> throw new IllegalArgumentException(medium);
                    }
                }
            } catch (RuntimeException unavailable) {
                port.addProperty("status", "unknown");
                port.addProperty("reason", unavailable.getClass().getSimpleName());
                observation.getAsJsonArray("unknown").add(side.getSerializedName() + ":" + medium);
            }
        }
    }

    private static void items(ServerPlayer player, BlockPos pos, Direction side, JsonObject port,
                              JsonObject observation, SnapshotBudget budget) {
        NativeItemPort handler = NativeItemPort.find(player.serverLevel(), pos, side);
        port.addProperty("status", handler == null ? "absent" : "observed");
        if (handler == null) return;
        int slots = handler.slots();
        port.addProperty("slot_count", slots);
        if (slots > 128) budget.truncate();
        for (int slot = 0; slot < Math.min(128, slots); slot++) {
            ItemStack stack = handler.stack(slot);
            JsonObject identity;
            try { identity = ResourceIdentity.item(stack, player.registryAccess()); }
            catch (IllegalArgumentException incomplete) {
                observation.getAsJsonArray("unknown").add(side.getSerializedName() + ":items:slot_" + slot + ":identity_unrepresentable");
                continue;
            }
            JsonObject value = ResourceIdentity.resource(identity,
                    stack.getCount(), (long) handler.limit(slot), "items", storage(pos, "items", side, slot),
                    side.getSerializedName(), pos.toShortString());
            value.addProperty("slot", slot);
            value.addProperty("views_may_overlap", true);
            if (!stack.isEmpty()) value.addProperty("valid_for_current_resource", handler.valid(slot, stack));
            budget.add(observation.getAsJsonArray("resources"), value);
        }
    }

    private static void energy(BlockPos pos, Direction side, Object handler, JsonObject port,
                               JsonObject observation, SnapshotBudget budget) {
        String api = "net.neoforged.neoforge.energy.IEnergyStorage";
        port.addProperty("input", NativeApi.truth(NativeApi.call(handler, api, "canReceive")) ? "verified" : "disabled");
        port.addProperty("output", NativeApi.truth(NativeApi.call(handler, api, "canExtract")) ? "verified" : "disabled");
        budget.add(observation.getAsJsonArray("resources"), ResourceIdentity.resource(
                ResourceIdentity.base("energy", "neoforge:energy"), NativeApi.number(NativeApi.call(handler, api, "getEnergyStored")),
                NativeApi.number(NativeApi.call(handler, api, "getMaxEnergyStored")), "FE", storage(pos, "energy", side, 0),
                side.getSerializedName(), pos.toShortString()));
    }

    @SuppressWarnings("unchecked")
    private static void fluids(ServerPlayer player, BlockPos pos, Direction side, Object handler, JsonObject port,
                               JsonObject observation, SnapshotBudget budget) {
        String api = "net.neoforged.neoforge.fluids.capability.IFluidHandler";
        String stackApi = "net.neoforged.neoforge.fluids.FluidStack";
        int count = (int) NativeApi.number(NativeApi.call(handler, api, "getTanks"));
        port.addProperty("tank_count", count);
        if (count > 32) budget.truncate();
        for (int tank = 0; tank < Math.min(count, 32); tank++) {
            Object stack = NativeApi.call(handler, api, "getFluidInTank", tank);
            Fluid fluid = (Fluid) NativeApi.call(stack, stackApi, "getFluid");
            JsonObject identity = ResourceIdentity.base("fluids", BuiltInRegistries.FLUID.getKey(fluid).toString());
            if (!NativeApi.truth(NativeApi.call(stack, stackApi, "isEmpty"))) {
                Codec<Object> codec = (Codec<Object>) NativeApi.constant(stackApi, "CODEC");
                JsonObject encoded = codec.encodeStart(RegistryOps.create(JsonOps.INSTANCE, player.registryAccess()), stack)
                        .getOrThrow().getAsJsonObject();
                if (encoded.has("components")) identity.add("components", encoded.get("components"));
            }
            JsonObject value = ResourceIdentity.resource(identity, NativeApi.number(NativeApi.call(stack, stackApi, "getAmount")),
                    NativeApi.number(NativeApi.call(handler, api, "getTankCapacity", tank)), "mB",
                    storage(pos, "fluids", side, tank), side.getSerializedName(), pos.toShortString());
            value.addProperty("tank", tank);
            budget.add(observation.getAsJsonArray("resources"), value);
        }
    }

    private static void chemicals(BlockPos pos, Direction side, Object handler, JsonObject port,
                                  JsonObject observation, SnapshotBudget budget) {
        String api = "mekanism.api.chemical.IChemicalHandler";
        int count = (int) NativeApi.number(NativeApi.call(handler, api, "getChemicalTanks"));
        port.addProperty("tank_count", count);
        if (count > 32) budget.truncate();
        for (int tank = 0; tank < Math.min(32, count); tank++) {
            Object stack = NativeApi.call(handler, api, "getChemicalInTank", tank);
            JsonObject value = ResourceIdentity.resource(ResourceIdentity.base("chemicals",
                    NativeApi.call(stack, "mekanism.api.chemical.ChemicalStack", "getTypeRegistryName").toString()),
                    NativeApi.number(NativeApi.call(stack, null, "getAmount")),
                    NativeApi.number(NativeApi.call(handler, api, "getChemicalTankCapacity", tank)), "mB",
                    storage(pos, "chemicals", side, tank), side.getSerializedName(), pos.toShortString());
            value.addProperty("tank", tank);
            budget.add(observation.getAsJsonArray("resources"), value);
        }
    }

    private static void strictEnergy(BlockPos pos, Direction side, Object handler, JsonObject port,
                                    JsonObject observation, SnapshotBudget budget) {
        String api = "mekanism.api.energy.IStrictEnergyHandler";
        int count = (int) NativeApi.number(NativeApi.call(handler, api, "getEnergyContainerCount"));
        port.addProperty("container_count", count);
        if (count > 32) budget.truncate();
        for (int slot = 0; slot < Math.min(32, count); slot++) {
            budget.add(observation.getAsJsonArray("resources"), ResourceIdentity.resource(
                    ResourceIdentity.base("energy", "mekanism:joules"), NativeApi.number(NativeApi.call(handler, api, "getEnergy", slot)),
                    NativeApi.number(NativeApi.call(handler, api, "getMaxEnergy", slot)), "J",
                    storage(pos, "mekanism_energy", side, slot), side.getSerializedName(), pos.toShortString()));
        }
    }

    private static String storage(BlockPos pos, String medium, Direction side, int slot) {
        // Sided wrappers can remap slot indices. Distinct views must not be summed as disjoint stores.
        return pos.toShortString() + "/" + medium + "/view:" + side.getSerializedName() + "/" + slot;
    }
}
