// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;

/** Adds an exact component-sensitive native item filter; existing filters are never overwritten. */
final class MekanismSorterAdapter {
    private static final String SORTER = "mekanism.common.tile.TileEntityLogisticalSorter";
    private static final String FILTER = "mekanism.common.content.transporter.SorterItemStackFilter";
    private static final String MANAGER = "mekanism.common.content.filter.FilterManager";
    private MekanismSorterAdapter() {}

    static void inspect(BlockEntity entity, JsonObject state) {
        if (!NativeApi.is(entity, SORTER)) return;
        for (String method : new String[]{"getAutoEject", "getRoundRobin", "getSingleItem", "hasConnectedInventory"}) {
            CreateMachineAdapter.scalar(state, method, NativeApi.call(entity, SORTER, method));
        }
        Object manager = NativeApi.call(entity, SORTER, "getFilterManager");
        state.addProperty("filter_count", NativeApi.number(NativeApi.call(manager, MANAGER, "count")));
        JsonArray filters = new JsonArray();
        state.add("sorter_filters", filters);
        int index = 0;
        for (Object filter : (Iterable<?>) NativeApi.call(manager, MANAGER, "getFilters")) {
            if (index >= 64) { state.addProperty("filters_truncated", true); break; }
            JsonObject entry = new JsonObject();
            entry.addProperty("index", index++);
            entry.addProperty("type", NativeApi.call(filter, "mekanism.common.content.filter.IFilter", "getFilterType").toString());
            entry.addProperty("enabled", NativeApi.truth(NativeApi.call(filter, "mekanism.common.content.filter.IFilter", "isEnabled")));
            if (NativeApi.is(filter, FILTER)) {
                entry.add("identity", ResourceIdentity.item((ItemStack) NativeApi.call(filter, FILTER, "getItemStack"),
                        entity.getLevel().registryAccess()));
                entry.addProperty("fuzzy", NativeApi.truth(NativeApi.field(filter, FILTER, "fuzzyMode")));
            }
            filters.add(entry);
        }
    }

    static JsonObject configure(ServerPlayer player, BlockEntity entity, JsonObject body) {
        if (!NativeApi.is(entity, SORTER)) throw ServerAccess.denied("unsupported", "A logistical sorter is required");
        String action = ServerAccess.text(body, "action");
        JsonObject result = new JsonObject();
        if (action.equals("mekanism.sorter_filter")) {
            // PacketNewFilter accepts native serialized ghost criteria; it does not consume sample items.
            ItemStack sample = FilterTemplate.resolve(player, body);
            if (sample.isEmpty()) throw ServerAccess.denied("invalid_argument", "An item filter must name a nonempty item");
            Object manager = NativeApi.call(entity, SORTER, "getFilterManager");
            if (NativeApi.number(NativeApi.call(manager, MANAGER, "count")) >= 64) {
                throw ServerAccess.denied("filter_limit", "At most 64 filters are managed");
            }
            Object filter;
            try { filter = NativeApi.type(FILTER).getConstructor().newInstance(); }
            catch (ReflectiveOperationException missing) { throw ServerAccess.denied("unsupported", "Native sorter filter constructor unavailable"); }
            NativeApi.call(filter, FILTER, "setItemStack", sample.copyWithCount(1));
            NativeApi.call(filter, "mekanism.common.content.filter.BaseFilter", "setEnabled", true);
            // The native constructor defaults to fuzzyMode=false, so full components remain significant.
            if (NativeApi.truth(NativeApi.field(filter, FILTER, "fuzzyMode"))) {
                throw ServerAccess.denied("unsupported", "Native filter unexpectedly defaults to fuzzy matching");
            }
            boolean added = NativeApi.truth(NativeApi.call(manager, MANAGER, "addFilter", filter));
            result.addProperty("status", added ? "applied" : "no_change");
            result.add("filter", ResourceIdentity.item(sample, player.registryAccess()));
            result.addProperty("filter_source", "native_ghost_criteria");
            boolean verified = false;
            for (Object actual : (Iterable<?>) NativeApi.call(manager, MANAGER, "getFilters")) {
                if (NativeApi.is(actual, FILTER) && !NativeApi.truth(NativeApi.field(actual, FILTER, "fuzzyMode"))
                        && NativeApi.truth(NativeApi.call(actual, "mekanism.common.content.filter.BaseFilter", "isEnabled"))
                        && ItemStack.isSameItemSameComponents(sample, (ItemStack) NativeApi.call(actual, FILTER, "getItemStack"))) verified = true;
            }
            result.addProperty("verified_configuration", verified);
        } else {
            String suffix = switch (action) {
                case "mekanism.sorter_auto_eject" -> "AutoEject";
                case "mekanism.sorter_single_item" -> "SingleItem";
                case "mekanism.sorter_round_robin" -> "RoundRobin";
                default -> throw ServerAccess.denied("unsupported", "Unknown sorter action");
            };
            boolean requested = ServerAccess.bool(body, "enabled");
            boolean current = NativeApi.truth(NativeApi.call(entity, SORTER, "get" + suffix));
            if (current != requested) NativeApi.call(entity, SORTER, "toggle" + suffix);
            result.addProperty("enabled", NativeApi.truth(NativeApi.call(entity, SORTER, "get" + suffix)));
            result.addProperty("status", current == requested ? "no_change" : "applied");
            result.addProperty("verified_configuration", NativeApi.truth(NativeApi.call(entity, SORTER, "get" + suffix)) == requested);
        }
        NativeApi.call(entity, "mekanism.common.tile.base.TileEntityUpdateable", "markForSave");
        NativeApi.call(entity, "mekanism.common.tile.base.TileEntityUpdateable", "sendUpdatePacket");
        return result;
    }

