// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.inventory;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** One real player slot and one native machine slot; no remote block-to-block teleportation. */
public final class InventoryTransfer {
    private InventoryTransfer() {}

    public static JsonObject execute(ServerPlayer player, JsonObject body) {
        BlockPos pos = ServerAccess.position(body.getAsJsonObject("position"));
        Direction side = ServerAccess.side(body);
        String mode = ServerAccess.text(body, "mode");
        if (!mode.equals("deposit") && !mode.equals("withdraw")) throw ServerAccess.denied("invalid_argument", "Unknown mode");
        int playerSlot = ServerAccess.integer(body, "player_slot", 0, 35);
        int slot = ServerAccess.integer(body, "slot", 0, 4095);
        int requested = ServerAccess.integer(body, "amount", 1, 64);
        ServerAccess.check(player, pos, true);
        NativeItemPort port = NativeItemPort.find(player.serverLevel(), pos, side);
        if (port == null) throw ServerAccess.denied("unsupported", "No native item port on this side");
        if (slot >= port.slots()) throw ServerAccess.denied("invalid_slot", "Machine slot is absent");
        ItemStack source = mode.equals("deposit") ? player.getInventory().getItem(playerSlot) : port.stack(slot);
        if (source.isEmpty()) throw ServerAccess.denied("empty_source", "Source slot is empty");
        String identity = ResourceIdentity.key(ResourceIdentity.item(source, player.registryAccess()));
        if (body.has("expected_identity") && !identity.equals(ServerAccess.text(body, "expected_identity"))) {
            throw ServerAccess.denied("source_changed", "Source resource identity changed");
        }
        int moved = mode.equals("deposit") ? deposit(player, playerSlot, port, slot, requested)
                : withdraw(player, playerSlot, port, slot, requested);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        JsonObject result = new JsonObject();
        result.addProperty("status", moved == 0 ? "no_change" : moved < requested ? "partial" : "applied");
        result.addProperty("requested", requested);
        result.addProperty("transferred", moved);
        result.addProperty("resource_id", identity);
        result.addProperty("tick", player.serverLevel().getGameTime());
        return result;
    }

    private static int deposit(ServerPlayer player, int playerSlot, NativeItemPort port, int slot, int requested) {
        ItemStack current = player.getInventory().getItem(playerSlot);
        ItemStack offer = current.copyWithCount(Math.min(requested, current.getCount()));
        ItemStack simulated = port.insert(slot, offer.copy(), true);
        validateRemainder(offer, simulated);
        int allowed = offer.getCount() - simulated.getCount();
        if (allowed == 0) return 0;
        // Reserve genuine items before invoking the native writer. An exception leaves an unknown effect,
        // never a fabricated rollback of a possibly accepted stack.
        ItemStack reserved = player.getInventory().removeItem(playerSlot, allowed);
        ItemStack remainder = port.insert(slot, reserved.copy(), false);
        validateRemainder(reserved, remainder);
        restore(player, playerSlot, remainder);
        return reserved.getCount() - remainder.getCount();
    }

    private static int withdraw(ServerPlayer player, int playerSlot, NativeItemPort port, int slot, int requested) {
        ItemStack sample = port.stack(slot).copy();
        int room = room(player.getInventory().getItem(playerSlot), sample);
        if (room == 0) return 0;
        ItemStack simulated = port.extract(slot, Math.min(requested, room), true);
        if (simulated.isEmpty()) return 0;
        if (!ItemStack.isSameItemSameComponents(sample, simulated) || simulated.getCount() > room
                || simulated.getCount() > requested) throw new IllegalStateException("Native extraction simulation violated its contract");
        ItemStack extracted = port.extract(slot, simulated.getCount(), false);
        if (extracted.isEmpty()) return 0;
        // Even a misbehaving provider's returned real stack must be retained, not replaced by the sample.
        restore(player, playerSlot, extracted);
        if (!ItemStack.isSameItemSameComponents(sample, extracted) || extracted.getCount() > simulated.getCount()) {
            throw new IllegalStateException("Native extraction changed identity or amount; inspect the player inventory");
        }
        return extracted.getCount();
    }

    public static int room(ItemStack existing, ItemStack sample) {
        return existing.isEmpty() ? sample.getMaxStackSize() : ItemStack.isSameItemSameComponents(existing, sample)
                ? Math.max(0, Math.min(existing.getMaxStackSize(), sample.getMaxStackSize()) - existing.getCount()) : 0;
    }

    public static void restore(ServerPlayer player, int slot, ItemStack items) {
        if (items.isEmpty()) return;
        ItemStack remaining = items.copy();
        ItemStack existing = player.getInventory().getItem(slot);
        int count = Math.min(remaining.getCount(), room(existing, remaining));
        if (count > 0) {
            player.getInventory().setItem(slot, remaining.copyWithCount(existing.getCount() + count));
            remaining.shrink(count);
        }
        if (!remaining.isEmpty()) player.getInventory().placeItemBackInInventory(remaining);
    }

    private static void validateRemainder(ItemStack offer, ItemStack remainder) {
        if (remainder.getCount() < 0 || remainder.getCount() > offer.getCount()
                || !remainder.isEmpty() && !ItemStack.isSameItemSameComponents(offer, remainder)) {
            throw new IllegalStateException("Native insertion violated its remainder contract; effect is uncertain");
        }
    }
}
