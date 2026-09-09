// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToIntFunction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Task-owned creative materials; decisions never mutate the inventory or claim existing supplies. */
public final class CreativeBuildInventory {
    public static final int NO_FUTURE_USE = Integer.MAX_VALUE;
    private static final int MAIN_SLOTS = 36;
    public enum Kind { EXISTING, EMPTY, EVICT, BLOCKED }
    public record Selection(Kind kind, int slot, ItemStack expected) {
        public Selection { expected = expected.copy(); }
        @Override public ItemStack expected() { return expected.copy(); }
    }

    private final Map<Integer, ItemStack> owned = new TreeMap<>();

    /** nextUse is a remaining-plan distance; NO_FUTURE_USE means this material is finished. */
    public Selection choose(Item wanted, List<ItemStack> inventory, ToIntFunction<Item> nextUse) {
        reconcile(inventory);
        int empty = -1;
        for (int slot = 0; slot < limit(inventory); slot++) {
            ItemStack stack = inventory.get(slot);
            if (!stack.isEmpty() && stack.is(wanted)) return choice(Kind.EXISTING, slot, stack);
            if (empty < 0 && stack.isEmpty()) empty = slot;
        }
        if (empty >= 0) return choice(Kind.EMPTY, empty, ItemStack.EMPTY);
        int candidate = -1, furthest = Integer.MIN_VALUE;
        for (var entry : owned.entrySet()) {
            int distance = nextUse.applyAsInt(entry.getValue().getItem());
            if (distance > furthest) { candidate = entry.getKey(); furthest = distance; }
        }
        return candidate < 0 ? blocked() : choice(Kind.EVICT, candidate, owned.get(candidate));
    }

    /** Cleanup can be deferred until a native mutation is available without discarding needed stacks. */
    public Selection unused(List<ItemStack> inventory, ToIntFunction<Item> nextUse) {
        reconcile(inventory);
        for (var entry : owned.entrySet()) {
            if (nextUse.applyAsInt(entry.getValue().getItem()) == NO_FUTURE_USE)
                return choice(Kind.EVICT, entry.getKey(), entry.getValue());
        }
        return blocked();
    }

    /** Call only after the native empty-slot -> expected-stack creative receipt was confirmed. */
    public boolean confirmedCreated(int slot, ItemStack expected, List<ItemStack> inventory) {
        reconcile(inventory);
        if (!valid(slot, inventory) || expected.isEmpty() || !same(expected, inventory.get(slot))) return false;
        owned.put(slot, expected.copy());
        return true;
    }

    /**
     * Call once per confirmed swap, before reconciling its post-swap inventory. The before values
     * must be copies captured when that native swap was submitted. User supplies remain unowned.
     */
    public void confirmedSwap(int first, int second, ItemStack firstBefore, ItemStack secondBefore,
                              List<ItemStack> inventory) {
        if (first == second || !valid(first, inventory) || !valid(second, inventory)) {
            reconcile(inventory);
            return;
        }
        ItemStack firstOwned = owned.remove(first), secondOwned = owned.remove(second);
        transfer(firstOwned, firstBefore, second, inventory);
        transfer(secondOwned, secondBefore, first, inventory);
        reconcile(inventory);
    }

    private void transfer(ItemStack signature, ItemStack before, int destination, List<ItemStack> inventory) {
        if (signature != null && same(signature, before) && same(signature, inventory.get(destination)))
            owned.put(destination, signature);
    }

    /** Any count/component change or unobserved move relinquishes cleanup rights, without guessing. */
    public void reconcile(List<ItemStack> inventory) {
        owned.entrySet().removeIf(entry -> !valid(entry.getKey(), inventory)
                || !same(entry.getValue(), inventory.get(entry.getKey())));
    }

    /** Exact copies suitable for guarded task-end cleanup; armour and offhand are never returned. */
    public List<Selection> owned(List<ItemStack> inventory) {
        reconcile(inventory);
        List<Selection> result = new ArrayList<>();
        owned.forEach((slot, stack) -> result.add(choice(Kind.EVICT, slot, stack)));
        return List.copyOf(result);
    }

    private static boolean same(ItemStack first, ItemStack second) {
        return first.getCount() == second.getCount() && ItemStack.isSameItemSameComponents(first, second);
    }
    private static int limit(List<ItemStack> inventory) { return Math.min(MAIN_SLOTS, inventory.size()); }
    private static boolean valid(int slot, List<ItemStack> inventory) { return slot >= 0 && slot < limit(inventory); }
    private static Selection choice(Kind kind, int slot, ItemStack expected) { return new Selection(kind, slot, expected); }
    private static Selection blocked() { return choice(Kind.BLOCKED, -1, ItemStack.EMPTY); }
}
