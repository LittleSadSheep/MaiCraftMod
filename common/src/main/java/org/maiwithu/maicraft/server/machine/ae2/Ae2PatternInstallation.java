// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.ae2;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.inventory.InventoryTransfer;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** Converts one genuine blank pattern and inserts into a native empty provider slot, with exact idempotency. */
final class Ae2PatternInstallation {
    private static final String HOST = "appeng.helpers.patternprovider.PatternProviderLogicHost";
    private static final String LOGIC = "appeng.helpers.patternprovider.PatternProviderLogic";
    private static final String HELPER = "appeng.api.crafting.PatternDetailsHelper";
    private Ae2PatternInstallation() {}

    static JsonObject install(ServerPlayer player, Object host, JsonObject body) {
        if (!NativeApi.is(host, HOST)) throw ServerAccess.denied("unsupported", "An actual pattern provider is required");
        Ae2PatternDefinition definition = Ae2PatternDefinition.resolve(player, body);
        String mode = definition.mode(), requestedId = definition.requestedId(), recipeId = definition.recipeId();
        ItemStack encoded = definition.pattern();
        Object logic = NativeApi.call(host, HOST, "getLogic");
        Object inventory = NativeApi.call(logic, LOGIC, "getPatternInv");
        int size = Math.min(128, (int) NativeApi.number(NativeApi.call(inventory, Ae2ConfigurationAccess.INTERNAL, "size")));
        int empty = -1;
        for (int slot = 0; slot < size; slot++) {
            ItemStack present = (ItemStack) NativeApi.call(inventory, Ae2ConfigurationAccess.INTERNAL, "getStackInSlot", slot);
            if (Ae2PatternDefinition.equivalent(present, encoded, mode)) return receipt(player, present, mode, requestedId, recipeId, slot, 0, "no_change", true);
            if (empty < 0 && present.isEmpty() && NativeApi.truth(NativeApi.call(inventory, Ae2ConfigurationAccess.INTERNAL, "isItemValid", slot, encoded))) empty = slot;
        }
        if (empty < 0) throw ServerAccess.denied("configuration_full", "No native empty pattern slot is available; existing patterns are preserved");
        int source = blankSlot(player);
        ItemStack blank = player.getInventory().getItem(source).copyWithCount(1);
        // Preserve the real blank's patch, then restore the authoritative encoded recipe component.
        // A blank's pre-existing data can never replace recipe-validated pattern IO.
        ItemStack completed = encoded.copy();
        completed.applyComponents(blank.getComponentsPatch()); completed.applyComponents(encoded.getComponentsPatch());
        ResourceIdentity.item(completed, player.registryAccess());
        if (NativeApi.call(null, HELPER, "decodePattern", completed, player.serverLevel()) == null) {
            throw ServerAccess.denied("invalid_pattern_components", "Blank-pattern components prevent a valid native encoded pattern");
        }
        ItemStack simulated = (ItemStack) NativeApi.call(inventory, Ae2ConfigurationAccess.INTERNAL, "insertItem", empty, completed.copy(), true);
        if (!simulated.isEmpty()) throw ServerAccess.denied("pattern_slot_rejected", "Native pattern slot rejected insertion");
        ItemStack reserved = player.getInventory().removeItem(source, 1);
        ItemStack remainder = (ItemStack) NativeApi.call(inventory, Ae2ConfigurationAccess.INTERNAL, "insertItem", empty, completed, false);
        int consumed = 1;
        if (!remainder.isEmpty()) {
            if (remainder.getCount() != 1 || !ItemStack.isSameItemSameComponents(remainder, completed)) {
                throw new IllegalStateException("Native pattern insertion returned an invalid remainder; effect is uncertain");
            }
            InventoryTransfer.restore(player, source, reserved); consumed = 0;
        }
        NativeApi.call(logic, LOGIC, "updatePatterns"); NativeApi.call(host, HOST, "saveChanges");
        player.getInventory().setChanged(); player.inventoryMenu.broadcastChanges();
        ItemStack actual = (ItemStack) NativeApi.call(inventory, Ae2ConfigurationAccess.INTERNAL, "getStackInSlot", empty);
        boolean verified = Ae2PatternDefinition.equivalent(actual, encoded, mode);
        return receipt(player, actual, mode, requestedId, recipeId, empty, consumed,
                consumed == 0 ? "no_change" : verified ? "applied" : "partial", verified);
    }

    private static int blankSlot(ServerPlayer player) {
        ItemStack blank = Ae2ConfigurationAccess.item("BLANK_PATTERN");
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (!item.isEmpty() && item.is(blank.getItem())) return slot;
        }
        throw ServerAccess.denied("requires_item", "A real ae2:blank_pattern is required");
    }

    private static JsonObject receipt(ServerPlayer player, ItemStack pattern, String mode, String requested, String recipeId,
                                      int slot, int consumed, String status, boolean verified) {
        JsonObject result = new JsonObject(); result.addProperty("status", status); result.addProperty("mode", mode);
        result.addProperty("recipe_id", recipeId); if (!recipeId.equals(requested)) result.addProperty("requested_recipe_id", requested);
        result.addProperty("pattern_slot", slot); result.addProperty("blank_patterns_consumed", consumed);
        result.addProperty("verified_configuration", verified); result.add("pattern", ResourceIdentity.item(pattern, player.registryAccess()));
        result.addProperty("recipe_execution_verified", false); return result;
    }
}
