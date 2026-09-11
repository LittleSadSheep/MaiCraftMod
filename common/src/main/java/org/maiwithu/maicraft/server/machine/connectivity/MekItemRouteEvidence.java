// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.connectivity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import java.util.List;

/** A native PULL source and one real stack prove present feasibility, never actual delivery. */
final class MekItemRouteEvidence {
    private static final String TRANSPORTER = "mekanism.common.content.network.transmitter.LogisticalTransporterBase";
    private static final String TRANSMITTER = "mekanism.common.content.network.transmitter.Transmitter";
    private static final String NETWORK = "mekanism.common.lib.transmitter.DynamicNetwork";
    private static final String ITEMS = "net.neoforged.neoforge.items.IItemHandler";
    private static final int MAX_SLOTS = 128;
    private static final int MAX_DESTINATION_SIMULATIONS = 2_048;

    private MekItemRouteEvidence() {}

    static JsonObject inspect(ServerPlayer player, List<BlockPos> path, List<Object> transmitters, JsonObject body) {
        if (path.size() != transmitters.size() || path.size() < 3) {
            return result("unknown", "source_transporter_and_destination_required");
        }
        int last = path.size() - 1;
        if (transmitters.getFirst() != null || transmitters.getLast() != null) {
            return result("unknown", "actual_inventory_endpoints_required");
        }
        for (int index = 1; index < last; index++) {
            if (!NativeApi.is(transmitters.get(index), TRANSPORTER)) {
                return result("unsupported", "non_item_transporter_in_path");
            }
        }
        String resource = requestedResource(body);
        if (body.has("resource") && resource == null) return result("unknown", "invalid_item_registry_id");
        Object entry = transmitters.get(1), exit = transmitters.get(last - 1);
        Direction sourceSide = direction(path.get(1), path.getFirst());
        Direction destinationSide = direction(path.get(last - 1), path.getLast());
        if (sourceSide == null || destinationSide == null) return result("unknown", "non_adjacent_endpoint");
        Object sourceMode = NativeApi.call(entry, TRANSMITTER, "getConnectionType", sourceSide);
        if (!((Enum<?>) sourceMode).name().equals("PULL")) {
            return result("unknown", "source_active_ejection_and_item_color_unproven");
        }
        if (!NativeApi.truth(NativeApi.call(entry, TRANSPORTER, "canReceiveFrom", sourceSide))) {
            return result("planned", "source_pull_side_disabled");
        }
        Object color = NativeApi.call(entry, TRANSPORTER, "getColor");
        JsonObject route = result("unknown", "resource_sample_required");
        route.addProperty("source_color", color == null ? "uncolored" : ((Enum<?>) color).name());
        route.addProperty("source_color_provenance", "native_transporter_pull");
        if (body.has("item_color")) {
            // An asserted color never replaces the source transporter's actual PULL color.
            route.add("asserted_item_color", body.get("item_color").deepCopy());
        }
        for (int index = 1; index < last; index++) {
            Object transmitter = transmitters.get(index);
            Object actualColor = NativeApi.call(transmitter, TRANSPORTER, "getColor");
            if (actualColor != null && actualColor != color) {
                return finish(route, "planned", "item_color_cannot_enter_path_transporter");
            }
            Object network = NativeApi.call(transmitter, TRANSMITTER, "getTransmitterNetwork");
            if (network == null || NativeApi.call(network, NETWORK, "getTransmitter", path.get(index)) != transmitter) {
                return finish(route, "unknown", "transporter_network_membership_pending");
            }
            if (index < last - 1) {
                Object next = transmitters.get(index + 1);
                Direction travel = direction(path.get(index), path.get(index + 1));
                if (travel == null) return finish(route, "unknown", "non_adjacent_transporter");
                if (!currentEdge(transmitter, travel) || !currentEdge(next, travel.getOpposite())
                        || !NativeApi.truth(NativeApi.call(transmitter, TRANSPORTER, "canEmitTo", travel))
                        || !NativeApi.truth(NativeApi.call(next, TRANSPORTER, "canReceiveFrom", travel.getOpposite()))) {
                    return finish(route, "planned", "transporter_edge_unavailable");
                }
                if (network != NativeApi.call(next, TRANSMITTER, "getTransmitterNetwork")) {
                    return finish(route, "unknown", "transporter_networks_not_committed_together");
                }
            }
        }
        if (!NativeApi.truth(NativeApi.call(exit, TRANSPORTER, "canEmitTo", destinationSide))
                || NativeApi.call(exit, TRANSMITTER, "getAcceptor", destinationSide) == null) {
            return finish(route, "planned", "destination_output_side_unavailable");
        }
        Object handler = NativeApi.call(entry, TRANSMITTER, "getAcceptor", sourceSide);
        if (!NativeApi.is(handler, ITEMS)) return finish(route, "planned", "source_item_handler_unavailable");
        // TransporterUtils scans every destination slot internally. Bound its actual capability first.
        Object itemCapability = NativeApi.constant("mekanism.common.capabilities.Capabilities", "ITEM");
        Object destination = NativeApi.call(itemCapability, "mekanism.common.capabilities.MultiTypeCapability", "getCapabilityIfLoaded",
                player.serverLevel(), path.getLast(), null, player.serverLevel().getBlockEntity(path.getLast()), destinationSide.getOpposite());
        if (!NativeApi.is(destination, ITEMS)) return finish(route, "planned", "destination_item_handler_unavailable");
        int destinationSlots = Math.toIntExact(NativeApi.number(NativeApi.call(destination, ITEMS, "getSlots")));
        if (destinationSlots < 0 || destinationSlots > MAX_SLOTS) {
            return finish(route, "unknown", "destination_slot_count_outside_simulation_budget");
        }
        route.addProperty("destination_slots", destinationSlots);
        int slots = Math.toIntExact(NativeApi.number(NativeApi.call(handler, ITEMS, "getSlots")));
        if (slots < 0) return finish(route, "unknown", "invalid_source_slot_count");
        route.addProperty("source_slots_scanned", 0);
        route.addProperty("source_scan_truncated", slots > MAX_SLOTS);
        boolean extractable = false;
        int simulationBudgetUsed = 0;
        for (int slot = 0; slot < Math.min(slots, MAX_SLOTS); slot++) {
            route.addProperty("source_slots_scanned", slot + 1);
            ItemStack observed = ((ItemStack) NativeApi.call(handler, ITEMS, "getStackInSlot", slot)).copy();
            if (observed.isEmpty() || resource != null
                    && !resource.equals(BuiltInRegistries.ITEM.getKey(observed.getItem()).toString())) continue;
            ItemStack sample = ((ItemStack) NativeApi.call(handler, ITEMS, "extractItem", slot, 1, true)).copy();
            if (sample.isEmpty()) continue;
            if (sample.getCount() != 1 || !ItemStack.isSameItemSameComponents(observed, sample)) {
                return finish(route, "unknown", "source_simulation_returned_unexpected_stack");
            }
            extractable = true;
            if (simulationBudgetUsed + destinationSlots > MAX_DESTINATION_SIMULATIONS) {
                return finish(route, "unknown", "destination_simulation_budget_exhausted");
            }
            simulationBudgetUsed += destinationSlots;
            route.addProperty("destination_simulation_budget_used", simulationBudgetUsed);
            boolean accepts = NativeApi.truth(NativeApi.call(null, "mekanism.common.util.TransporterUtils", "canInsert",
                    player.serverLevel(), path.getLast(), player.serverLevel().getBlockEntity(path.getLast()),
                    color, sample.copy(), destinationSide, false));
            if (!accepts) continue;
            JsonObject identity = ResourceIdentity.item(sample, player.registryAccess());
            route.add("sample_identity", identity);
            route.addProperty("sample_resource_id", ResourceIdentity.key(identity));
            route.addProperty("sample_amount", 1);
            route.addProperty("source_slot", slot);
            route.addProperty("source_extraction_simulated", true);
            route.addProperty("destination_insertion_simulated", true);
            return finish(route, "verified", "native_pull_color_and_exact_sample_route_feasible_now");
        }
        if (slots > MAX_SLOTS) return finish(route, "unknown", "source_scan_limit_reached_without_feasible_sample");
        return finish(route, "planned", extractable ? "destination_cannot_accept_current_source_samples"
                : "matching_extractable_source_resource_required");
    }

