// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import static org.maiwithu.maicraft.core.task.build.CreativeBuildInventory.Kind.*;

public final class CreativeBuildInventoryTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        reuseAndEviction(); preciseOwnership(); confirmedMoves();
        System.out.println("CreativeBuildInventoryTest: passed");
    }

    private static void reuseAndEviction() {
        var cache = new CreativeBuildInventory();
        var slots = slots();
        for (int i = 0; i < 9; i++) slots.set(i, new ItemStack(Items.DIAMOND));
        check(cache.choose(Items.STONE, slots, next(Map.of())).slot() == 9,
                "a full user hotbar still permits creative supply in the main inventory");
        create(cache, slots, 9, new ItemStack(Items.STONE, 23));
        var future = next(Map.of(Items.STONE, 0, Items.OAK_LOG, 3, Items.GLASS, 90));
        for (int i = 0; i < 100; i++)
            check(cache.choose(Items.STONE, slots, future).kind() == EXISTING,
                    "repeated placements reuse the retained material without eviction or creation");
        check(cache.owned(slots).size() == 1 && cache.unused(slots, future).kind() == BLOCKED,
                "needed materials remain cached across placements");
        create(cache, slots, 10, new ItemStack(Items.OAK_LOG));
        create(cache, slots, 11, new ItemStack(Items.GLASS));
        for (int i = 12; i < 36; i++) slots.set(i, new ItemStack(Items.DIAMOND));
        check(cache.choose(Items.BRICKS, slots, future).slot() == 11,
                "capacity pressure evicts the most distant future use, preserving near-term materials");
        var noStone = next(Map.of(Items.OAK_LOG, 3, Items.GLASS, 90));
        check(cache.choose(Items.BRICKS, slots, noStone).slot() == 9,
                "finished material is evicted before even the most distant useful material");
        check(cache.unused(slots, noStone).slot() == 9, "finished material can be proactively cleaned");
        var unowned = new CreativeBuildInventory();
        check(unowned.choose(Items.BRICKS, slots, future).kind() == BLOCKED,
                "a full inventory containing only user items never permits deletion");
        check(unowned.choose(Items.STONE, slots, future).kind() == EXISTING && unowned.owned(slots).isEmpty(),
                "using a user's matching material never claims cleanup ownership");
    }

    private static void preciseOwnership() {
        var cache = new CreativeBuildInventory(); var slots = slots();
        var customized = new ItemStack(Items.STONE, 7);
        customized.set(DataComponents.MAX_STACK_SIZE, 7);
        create(cache, slots, 0, customized);
        var signature = cache.owned(slots).getFirst().expected();
        check(signature.getCount() == 7 && signature.getMaxStackSize() == 7,
                "ownership preserves actual stack limits and counts, without assuming 64");
        signature.setCount(1);
        check(cache.owned(slots).getFirst().expected().getCount() == 7, "returned stacks cannot change ownership");
        slots.get(0).shrink(1);
        check(cache.owned(slots).isEmpty(), "partial stack changes revoke deletion rights");
        create(cache, slots, 1, new ItemStack(Items.OAK_LOG));
        slots.get(1).set(DataComponents.CUSTOM_NAME, Component.literal("User's log"));
        check(cache.owned(slots).isEmpty(), "component changes revoke deletion rights");
        check(!cache.confirmedCreated(2, new ItemStack(Items.GLASS), slots),
                "unconfirmed or mismatched supplies do not become owned");
        slots.set(36, new ItemStack(Items.GLASS));
        check(!cache.confirmedCreated(36, slots.get(36), slots), "slots outside the main 36 are never claimed");
        create(cache, slots, 2, new ItemStack(Items.STONE));
        slots.set(3, slots.get(2)); slots.set(2, ItemStack.EMPTY);
        check(cache.owned(slots).isEmpty(), "unobserved moves are not guessed from matching item identities");
    }

    private static void confirmedMoves() {
        var cache = new CreativeBuildInventory(); var slots = slots();
        create(cache, slots, 15, new ItemStack(Items.STONE, 31));
        slots.set(2, new ItemStack(Items.DIAMOND, 2));
        swap(cache, slots, 15, 2);
        check(cache.owned(slots).size() == 1 && cache.owned(slots).getFirst().slot() == 2,
                "confirmed main-to-hotbar swaps move ownership but leave swapped user supplies unowned");
        create(cache, slots, 16, new ItemStack(Items.GLASS, 5));
        swap(cache, slots, 16, 2);
        check(cache.owned(slots).getFirst().expected().is(Items.GLASS)
                        && cache.owned(slots).get(1).expected().is(Items.STONE),
                "swapping two owned slots exchanges both exact signatures");
        slots.get(16).shrink(1);
        swap(cache, slots, 16, 3);
        check(cache.owned(slots).size() == 1 && cache.owned(slots).getFirst().slot() == 2,
                "a stack modified before its confirmed swap cannot inherit stale ownership");
        ItemStack before = slots.get(2).copy();
        slots.set(4, before.copy()); slots.get(4).shrink(1); slots.set(2, ItemStack.EMPTY);
        cache.confirmedSwap(2, 4, before, ItemStack.EMPTY, slots);
        check(cache.owned(slots).isEmpty(), "a destination changed after swapping is not safe to delete");
    }

    private static void create(CreativeBuildInventory cache, List<ItemStack> slots, int slot, ItemStack stack) {
        slots.set(slot, stack.copy());
        check(cache.confirmedCreated(slot, stack, slots), "a confirmed creative supply should be tracked");
    }
    private static void swap(CreativeBuildInventory cache, List<ItemStack> slots, int a, int b) {
        var first = slots.get(a).copy(); var second = slots.get(b).copy();
        slots.set(a, second.copy()); slots.set(b, first.copy());
        cache.confirmedSwap(a, b, first, second, slots);
    }
    private static List<ItemStack> slots() { return new ArrayList<>(Collections.nCopies(41, ItemStack.EMPTY)); }
    private static ToIntFunction<Item> next(Map<Item, Integer> distances) {
        return item -> distances.getOrDefault(item, CreativeBuildInventory.NO_FUTURE_USE);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
