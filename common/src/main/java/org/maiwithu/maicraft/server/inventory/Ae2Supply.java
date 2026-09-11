// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.inventory;

import com.google.gson.JsonObject;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** Uses AE2's powered player-source transaction, retaining components and charging native network energy. */
public final class Ae2Supply {
    private Ae2Supply() {}

    public static JsonObject execute(ServerPlayer player, JsonObject body) {
        BlockPos pos = ServerAccess.position(body.getAsJsonObject("position"));
        Direction side = ServerAccess.side(body);
        int slot = ServerAccess.integer(body, "player_slot", 0, 35);
        int amount = ServerAccess.integer(body, "amount", 1, 64);
        String mode = ServerAccess.text(body, "mode");
        if (!mode.equals("deposit") && !mode.equals("withdraw")) throw ServerAccess.denied("invalid_argument", "Unknown mode");
        Ae2Access access = Ae2Access.terminal(player, pos, side, true);
        Object key;
        ItemStack sample;
        if (mode.equals("deposit")) {
            sample = player.getInventory().getItem(slot).copy();
            if (sample.isEmpty()) throw ServerAccess.denied("empty_source", "Player slot is empty");
            key = NativeApi.call(null, Ae2Access.ITEM, "of", sample);
        } else {
            String identity = ServerAccess.text(body, "resource_id");
            key = find(access, player, identity);
            sample = (ItemStack) NativeApi.call(key, Ae2Access.ITEM, "toStack", 1);
        }
        String identity = ResourceIdentity.key(ResourceIdentity.item(sample, player.registryAccess()));
        if (body.has("resource_id") && !identity.equals(ServerAccess.text(body, "resource_id"))) {
            throw ServerAccess.denied("source_changed", "Resource identity differs from the requested key");
        }
        boolean deposit = mode.equals("deposit");
        int bounded = Math.min(amount, deposit ? sample.getCount() : InventoryTransfer.room(player.getInventory().getItem(slot), sample));
        long simulated = access.powered(key, bounded, deposit, true);
        validateAmount(simulated, bounded);
        long actual = 0;
        if (simulated > 0) {
            if (deposit) {
                ItemStack reserved = player.getInventory().removeItem(slot, (int) simulated);
                actual = access.powered(key, reserved.getCount(), true, false);
                validateAmount(actual, reserved.getCount());
                InventoryTransfer.restore(player, slot, reserved.copyWithCount(reserved.getCount() - (int) actual));
            } else {
                actual = access.powered(key, simulated, false, false);
                validateAmount(actual, simulated);
                // Creation follows a confirmed native extraction of this exact key and amount.
                InventoryTransfer.restore(player, slot, (ItemStack) NativeApi.call(key, Ae2Access.ITEM, "toStack", (int) actual));
            }
        }
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        JsonObject result = new JsonObject();
        result.addProperty("status", actual == 0 ? "no_change" : actual < amount ? "partial" : "applied");
        result.addProperty("resource_id", identity);
        result.addProperty("transferred", actual);
        result.addProperty("requested", amount);
        result.addProperty("membership", access.membership());
        result.addProperty("tick", player.serverLevel().getGameTime());
        return result;
    }

    private static Object find(Ae2Access access, ServerPlayer player, String identity) {
        int checked = 0;
        for (Object raw : (Iterable<?>) access.cachedInventory()) {
            if (++checked > 4096) throw ServerAccess.denied("inventory_limit", "Key was not found within the bounded native inventory");
            Object key = ((Map.Entry<?, ?>) raw).getKey();
            if (!NativeApi.is(key, Ae2Access.ITEM)) continue;
            ItemStack sample = (ItemStack) NativeApi.call(key, Ae2Access.ITEM, "toStack", 1);
            if (identity.equals(ResourceIdentity.key(ResourceIdentity.item(sample, player.registryAccess())))) return key;
        }
        throw ServerAccess.denied("resource_unavailable", "Exact component-sensitive key is absent from AE2");
    }

    private static void validateAmount(long actual, long maximum) {
        if (actual < 0 || actual > maximum) throw new IllegalStateException("AE2 returned an invalid transfer amount; effect is uncertain");
    }
}