    private static boolean currentEdge(Object transmitter, Direction side) {
        byte connections = ((Number) NativeApi.field(transmitter, TRANSMITTER, "currentTransmitterConnections")).byteValue();
        return NativeApi.truth(NativeApi.call(null, TRANSMITTER, "connectionMapContainsSide", connections, side));
    }

    /** A sorter supplies its native filter color and an already simulated, exact source sample. */
    static JsonObject inspectColoredSample(ServerPlayer player, List<BlockPos> path, List<Object> transmitters,
                                          Object color, ItemStack sample) {
        JsonObject route = result("unknown", "sorter_route_required");
        if (path.size() < 3 || sample.isEmpty() || transmitters.getLast() != null) return route;
        int last = path.size() - 1;
        for (int index = 1; index < last; index++) {
            Object transmitter = transmitters.get(index);
            if (!NativeApi.is(transmitter, TRANSPORTER)) return finish(route, "unsupported", "sorter_path_requires_item_transmitters");
            Object actualColor = NativeApi.call(transmitter, TRANSPORTER, "getColor");
            if (actualColor != null && actualColor != color) return finish(route, "planned", "sorter_filter_color_cannot_enter_transporter");
            Object network = NativeApi.call(transmitter, TRANSMITTER, "getTransmitterNetwork");
            if (network == null || NativeApi.call(network, NETWORK, "getTransmitter", path.get(index)) != transmitter) {
                return finish(route, "unknown", "sorter_route_network_pending");
            }
            Direction fromSide = direction(path.get(index), path.get(index - 1));
            Direction toSide = direction(path.get(index), path.get(index + 1));
            if (fromSide == null || toSide == null || !NativeApi.truth(NativeApi.call(transmitter, TRANSPORTER, "canReceiveFrom", fromSide))
                    || !NativeApi.truth(NativeApi.call(transmitter, TRANSPORTER, "canEmitTo", toSide))) {
                return finish(route, "planned", "sorter_route_direction_disabled");
            }
            if (index < last - 1 && (!currentEdge(transmitter, toSide) || !currentEdge(transmitters.get(index + 1), toSide.getOpposite())
                    || network != NativeApi.call(transmitters.get(index + 1), TRANSMITTER, "getTransmitterNetwork"))) {
                return finish(route, "planned", "sorter_route_native_edge_missing");
            }
        }
        Direction travel = direction(path.get(last - 1), path.getLast());
        Object capability = NativeApi.constant("mekanism.common.capabilities.Capabilities", "ITEM");
        Object destination = NativeApi.call(capability, "mekanism.common.capabilities.IMultiTypeCapability", "getCapabilityIfLoaded",
                player.serverLevel(), path.getLast(), travel.getOpposite());
        if (!NativeApi.is(destination, ITEMS)) return finish(route, "planned", "sorter_destination_handler_missing");
        int slots = ((Number) NativeApi.call(destination, ITEMS, "getSlots")).intValue();
        if (slots < 0 || slots > MAX_SLOTS) return finish(route, "unknown", "sorter_destination_simulation_budget_exceeded");
        boolean accepted = NativeApi.truth(NativeApi.call(null, "mekanism.common.util.TransporterUtils", "canInsert",
                player.serverLevel(), path.getLast(), player.serverLevel().getBlockEntity(path.getLast()), color, sample, travel, false));
        route.addProperty("source_extraction_simulated", true);
        route.addProperty("destination_insertion_simulated", true);
        route.addProperty("source_color", color == null ? "uncolored" : ((Enum<?>) color).name());
        route.addProperty("source_color_provenance", "native_sorter_filter");
        route.addProperty("sample_amount", sample.getCount());
        return finish(route, accepted ? "verified" : "planned", accepted ? "sorter_filter_sample_route_feasible_now"
                : "sorter_destination_rejects_filtered_sample");
    }

