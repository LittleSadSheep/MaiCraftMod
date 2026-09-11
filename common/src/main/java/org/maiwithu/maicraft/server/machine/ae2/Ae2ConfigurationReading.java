// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.ae2;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.inventory.Ae2Access;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerMachineConfiguration;

/** Current AE2 settings readback. No insert/set/update/save, blank stock lookup, or interaction dispatch. */
final class Ae2ConfigurationReading {
    private Ae2ConfigurationReading() {}

    static JsonObject pattern(ServerPlayer player, Object host, JsonObject body) {
        String provider = "appeng.helpers.patternprovider.PatternProviderLogicHost";
        if (!NativeApi.is(host, provider)) return ServerMachineConfiguration.unknown("not_a_pattern_provider");
        Ae2PatternDefinition expected = Ae2PatternDefinition.resolve(player, body);
        Object logic = NativeApi.call(host, provider, "getLogic");
        Object inventory = NativeApi.call(logic, "appeng.helpers.patternprovider.PatternProviderLogic", "getPatternInv");
        int count = (int) NativeApi.number(NativeApi.call(inventory, Ae2ConfigurationAccess.INTERNAL, "size"));
        JsonObject result = new JsonObject(); result.addProperty("mode", expected.mode()); result.addProperty("recipe_id", expected.recipeId());
        if (!expected.recipeId().equals(expected.requestedId())) result.addProperty("requested_recipe_id", expected.requestedId());
        for (int slot = 0; slot < Math.min(128, count); slot++) {
            ItemStack actual = (ItemStack) NativeApi.call(inventory, Ae2ConfigurationAccess.INTERNAL, "getStackInSlot", slot);
            if (!Ae2PatternDefinition.equivalent(actual, expected.pattern(), expected.mode())) continue;
            if (NativeApi.call(null, "appeng.api.crafting.PatternDetailsHelper", "decodePattern", actual, player.serverLevel()) == null) continue;
            result.addProperty("pattern_slot", slot); result.add("pattern", ResourceIdentity.item(actual, player.registryAccess()));
            result.addProperty("verified_configuration", true); return result;
        }
        result.addProperty("verified_configuration", false);
        if (count > 128) result.addProperty("unknown_reason", "pattern_scan_limit");
        return result;
    }

    static JsonObject bus(ServerPlayer player, Object part, JsonObject body) {
        boolean importing = NativeApi.is(part, "appeng.parts.automation.ImportBusPart");
        if (!importing && !NativeApi.is(part, "appeng.parts.automation.ExportBusPart")) return ServerMachineConfiguration.unknown("not_an_io_bus");
        String upgrades = "appeng.api.upgrades.IUpgradeableObject", inventoryApi = "appeng.helpers.externalstorage.GenericStackInv";
        boolean fuzzy = NativeApi.truth(NativeApi.call(part, upgrades, "isUpgradedWith", NativeApi.constant("appeng.core.definitions.AEItems", "FUZZY_CARD")));
        boolean inverted = NativeApi.truth(NativeApi.call(part, upgrades, "isUpgradedWith", NativeApi.constant("appeng.core.definitions.AEItems", "INVERTER_CARD")));
        Object expected = NativeApi.call(null, Ae2Access.ITEM, "of", Ae2BusConfiguration.criterion(player, body));
        Object config = NativeApi.call(part, "appeng.parts.automation.IOBusPart", "getConfig");
        if (!"CONFIG_TYPES".equals(String.valueOf(NativeApi.call(config, inventoryApi, "getMode")))) {
            return ServerMachineConfiguration.unknown("not_a_ghost_configuration_inventory");
        }
        int capacity = (int) NativeApi.number(NativeApi.call(part, upgrades, "getInstalledUpgrades", NativeApi.constant("appeng.core.definitions.AEItems", "CAPACITY_CARD")));
        if (capacity < 0 || capacity > 64) return ServerMachineConfiguration.unknown("native_capacity_unavailable");
        int count = Math.min((int) NativeApi.number(NativeApi.call(config, inventoryApi, "size")), 18 + 9 * capacity);
        JsonObject result = new JsonObject(); result.addProperty("bus_type", importing ? "import" : "export");
        result.addProperty("fuzzy", fuzzy); result.addProperty("inverted", inverted);
        result.addProperty("match_mode", fuzzy ? "fuzzy" : inverted ? "exclude" : "exact_include");
        for (int slot = 0; slot < Math.min(count, 128); slot++) {
            Object actual = NativeApi.call(config, inventoryApi, "getKey", slot);
            if (!expected.equals(actual)) continue;
            result.addProperty("filter_slot", slot); result.add("filter", ResourceIdentity.item(
                    (ItemStack) NativeApi.call(actual, Ae2Access.ITEM, "toStack", 1), player.registryAccess()));
            result.addProperty("verified_configuration", !fuzzy && !inverted); return result;
        }
        result.addProperty("verified_configuration", false);
        if (count > 128) result.addProperty("unknown_reason", "filter_scan_limit");
        return result;
    }
}