    static JsonObject configuration(ServerPlayer player, BlockEntity entity, JsonObject body) {
        if (!NativeApi.is(entity, SORTER)) return ServerMachineConfiguration.unknown("not_a_logistical_sorter");
        String action = ServerAccess.text(body, "action");
        JsonObject result = new JsonObject();
        if (!action.equals("mekanism.sorter_filter")) {
            String getter = switch (action) {
                case "mekanism.sorter_auto_eject" -> "getAutoEject";
                case "mekanism.sorter_single_item" -> "getSingleItem";
                case "mekanism.sorter_round_robin" -> "getRoundRobin";
                default -> null;
            };
            if (getter == null) return ServerMachineConfiguration.unknown("unsupported_action");
            boolean actual = NativeApi.truth(NativeApi.call(entity, SORTER, getter));
            result.addProperty("enabled", actual); result.addProperty("verified_configuration", actual == ServerAccess.bool(body, "enabled"));
            return result;
        }
        if (!body.has("item_id")) return ServerMachineConfiguration.unknown("semantic_filter_identity_required");
        ItemStack expected = FilterTemplate.resolve(player, body);
        if (expected.isEmpty()) throw ServerAccess.denied("invalid_argument", "An item filter must name a nonempty item");
        Object manager = NativeApi.call(entity, SORTER, "getFilterManager");
        int checked = 0;
        for (Object filter : (Iterable<?>) NativeApi.call(manager, MANAGER, "getFilters")) {
            if (++checked > 64) return ServerMachineConfiguration.unknown("filter_scan_limit");
            if (!NativeApi.is(filter, FILTER)) continue;
            ItemStack actual = (ItemStack) NativeApi.call(filter, FILTER, "getItemStack");
            if (!ItemStack.isSameItemSameComponents(expected, actual)) continue;
            boolean enabled = NativeApi.truth(NativeApi.call(filter, "mekanism.common.content.filter.BaseFilter", "isEnabled"));
            boolean fuzzy = NativeApi.truth(NativeApi.field(filter, FILTER, "fuzzyMode"));
            result.add("filter", ResourceIdentity.item(actual, player.registryAccess()));
            result.addProperty("enabled", enabled); result.addProperty("fuzzy", fuzzy);
            if (enabled && !fuzzy) { result.addProperty("verified_configuration", true); return result; }
        }
        result.addProperty("verified_configuration", false); return result;
    }
}
