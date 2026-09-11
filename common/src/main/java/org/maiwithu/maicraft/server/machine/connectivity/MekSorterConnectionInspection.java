// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.connectivity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;
import java.util.ArrayList;
import java.util.List;

/** Sorters are directional active ejectors, not transmitter-network members. */
final class MekSorterConnectionInspection {
    static final String SORTER = "mekanism.common.tile.TileEntityLogisticalSorter";
    private static final String BASE = "mekanism.common.tile.base.TileEntityMekanism";
    private static final String ITEMS = "net.neoforged.neoforge.items.IItemHandler";
    private static final String FILTER = "mekanism.common.content.transporter.SorterFilter";

    private MekSorterConnectionInspection() {}
    static boolean is(Object value) { return NativeApi.is(value, SORTER); }

    static ConnectionEvidence home(BlockEntity inventory, BlockEntity sorter, BlockPos inventoryPosition) {
        Direction facing = facing(sorter);
        if (!sorter.getBlockPos().relative(facing.getOpposite()).equals(inventoryPosition)) return edge(false, false, "sorter_home_face_mismatch");
        Object handler = capability(sorter, inventoryPosition, facing);
        boolean attached = NativeApi.is(handler, ITEMS) && NativeApi.truth(NativeApi.call(sorter, SORTER, "hasConnectedInventory"));
        boolean running = NativeApi.truth(NativeApi.call(sorter, BASE, "canFunction"));
        ConnectionEvidence result = edge(attached, attached && running, !attached ? "sorter_home_handler_unavailable"
                : !running ? "sorter_redstone_or_function_gate_closed" : "native_sorter_home_inventory_attached");
        result.details().addProperty("inventory_face", facing.getSerializedName());
        result.details().addProperty("resource_filter_verified", false);
        return result;
    }

    static ConnectionEvidence output(BlockEntity sorter, Object transmitter, BlockPos target) {
        Direction facing = facing(sorter);
        if (!sorter.getBlockPos().relative(facing).equals(target)) return edge(false, false, "sorter_output_face_mismatch");
        Object handler = capability(sorter, target, facing.getOpposite());
        String cursed = "mekanism.common.capabilities.item.CursedTransporterItemHandler";
        boolean attached = NativeApi.is(handler, cursed) && NativeApi.call(handler, cursed, "getTransporter") == transmitter;
        boolean receiving = attached && NativeApi.truth(NativeApi.call(transmitter,
                "mekanism.common.content.network.transmitter.LogisticalTransporterBase", "canReceiveFrom", facing.getOpposite()));
        Object network = receiving ? NativeApi.call(transmitter,
                "mekanism.common.content.network.transmitter.Transmitter", "getTransmitterNetwork") : null;
        boolean committed = network != null && NativeApi.call(network,
                "mekanism.common.lib.transmitter.DynamicNetwork", "getTransmitter", target) == transmitter;
        boolean running = NativeApi.truth(NativeApi.call(sorter, BASE, "canFunction"));
        return edge(receiving && committed, receiving && committed && running,
                !attached ? "sorter_target_is_not_actual_transporter_handler" : !receiving ? "sorter_transporter_input_disabled"
                        : !committed ? "sorter_target_network_pending" : !running ? "sorter_redstone_or_function_gate_closed"
                        : "native_sorter_transporter_output_attached");
    }

