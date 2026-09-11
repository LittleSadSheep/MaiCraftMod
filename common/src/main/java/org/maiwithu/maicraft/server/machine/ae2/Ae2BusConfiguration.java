// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.ae2;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.inventory.Ae2Access;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** Exact item ghost filters in native unlocked config slots; no real inventory contents are created. */
final class Ae2BusConfiguration {
    private static final String BUS = "appeng.parts.automation.IOBusPart";
    private static final String INVENTORY = "appeng.helpers.externalstorage.GenericStackInv";
    private static final String UPGRADES = "appeng.api.upgrades.IUpgradeableObject";
    private Ae2BusConfiguration() {}

    static JsonObject configure(ServerPlayer player, Object part, JsonObject body) {
        boolean importing = NativeApi.is(part, "appeng.parts.automation.ImportBusPart");
        if (!importing && !NativeApi.is(part, "appeng.parts.automation.ExportBusPart")) {
            throw ServerAccess.denied("unsupported", "An actual AE2 import or export bus is required");
        }
        for (String upgrade : new String[]{"FUZZY_CARD", "INVERTER_CARD"}) {
            if (NativeApi.truth(NativeApi.call(part, UPGRADES, "isUpgradedWith",
                    NativeApi.constant("appeng.core.definitions.AEItems", upgrade)))) {
                throw ServerAccess.denied("incompatible_upgrade", "Exact include filtering requires no fuzzy or inverter card");
            }
        }
        ItemStack criterion = criterion(player, body);
        Object key = NativeApi.call(null, Ae2Access.ITEM, "of", criterion);
        Object config = NativeApi.call(part, BUS, "getConfig");
        if (!"CONFIG_TYPES".equals(String.valueOf(NativeApi.call(config, INVENTORY, "getMode")))) {
            throw ServerAccess.denied("unsupported", "Native bus inventory is not a ghost configuration inventory");
        }
        int capacity = (int) NativeApi.number(NativeApi.call(part, UPGRADES, "getInstalledUpgrades",
                NativeApi.constant("appeng.core.definitions.AEItems", "CAPACITY_CARD")));
        if (capacity < 0 || capacity > 64) throw ServerAccess.denied("unsupported", "Unexpected native capacity-card count");
        // IOBusPart.availableSlots in AE2 19.2.17: min(config.size, 18 + 9 * installed capacity cards).
        int limit = Math.min((int) NativeApi.number(NativeApi.call(config, INVENTORY, "size")), 18 + 9 * capacity);
        int empty = -1;
        for (int slot = 0; slot < Math.min(limit, 128); slot++) {
            Object existing = NativeApi.call(config, INVENTORY, "getKey", slot);
            if (key.equals(existing)) return receipt(player, part, importing, slot, key, "no_change");
            if (empty < 0 && existing == null && NativeApi.truth(NativeApi.call(config, INVENTORY, "isAllowedIn", slot, key))) empty = slot;
        }
        if (empty < 0) throw ServerAccess.denied("configuration_full", "No unlocked empty native filter slot accepts this item");
        NativeApi.call(config, "appeng.util.ConfigInventory", "setStack", empty, Ae2ConfigurationAccess.generic(key, 0));
        Ae2ConfigurationAccess.savePart(part);
        return receipt(player, part, importing, empty, key, "applied");
    }

    private static JsonObject receipt(ServerPlayer player, Object part, boolean importing, int slot, Object expected, String status) {
        Object config = NativeApi.call(part, BUS, "getConfig");
        Object actual = NativeApi.call(config, INVENTORY, "getKey", slot);
        JsonObject result = new JsonObject(); result.addProperty("status", status);
        result.addProperty("bus_type", importing ? "import" : "export");
        result.addProperty("filter_slot", slot);
        result.addProperty("verified_configuration", expected.equals(actual));
        result.addProperty("filter_source", "native_ghost_config_inventory");
        result.addProperty("match_mode", "exact_include");
        if (NativeApi.is(actual, Ae2Access.ITEM)) result.add("filter", ResourceIdentity.item(
                (ItemStack) NativeApi.call(actual, Ae2Access.ITEM, "toStack", 1), player.registryAccess()));
        result.addProperty("redstone_mode", String.valueOf(NativeApi.call(part, BUS, "getRSMode")));
        result.addProperty("flow_verified", false);
        return result;
    }

    static ItemStack criterion(ServerPlayer player, JsonObject body) {
        ResourceLocation id = ResourceLocation.tryParse(ServerAccess.text(body, "item_id"));
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) throw ServerAccess.denied("unknown_item", "Filter item is not registered");
        JsonObject encoded = new JsonObject(); encoded.addProperty("id", id.toString()); encoded.addProperty("count", 1);
        if (body.has("components")) encoded.add("components", body.get("components").deepCopy());
        try {
            ItemStack stack = ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, player.registryAccess()), encoded).getOrThrow();
            if (stack.isEmpty()) throw new IllegalArgumentException("Empty criterion");
            return stack;
        } catch (RuntimeException invalid) { throw ServerAccess.denied("invalid_filter_components", "Native item codec rejected the filter criteria"); }
    }
}
