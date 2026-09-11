// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.inventory;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** Finds a legal slot through the same sided native simulation used by the eventual transaction. */
public final class InventoryQuote {
    private InventoryQuote() {}

    public static JsonObject inspect(ServerPlayer player, JsonObject body) {
        BlockPos pos = ServerAccess.position(body.getAsJsonObject("position"));
        Direction side = ServerAccess.side(body);
        int playerSlot = ServerAccess.integer(body, "player_slot", 0, 35);
        int requested = ServerAccess.integer(body, "amount", 1, 64);
        String mode = ServerAccess.text(body, "mode");
        if (!mode.equals("deposit") && !mode.equals("withdraw")) throw ServerAccess.denied("invalid_argument", "Unknown mode");
        ServerAccess.preview(player, pos);
        NativeItemPort port = NativeItemPort.find(player.serverLevel(), pos, side);
        JsonObject result = new JsonObject();
        result.addProperty("tick", player.serverLevel().getGameTime());
        result.addProperty("player_slot", playerSlot);
        result.addProperty("effect", "not_applied");
        result.addProperty("interaction_event_permission", "checked_by_transfer_before_mutation");
        if (port == null) { result.addProperty("status", "unsupported"); return result; }
        ItemStack playerStack = player.getInventory().getItem(playerSlot);
        if (mode.equals("deposit") && playerStack.isEmpty()) {
            result.addProperty("status", "empty"); return result;
        }
        String expected = body.has("resource_id") ? ServerAccess.text(body, "resource_id") : null;
        boolean unknownIdentity = false;
        for (int slot = 0; slot < Math.min(128, port.slots()); slot++) {
            ItemStack sample = mode.equals("deposit") ? playerStack : port.stack(slot);
            if (sample.isEmpty()) continue;
            if (body.has("expected_item_id") && !ServerAccess.text(body, "expected_item_id").equals(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(sample.getItem()).toString())) continue;
            JsonObject resourceIdentity;
            try { resourceIdentity = ResourceIdentity.item(sample, player.registryAccess()); }
            catch (IllegalArgumentException incomplete) { unknownIdentity = true; continue; }
            String identity = ResourceIdentity.key(resourceIdentity);
            if (expected != null && !expected.equals(identity)) continue;
            int amount;
            if (mode.equals("deposit")) {
                ItemStack offer = sample.copyWithCount(Math.min(requested, sample.getCount()));
                ItemStack remainder = port.insert(slot, offer.copy(), true);
                if (!remainder.isEmpty() && !ItemStack.isSameItemSameComponents(offer, remainder)) {
                    throw new IllegalStateException("Native insertion simulation changed resource identity");
                }
                amount = offer.getCount() - remainder.getCount();
            } else {
                int maximum = Math.min(requested, InventoryTransfer.room(playerStack, sample));
                if (maximum == 0) continue;
                ItemStack extracted = port.extract(slot, maximum, true);
                if (!extracted.isEmpty() && !ItemStack.isSameItemSameComponents(sample, extracted)) {
                    throw new IllegalStateException("Native extraction simulation changed resource identity");
                }
                amount = extracted.getCount();
            }
            if (amount > requested || amount < 0) throw new IllegalStateException("Native simulation returned an invalid amount");
            if (amount == 0) continue;
            result.addProperty("status", "ready");
            result.addProperty("slot", slot);
            result.addProperty("amount", amount);
            result.addProperty("resource_id", identity);
            result.add("identity", resourceIdentity);
            result.addProperty("requires_revalidation", true);
            return result;
        }
        result.addProperty("status", unknownIdentity ? "unknown" : mode.equals("deposit") ? "full" : "empty");
        result.addProperty("unknown_component_identities", unknownIdentity);
        result.addProperty("truncated", port.slots() > 128);
        return result;
    }
}