    static JsonObject route(ServerPlayer player, List<BlockPos> path, List<BlockEntity> entities,
                            List<Object> transmitters, JsonObject body) {
        int index = is(entities.getFirst()) ? 0 : entities.size() > 1 && is(entities.get(1)) ? 1 : -1;
        if (index < 0) return null;
        BlockEntity sorter = entities.get(index);
        BlockPos source = sorter.getBlockPos().relative(facing(sorter).getOpposite());
        ServerAccess.check(player, source, false);
        if (index == 1 && !source.equals(path.getFirst())) return routeResult("planned", "declared_source_is_not_sorter_home");
        Object handler = capability(sorter, source, facing(sorter));
        if (!NativeApi.is(handler, ITEMS)) return routeResult("planned", "sorter_home_handler_unavailable");
        int slots = ((Number) NativeApi.call(handler, ITEMS, "getSlots")).intValue();
        if (slots < 0 || slots > 128) return routeResult("unknown", "sorter_source_scan_budget_exceeded");
        Object manager = NativeApi.call(sorter, SORTER, "getFilterManager");
        List<?> filters = (List<?>) NativeApi.call(manager, "mekanism.common.content.filter.FilterManager", "getEnabledFilters");
        if (filters.size() > 8) return routeResult("unknown", "sorter_filter_scan_budget_exceeded");
        boolean single = NativeApi.truth(NativeApi.call(sorter, SORTER, "getSingleItem"));
        for (Object filter : filters) {
            Object request = NativeApi.call(filter, FILTER, "mapInventory", handler, single);
            List<?> data = candidates(request);
            if (data == null) return routeResult("unknown", "sorter_candidate_budget_exceeded");
            for (Object item : data) {
                ItemStack sample = ((ItemStack) NativeApi.call(item, "mekanism.common.lib.inventory.TransitRequest$ItemData", "getStack")).copy();
                if (sample.isEmpty() || body.has("resource") && !body.get("resource").getAsString()
                        .equals(BuiltInRegistries.ITEM.getKey(sample.getItem()).toString())) continue;
                if (!single && NativeApi.truth(NativeApi.field(filter, FILTER, "sizeMode"))
                        && NativeApi.number(NativeApi.field(filter, FILTER, "min")) > 1) {
                    return routeResult("unknown", "sorter_minimum_batch_requires_full_destination_capacity_proof");
                }
                Object color = NativeApi.field(filter, FILTER, "color");
                return routeSample(player, path, transmitters, index, source, color, sample, "native_SorterFilter.mapInventory_simulated_extraction");
            }
        }
        if (NativeApi.truth(NativeApi.call(sorter, SORTER, "getAutoEject"))) {
            Object request = NativeApi.call(null, "mekanism.common.lib.inventory.TransitRequest", "definedItem", handler,
                    single ? 1 : 99, NativeApi.constant("mekanism.common.lib.inventory.Finder", "ANY"));
            List<?> data = candidates(request);
            if (data == null) return routeResult("unknown", "sorter_default_candidate_budget_exceeded");
            for (Object item : data) {
                ItemStack sample = ((ItemStack) NativeApi.call(item, "mekanism.common.lib.inventory.TransitRequest$ItemData", "getStack")).copy();
                if (sample.isEmpty() || body.has("resource") && !body.get("resource").getAsString()
                        .equals(BuiltInRegistries.ITEM.getKey(sample.getItem()).toString())) continue;
                boolean allowed = true;
                for (Object filter : filters) {
                    if (!NativeApi.truth(NativeApi.field(filter, FILTER, "allowDefault")) && NativeApi.truth(NativeApi.call(
                            NativeApi.call(filter, FILTER, "getFinder"), "mekanism.common.lib.inventory.Finder", "test", sample))) {
                        allowed = false; break;
                    }
                }
                if (allowed) return routeSample(player, path, transmitters, index, source, NativeApi.field(sorter, SORTER, "color"),
                        sample, "native_default_eject_finder_and_simulated_extraction");
            }
        }
        return routeResult("unknown", "no_native_filter_route_has_requested_extractable_resource");
    }

    private static JsonObject routeSample(ServerPlayer player, List<BlockPos> path, List<Object> transmitters, int index,
                                          BlockPos source, Object color, ItemStack sample, String provenance) {
        JsonObject result;
        if (index == path.size() - 1) {
            result = routeResult("verified", "native_filter_accepts_simulated_home_inventory_sample");
            result.addProperty("scope", "sorter_home_extraction_and_filter_feasibility");
        } else {
            result = MekItemRouteEvidence.inspectColoredSample(player, path.subList(index, path.size()),
                    transmitters.subList(index, transmitters.size()), color, sample.copyWithCount(1));
        }
        result.addProperty("source_inventory", source.getX() + "," + source.getY() + "," + source.getZ());
        result.addProperty("sorter_filter_provenance", provenance);
        JsonObject identity = ResourceIdentity.item(sample, player.registryAccess());
        result.addProperty("sample_resource_id", ResourceIdentity.key(identity)); result.add("sample_identity", identity);
        return result;
    }

    private static Direction facing(BlockEntity sorter) { return (Direction) NativeApi.call(sorter, BASE, "getDirection"); }

    private static List<?> candidates(Object request) {
        if (!(request instanceof Iterable<?> iterable)) return null;
        List<Object> result = new ArrayList<>();
        for (Object item : iterable) {
            if (result.size() >= 128) return null;
            result.add(item);
        }
        return result;
    }

    private static Object capability(BlockEntity sorter, BlockPos position, Direction side) {
        return NativeApi.call(NativeApi.constant("mekanism.common.capabilities.Capabilities", "ITEM"),
                "mekanism.common.capabilities.IMultiTypeCapability", "getCapabilityIfLoaded", sorter.getLevel(), position, side);
    }

    private static ConnectionEvidence edge(boolean connected, boolean operational, String reason) {
        return ConnectionEvidence.of(operational ? "verified" : "planned", connected, operational, reason,
                "Mekanism.TileEntityLogisticalSorter.native_home_and_target_capabilities");
    }

    private static JsonObject routeResult(String status, String reason) {
        JsonObject result = new JsonObject(); result.addProperty("status", status); result.addProperty("reason", reason);
        result.addProperty("provenance", "mekanism_native_sorter_filter"); result.addProperty("flow_verified", false);
        result.addProperty("production_verified", false); return result;
    }
}