    private static Direction direction(BlockPos from, BlockPos to) {
        for (Direction side : Direction.values()) if (from.relative(side).equals(to)) return side;
        return null;
    }

    private static String requestedResource(JsonObject body) {
        if (!body.has("resource")) return null;
        var value = body.get("resource");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        ResourceLocation id = ResourceLocation.tryParse(value.getAsString());
        return id != null && BuiltInRegistries.ITEM.containsKey(id) ? id.toString() : null;
    }

    private static JsonObject result(String status, String reason) {
        JsonObject value = new JsonObject();
        value.addProperty("provenance", "mekanism_native_pull_and_simulated_item_handlers");
        value.addProperty("scope", "current_exact_item_sample_route_feasibility");
        value.addProperty("source_extraction_simulated", false);
        value.addProperty("destination_insertion_simulated", false);
        value.addProperty("batch_capacity_verified", false);
        value.addProperty("actual_route_selection_verified", false);
        value.addProperty("inventory_depletion_verified", false);
        value.addProperty("delivery_verified", false);
        value.addProperty("flow_verified", false);
        value.addProperty("production_verified", false);
        return finish(value, status, reason);
    }

    private static JsonObject finish(JsonObject value, String status, String reason) {
        value.addProperty("status", status);
        value.addProperty("reason", reason);
        value.addProperty("resource_compatibility", status);
        return value;
    }
}
