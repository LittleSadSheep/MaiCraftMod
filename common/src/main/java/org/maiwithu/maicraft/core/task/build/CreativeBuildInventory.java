// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToIntFunction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 记住创造模式施工时临时拿出的材料，避免清理时动到玩家原本的物品。这里只做选择和记账，不直接改背包。
 * 记录范围是普通三十六格，不包括盔甲和副手；数量、组件或位置出现未确认变化，就放弃清理权。
 */
public final class CreativeBuildInventory {
    public static final int NO_FUTURE_USE = Integer.MAX_VALUE;
    private static final int MAIN_SLOTS = 36;
    public enum Kind { EXISTING, EMPTY, EVICT, BLOCKED }
    public record Selection(Kind kind, int slot, ItemStack expected) {
        public Selection { expected = expected.copy(); }
        @Override public ItemStack expected() { return expected.copy(); }
    }

    private final Map<Integer, ItemStack> owned = new TreeMap<>();

    /**
     * 先找已有同种材料，其次找空格；背包满了，只能换掉本任务仍能确认归属的一格材料。
     * 要换时优先选以后最晚用到或不再需要的材料；玩家原有物品不会成为候选。
     */
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

    /**
     * 挑出一格后续计划完全不再需要的自有材料，供调用方逐格清理；没有可清理项时返回 BLOCKED。
     */
    public Selection unused(List<ItemStack> inventory, ToIntFunction<Item> nextUse) {
        reconcile(inventory);
        for (var entry : owned.entrySet()) {
            if (nextUse.applyAsInt(entry.getValue().getItem()) == NO_FUTURE_USE)
                return choice(Kind.EVICT, entry.getKey(), entry.getValue());
        }
        return blocked();
    }

    /**
     * 调用者必须先确认一次原版“空格变成指定物品”的动作。这里再核对槽位、数量和组件一致，才登记归属。
     */
    public boolean confirmedCreated(int slot, ItemStack expected, List<ItemStack> inventory) {
        reconcile(inventory);
        if (!valid(slot, inventory) || expected.isEmpty() || !same(expected, inventory.get(slot))) return false;
        owned.put(slot, expected.copy());
        return true;
    }

    /**
     * 原版换槽动作已确认后，把自有材料的归属一起转到新槽位。交换前的物品必须是提交动作时留的副本。
     * 先转移再统一核对，避免把正常换到快捷栏误当成玩家移动而丢掉记录。
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

    // 只有登记值、换槽前的值和目的槽位的现值三者一致，才把归属跟到新槽位。
    private void transfer(ItemStack signature, ItemStack before, int destination, List<ItemStack> inventory) {
        if (signature != null && same(signature, before) && same(signature, inventory.get(destination)))
            owned.put(destination, signature);
    }

    /**
     * 核对登记物品还在原槽位且数量和组件完全一致；有变化就删记录，不猜新位置，也不认领相似物品。
     */
    public void reconcile(List<ItemStack> inventory) {
        owned.entrySet().removeIf(entry -> !valid(entry.getKey(), inventory)
                || !same(entry.getValue(), inventory.get(entry.getKey())));
    }

    /**
     * 返回仍能确认归属的材料副本，供结束时逐格核对后清理；不能用返回列表直接假定它们之后也没变。
     */
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
