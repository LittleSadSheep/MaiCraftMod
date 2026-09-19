// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/** 只跟踪本次原料和成品的完整组件；其他背包物品可以正常变化，不能把同名不同组件的物品混作一次消耗。 */
final class WorldProcessInventory {
    private static final class Stock {
        final ItemStack kind; int expected;
        Stock(ItemStack kind, int expected) { this.kind = kind.copyWithCount(1); this.expected = expected; }
    }
    private final LocalPlayer player;
    private final List<ItemStack> initial;
    private final List<Stock> stocks = new ArrayList<>();
    WorldProcessInventory(LocalPlayer player, WorldProcessBatchPlan plan, ItemStack result) {
        this.player = player;
        // 投料前保存整个主背包；原生加工随后才赋予的新成品组件也必须从这份旧快照找基线。
        initial = snapshot(player);
        for (int i = 0; i < plan.size(); i++) for (ItemStack item : plan.batch(i)) stock(item);
        stock(result);
    }

    static List<ItemStack> snapshot(LocalPlayer player) {
        // 原生投料仅选主背包与快捷栏，盔甲、副手和菜单光标不属于可消耗库存。
        var result = new ArrayList<ItemStack>();
        for (int i = 0; i < 36; i++) result.add(player.getInventory().getItem(i).copy());
        return result;
    }

    void requireUnchanged() {
        for (Stock stock : stocks) if (count(stock.kind) != stock.expected)
            throw new IllegalStateException("world_process_reserved_inventory_changed");
    }

    void dropped(ItemStack item, int quantity) {
        // 已冻结的投料回执先记实际消耗；暂停期间成品可能已被本人拾取，不能用此刻库存否定历史投料。
        // 下一次投料前仍核对账本，最后一份则等待同UUID拾取和成品净增共同结算。
        Stock stock = stock(item); int remaining = Math.subtractExact(stock.expected, quantity);
        if (quantity < 1 || remaining < 0) throw new IllegalStateException("world_process_input_accounting_underflow");
        stock.expected = remaining;
    }

    boolean collected(ItemStack item, int quantity) {
        Stock stock = stock(item);
        int after = Math.addExact(stock.expected, quantity);
        if (quantity < 1 || count(item) != after) return false;
        // 只有成品和其余预留物资同时对账通过才推进账本，部分同步或错误组件不能留下半次成功。
        for (Stock other : stocks) if (other != stock && count(other.kind) != other.expected)
            throw new IllegalStateException("world_process_reserved_inventory_changed");
        stock.expected = after; return true;
    }

    // 发现真实成品时只登记其种类，基线始终来自投料前快照，即使恢复时物品已经进了背包也不改写历史。
    void expectOutput(ItemStack item) { stock(item); }

    private Stock stock(ItemStack item) {
        for (Stock stock : stocks) if (ItemStack.isSameItemSameComponents(stock.kind, item)) return stock;
        Stock added = new Stock(item, count(initial, item)); stocks.add(added); return added;
    }

    private int count(ItemStack item) {
        // 逐刻只读数量无需复制整包；只有投料前的长期基线需要冻结完整ItemStack。
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack actual = player.getInventory().getItem(slot);
            if (!actual.isEmpty() && ItemStack.isSameItemSameComponents(actual, item)) total += actual.getCount();
        }
        return total;
    }

    private static int count(List<ItemStack> items, ItemStack item) {
        int total = 0;
        for (ItemStack actual : items) {
            if (!actual.isEmpty() && ItemStack.isSameItemSameComponents(actual, item)) total += actual.getCount();
        }
        return total;
    }
}
