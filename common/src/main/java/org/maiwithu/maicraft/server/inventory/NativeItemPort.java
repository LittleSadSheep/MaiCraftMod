// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.inventory;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** Sided insertion/extraction, always retaining the native ItemStack data components. */
public interface NativeItemPort {
    String API = "net.neoforged.neoforge.items.IItemHandler";
    int slots();
    ItemStack stack(int slot);
    int limit(int slot);
    boolean valid(int slot, ItemStack stack);
    ItemStack insert(int slot, ItemStack stack, boolean simulate);
    ItemStack extract(int slot, int amount, boolean simulate);

    static Object capability(ServerLevel level, BlockPos pos, Direction side, String medium) {
        Object token = switch (medium) {
            case "items" -> NativeApi.constant("net.neoforged.neoforge.capabilities.Capabilities$ItemHandler", "BLOCK");
            case "fluids" -> NativeApi.constant("net.neoforged.neoforge.capabilities.Capabilities$FluidHandler", "BLOCK");
            case "energy" -> NativeApi.constant("net.neoforged.neoforge.capabilities.Capabilities$EnergyStorage", "BLOCK");
            case "chemicals", "mekanism_energy" -> NativeApi.call(NativeApi.constant(
                    "mekanism.common.capabilities.Capabilities", medium.equals("chemicals") ? "CHEMICAL" : "STRICT_ENERGY"),
                    "mekanism.common.capabilities.MultiTypeCapability", "block");
            default -> throw new IllegalArgumentException(medium);
        };
        return NativeApi.call(level, null, "getCapability", token, pos, side);
    }

    static NativeItemPort find(ServerLevel level, BlockPos pos, Direction side) {
        if (level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity loot && loot.getLootTable() != null) {
            throw ServerAccess.denied("loot_unopened", "Open the loot container before observing or transferring its contents");
        }
        if (NativeApi.present(API)) {
            Object handler = capability(level, pos, side, "items");
            return handler == null ? null : new CapabilityPort(handler);
        }
        if (level.getBlockEntity(pos) instanceof Container container) return new ContainerPort(container, side);
        return null;
    }

    record CapabilityPort(Object handler) implements NativeItemPort {
        @Override public int slots() { return (int) NativeApi.number(NativeApi.call(handler, API, "getSlots")); }
        @Override public ItemStack stack(int slot) { return (ItemStack) NativeApi.call(handler, API, "getStackInSlot", slot); }
        @Override public int limit(int slot) { return (int) NativeApi.number(NativeApi.call(handler, API, "getSlotLimit", slot)); }
        @Override public boolean valid(int slot, ItemStack stack) {
            return NativeApi.truth(NativeApi.call(handler, API, "isItemValid", slot, stack));
        }
        @Override public ItemStack insert(int slot, ItemStack stack, boolean simulate) {
            return (ItemStack) NativeApi.call(handler, API, "insertItem", slot, stack, simulate);
        }
        @Override public ItemStack extract(int slot, int amount, boolean simulate) {
            return (ItemStack) NativeApi.call(handler, API, "extractItem", slot, amount, simulate);
        }
    }

    record ContainerPort(Container container, Direction side) implements NativeItemPort {
        private boolean exposed(int slot) {
            return !(container instanceof WorldlyContainer sided)
                    || Arrays.stream(sided.getSlotsForFace(side)).anyMatch(candidate -> candidate == slot);
        }
        @Override public int slots() { return container.getContainerSize(); }
        @Override public ItemStack stack(int slot) { return exposed(slot) ? container.getItem(slot) : ItemStack.EMPTY; }
        @Override public int limit(int slot) { return container.getMaxStackSize(); }
        @Override public boolean valid(int slot, ItemStack stack) {
            return exposed(slot) && container.canPlaceItem(slot, stack)
                    && (!(container instanceof WorldlyContainer sided) || sided.canPlaceItemThroughFace(slot, stack, side));
        }
        @Override public ItemStack insert(int slot, ItemStack offered, boolean simulate) {
            if (!valid(slot, offered)) return offered.copy();
            ItemStack existing = container.getItem(slot);
            if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, offered)) return offered.copy();
            int accepted = Math.min(offered.getCount(), Math.max(0,
                    Math.min(limit(slot), offered.getMaxStackSize()) - existing.getCount()));
            if (!simulate && accepted > 0) {
                container.setItem(slot, offered.copyWithCount(existing.getCount() + accepted));
                container.setChanged();
            }
            return offered.copyWithCount(offered.getCount() - accepted);
        }
        @Override public ItemStack extract(int slot, int amount, boolean simulate) {
            ItemStack existing = stack(slot);
            if (existing.isEmpty() || container instanceof WorldlyContainer sided
                    && !sided.canTakeItemThroughFace(slot, existing, side)) return ItemStack.EMPTY;
            int count = Math.min(amount, existing.getCount());
            if (simulate) return existing.copyWithCount(count);
            ItemStack result = container.removeItem(slot, count);
            container.setChanged();
            return result;
        }
    }
}
