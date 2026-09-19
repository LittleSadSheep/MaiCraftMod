// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.enchant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.maiwithu.maicraft.core.task.container.ContainerTransferTaskRecord.Move;

/** 附魔前冻结一件装备或一本书的来源，并为附魔书保留空位；所有搬运都使用真实菜单槽和完整物品组件。 */
final class EnchantInventory {
    private final LocalPlayer player;
    final ItemStack input, lapisKind;
    final int lapisNeeded;
    private final ItemStack sourceStack;
    private final int sourceInventorySlot, outputInventorySlot, inputBefore, lapisBefore;
    private ItemStack result = ItemStack.EMPTY;
    private int resultBefore;

    private EnchantInventory(LocalPlayer player, int source, int output, ItemStack lapis, int needed) {
        this.player = player; sourceInventorySlot = source; outputInventorySlot = output;
        sourceStack = player.getInventory().getItem(source).copy(); input = sourceStack.copyWithCount(1);
        lapisKind = lapis.copyWithCount(lapis.isEmpty() ? 0 : 1); lapisNeeded = needed;
        inputBefore = count(input); lapisBefore = count(lapisKind);
    }

    static EnchantInventory prepare(LocalPlayer player, EnchantTaskRecord record) {
        // 只在主背包选未附魔物品；堆叠书先按一本判断，已经附魔或含储存附魔的物品不会被再次消费。
        int source = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(record.itemId)
                    && stack.copyWithCount(1).isEnchantable()
                    && stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty()) {
                source = i; break;
            }
        }
        if (source < 0) throw new IllegalArgumentException("enchantable_item_missing");
        int output = source;
        if (player.getInventory().getItem(source).getCount() > 1) {
            output = -1;
            for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).isEmpty()) { output = i; break; }
            if (output < 0) throw new IllegalArgumentException("inventory_space_required_for_enchanted_item");
        }
        int needed = player.hasInfiniteMaterials() ? 0 : record.offerTier;
        ItemStack lapis = ItemStack.EMPTY;
        // 多叠同组件青金石可以合并凑足本档费用；不同命名的材料不能靠替换组件强行合并。
        for (int i = 0; needed > 0 && i < 36; i++) {
            ItemStack candidate = player.getInventory().getItem(i);
            if (candidate.is(Items.LAPIS_LAZULI) && count(player, candidate) >= needed) { lapis = candidate; break; }
        }
        if (needed > 0 && lapis.isEmpty()) throw new IllegalArgumentException("enchantment_insufficient_compatible_lapis");
        return new EnchantInventory(player, source, output, lapis, needed);
    }

    List<Move> loadInput(EnchantmentMenu menu) {
        // 到台子后重新核对原来源整叠，防止导航途中物品被换过却沿用旧槽号。
        if (!ItemStack.matches(sourceStack, player.getInventory().getItem(sourceInventorySlot)))
            throw new IllegalStateException("enchantment_source_changed");
        requireOutputSpace();
        return List.of(new Move(menuSlot(menu, sourceInventorySlot), 0, 1));
    }

    List<Move> loadLapis(EnchantmentMenu menu) {
        var moves = new ArrayList<Move>();
        int remaining = lapisNeeded;
        // 逐叠精确搬到同一青金石格，最多放本档真正需要的数量，绝不整堆快速移动超额材料。
        for (int i = 0; i < 36 && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!sameKind(stack, lapisKind)) continue;
            int take = Math.min(remaining, stack.getCount());
            moves.add(new Move(menuSlot(menu, i), 1, take)); remaining -= take;
        }
        if (remaining != 0) throw new IllegalStateException("enchantment_lapis_inventory_changed");
        return moves;
    }

    boolean loaded(EnchantmentMenu menu) {
        // 装料完成时输入必须仍是那一件，青金石数量和组件必须精确吻合，光标必须已经清空。
        return ItemStack.matches(input, menu.getSlot(0).getItem()) && menu.getCarried().isEmpty()
                && ItemStack.matches(lapisKind.copyWithCount(lapisNeeded), menu.getSlot(1).getItem())
                && count(input) == inputBefore - 1 && count(lapisKind) == lapisBefore - lapisNeeded;
    }

    void freezeResult(ItemStack confirmedResult) {
        // 接收原生回执确认当刻保存的成品副本，不能在暂停恢复后把工作槽里后来换入的物品当成原成品。
        result = confirmedResult.copy(); resultBefore = count(result);
    }

    Move returnMove(EnchantmentMenu menu, int workSlot, boolean enchanted) {
        ItemStack stack = menu.getSlot(workSlot).getItem();
        int preferred = workSlot == 0 ? enchanted ? outputInventorySlot : sourceInventorySlot : -1;
        // 优先放回来源或事先保留的成品空位，空间被外部占用时只寻找兼容空位，不交换别人的物品。
        int target = canReceive(preferred, stack) ? preferred : -1;
        for (int i = 0; target < 0 && i < 36; i++) if (canReceive(i, stack)) target = i;
        if (target < 0) throw new IllegalStateException("inventory_space_required_for_enchantment_return");
        return new Move(workSlot, menuSlot(menu, target), stack.getCount());
    }

    boolean ownedContents(EnchantmentMenu menu, boolean enchanted, int expectedLapis) {
        // 只承认最初投入的一件或回执已验证的成品；陌生光标和外来工作槽会留给玩家接管。
        ItemStack actual = menu.getSlot(0).getItem(), lapis = menu.getSlot(1).getItem();
        return menu.getCarried().isEmpty() && (actual.isEmpty() || ItemStack.matches(actual, enchanted ? result : input))
                && (lapis.isEmpty() || sameKind(lapis, lapisKind) && lapis.getCount() <= expectedLapis);
    }

    boolean verifiedReturn(EnchantmentMenu menu, int lapisSpent) {
        // 最终同时核对空工作槽、原物品减少一件、完整成品增加一件和材料真实扣减，不能仅凭动画声称成功。
        return !result.isEmpty() && menu.getCarried().isEmpty() && menu.getSlot(0).getItem().isEmpty()
                && menu.getSlot(1).getItem().isEmpty() && count(input) == inputBefore - 1
                && count(result) == resultBefore + 1 && count(lapisKind) == lapisBefore - lapisSpent;
    }

    boolean restoredBeforeConsumption(EnchantmentMenu menu) {
        return menu.getCarried().isEmpty() && menu.getSlot(0).getItem().isEmpty() && menu.getSlot(1).getItem().isEmpty()
                && count(input) == inputBefore && count(lapisKind) == lapisBefore;
    }

    void requireOutputSpace() {
        // 一本书变成不可堆叠的附魔书后需要独立格；投入前来源只有一件时，其腾出的格就是预留位置。
        if (outputInventorySlot != sourceInventorySlot && !player.getInventory().getItem(outputInventorySlot).isEmpty())
            throw new IllegalStateException("enchantment_reserved_output_space_changed");
    }

    String resultItemId() { return result.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(result.getItem()).toString(); }

    Map<String, Integer> resultEnchantments() {
        // 只读已经冻结的成品，完整列出随机附魔的实际结果；附魔书读取储存附魔，装备读取普通附魔组件。
        var component = result.is(Items.ENCHANTED_BOOK) ? DataComponents.STORED_ENCHANTMENTS : DataComponents.ENCHANTMENTS;
        var values = new java.util.TreeMap<String, Integer>();
        result.getOrDefault(component, ItemEnchantments.EMPTY).entrySet().forEach(entry ->
                entry.getKey().unwrapKey().ifPresent(key -> values.put(key.location().toString(), entry.getIntValue())));
        return java.util.Collections.unmodifiableMap(values);
    }

    private boolean canReceive(int inventorySlot, ItemStack stack) {
        if (inventorySlot < 0 || stack.isEmpty()) return false;
        ItemStack there = player.getInventory().getItem(inventorySlot);
        return there.isEmpty() || sameKind(there, stack) && there.getCount() + stack.getCount() <= stack.getMaxStackSize();
    }

    private int menuSlot(EnchantmentMenu menu, int inventorySlot) {
        // 不假定玩家背包恰好从某个菜单编号开始，按真实 Slot 的容器与来源格建立对应关系。
        for (int i = 2; i < menu.slots.size(); i++) {
            var slot = menu.getSlot(i);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == inventorySlot) return i;
        }
        throw new IllegalStateException("enchantment_inventory_slot_missing");
    }

    private int count(ItemStack kind) { return count(player, kind); }
    private static int count(LocalPlayer player, ItemStack kind) {
        int count = 0;
        for (int i = 0; !kind.isEmpty() && i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (sameKind(stack, kind)) count += stack.getCount();
        }
        return count;
    }
    private static boolean sameKind(ItemStack left, ItemStack right) {
        return !left.isEmpty() && !right.isEmpty() && ItemStack.isSameItemSameComponents(left, right);
    }
}
