// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;
import org.maiwithu.maicraft.server.machine.SnapshotBudget;

/** Bounded pages from AE2's actual storage service and crafting CPU status. */
public final class Ae2NetworkSnapshot {
    private Ae2NetworkSnapshot() {}

    public static JsonObject inspect(ServerPlayer player, JsonObject body) {
        BlockPos pos = ServerAccess.position(body.getAsJsonObject("position"));
        Direction side = ServerAccess.side(body);
        Ae2Access access = Ae2Access.terminal(player, pos, side, false);
        int offset = body.has("resource_offset") ? ServerAccess.integer(body, "resource_offset", 0, 4096) : 0;
        int limit = body.has("resource_limit") ? ServerAccess.integer(body, "resource_limit", 1, 128) : 64;
        SnapshotBudget budget = new SnapshotBudget(offset, limit);
        JsonObject result = new JsonObject();
        result.addProperty("schema", "maicraft.ae2_network.v1");
        result.addProperty("tick", player.serverLevel().getGameTime());
        result.addProperty("dimension", player.serverLevel().dimension().location().toString());
        result.addProperty("membership", access.membership());
        result.addProperty("status", "observed");
        result.addProperty("inventory_source", "native_storage_service_cache");
        result.addProperty("capacity", "unknown");
        result.addProperty("used_channels", NativeApi.number(NativeApi.call(access.node(), Ae2Access.NODE, "getUsedChannels")));
        result.addProperty("max_channels", NativeApi.number(NativeApi.call(access.node(), Ae2Access.NODE, "getMaxChannels")));
        result.addProperty("node_count", NativeApi.number(NativeApi.call(access.grid(), Ae2Access.GRID, "size")));
        result.addProperty("stored_power", (Number) NativeApi.call(access.energy(), "appeng.api.networking.energy.IEnergyService", "getStoredPower"));
        result.addProperty("power_capacity", (Number) NativeApi.call(access.energy(), "appeng.api.networking.energy.IEnergyService", "getMaxStoredPower"));
        result.addProperty("power_unit", "AE");
        JsonArray resources = new JsonArray();
        JsonArray unknown = new JsonArray();
        result.add("resources", resources);
        result.add("unknown", unknown);
        Object crafting = NativeApi.call(access.grid(), Ae2Access.GRID, "getCraftingService");
        String craftApi = "appeng.api.networking.crafting.ICraftingService";
        String itemFilter = body.has("item_id") ? ServerAccess.text(body, "item_id") : null;
        int inspected = 0;
        java.util.Set<Object> seen = new java.util.HashSet<>();
        boolean nonItemKeys = false;
        for (Object raw : (Iterable<?>) access.cachedInventory()) {
            if (++inspected > 4096) { budget.truncate(); break; }
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) raw;
            Object key = entry.getKey();
            if (!NativeApi.is(key, Ae2Access.ITEM)) { nonItemKeys = true; continue; }
            seen.add(key);
            ItemStack sample = (ItemStack) NativeApi.call(key, Ae2Access.ITEM, "toStack", 1);
            JsonObject identity = ResourceIdentity.item(sample, player.registryAccess());
            if (itemFilter != null && !itemFilter.equals(identity.get("id").getAsString())) continue;
            JsonObject resource = ResourceIdentity.resource(identity, ((Number) entry.getValue()).longValue(), null,
                    "items", access.membership() + "/inventory", side.getSerializedName(), access.membership());
            resource.addProperty("craftable", NativeApi.truth(NativeApi.call(crafting, craftApi, "isCraftable", key)));
            resource.addProperty("requested_amount", NativeApi.number(NativeApi.call(crafting, craftApi, "getRequestedAmount", key)));
            budget.add(resources, resource);
        }
        if (nonItemKeys) unknown.add("non_item_keys_not_decoded");
        inspected = 0;
        for (Object key : Ae2Keys.craftables(access)) {
            if (++inspected > 4096) { budget.truncate(); break; }
            if (!seen.add(key)) continue;
            JsonObject identity = Ae2Keys.identity(player, key);
            if (itemFilter != null && !itemFilter.equals(identity.get("id").getAsString())) continue;
            JsonObject resource = ResourceIdentity.resource(identity, 0, null, "items", access.membership() + "/inventory",
                    side.getSerializedName(), access.membership());
            resource.addProperty("craftable", true);
            resource.addProperty("requested_amount", NativeApi.number(NativeApi.call(crafting, craftApi, "getRequestedAmount", key)));
            budget.add(resources, resource);
        }
        result.addProperty("pagination_consistency", "live_native_inventory_and_craftable_key_views");
        JsonArray cpus = new JsonArray();
        result.add("crafting_cpus", cpus);
        int cpuIndex = 0;
        for (Object cpu : (Iterable<?>) NativeApi.call(crafting, craftApi, "getCpus")) {
            if (++cpuIndex > 32) { unknown.add("crafting_cpus_truncated"); break; }
            String api = "appeng.api.networking.crafting.ICraftingCPU";
            JsonObject value = new JsonObject();
            value.addProperty("busy", NativeApi.truth(NativeApi.call(cpu, api, "isBusy")));
            value.addProperty("available_storage", NativeApi.number(NativeApi.call(cpu, api, "getAvailableStorage")));
            value.addProperty("coprocessors", NativeApi.number(NativeApi.call(cpu, api, "getCoProcessors")));
            Object job = NativeApi.call(cpu, api, "getJobStatus");
            if (job != null) {
                value.addProperty("total_items", NativeApi.number(NativeApi.call(job, null, "totalItems")));
                value.addProperty("progress", NativeApi.number(NativeApi.call(job, null, "progress")));
                value.addProperty("elapsed_nanos", NativeApi.number(NativeApi.call(job, null, "elapsedTimeNanos")));
            }
            cpus.add(value);
        }
        result.addProperty("truncated", budget.truncated());
        result.addProperty("complete", !budget.truncated() && unknown.isEmpty());
        result.addProperty("next_resource_offset", budget.nextOffset());
        return result;
    }
}
