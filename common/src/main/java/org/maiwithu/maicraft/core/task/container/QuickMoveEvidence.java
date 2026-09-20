// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.container;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;

/** 一次快速搬运的两侧数量账：只记录原生点击后的实际变化，不把请求整堆等同于已经搬完。 */
final class QuickMoveEvidence {
    private final LocalPlayer player;
    private final AbstractContainerMenu menu;
    private final int sourceIndex;
    private final ItemStack before;
    private final boolean toPlayer;
    private final boolean producingSource;
    private final long inventoryBefore;
    private final long outsideBefore;
    private final int[] inventorySlotsBefore;
    private int moved;

    QuickMoveEvidence(LocalPlayer player, AbstractContainerMenu menu, int sourceIndex) {
        this.player = player;
        this.menu = menu;
        this.sourceIndex = sourceIndex;
        Slot source = menu.getSlot(sourceIndex);
        before = source.getItem().copy();
        toPlayer = source.container != player.getInventory();
        producingSource = source instanceof FurnaceResultSlot || source instanceof MerchantResultSlot || source instanceof ResultSlot;
        inventoryBefore = inventoryCount();
        outsideBefore = outsideCount();
        inventorySlotsBefore = new int[player.getInventory().getContainerSize()];
        for (int slot = 0; slot < inventorySlotsBefore.length; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, before)) inventorySlotsBefore[slot] = stack.getCount();
        }
    }

    MenuConfirmation.Verdict observe() {
        if (player.containerMenu != menu || !menu.getCarried().isEmpty()) return MenuConfirmation.Verdict.DIVERGED;
        ItemStack source = menu.getSlot(sourceIndex).getItem();
        if (!source.isEmpty() && !ItemStack.isSameItemSameComponents(source, before)) return MenuConfirmation.Verdict.DIVERGED;
        long inventoryDelta = inventoryCount() - inventoryBefore;
        long sourceDebit = before.getCount() - source.getCount();
        long transferred = toPlayer ? inventoryDelta : -inventoryDelta;
        // 炉子不接受某种物品时，原版可能只把它从主背包换到快捷栏；这也是已发生的快速移动。
        if (!toPlayer && inventoryDelta == 0 && outsideCount() == outsideBefore && sourceDebit > 0) {
            long gainedElsewhere = 0;
            int from = menu.getSlot(sourceIndex).getContainerSlot();
            for (int slot = 0; slot < inventorySlotsBefore.length; slot++) {
                if (slot == from) continue;
                ItemStack stack = player.getInventory().getItem(slot);
                int current = ItemStack.isSameItemSameComponents(stack, before) ? stack.getCount() : 0;
                gainedElsewhere += Math.max(0, current - inventorySlotsBefore[slot]);
            }
            if (sourceDebit == gainedElsewhere) {
                moved = (int) sourceDebit;
                return MenuConfirmation.Verdict.APPLIED;
            }
        }
        if (transferred < 0 || transferred > Integer.MAX_VALUE) return MenuConfirmation.Verdict.DIVERGED;
        if (producingSource && toPlayer) {
            // 结果格可以补出下一件，甚至在一次交易快速取出中连续产出；以玩家真正增加的同组件物品计数。
            if (transferred == 0) return MenuConfirmation.Verdict.PENDING;
        } else {
            long otherDelta = toPlayer ? -sourceDebit : outsideCount() - outsideBefore;
            long expected = toPlayer ? -transferred : transferred;
            // 服务端可能分开发送两侧槽位；一边先到时继续等，不能提前记账或重发点击。
            if (sourceDebit < 0 || sourceDebit > before.getCount()) return MenuConfirmation.Verdict.DIVERGED;
            if (transferred == 0 || otherDelta != expected || sourceDebit != transferred)
                return MenuConfirmation.Verdict.PENDING;
        }
        moved = (int) transferred;
        return MenuConfirmation.Verdict.APPLIED;
    }

    int moved() { return moved; }

    private long inventoryCount() {
        long total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, before)) total += stack.getCount();
        }
        return total;
    }

    private long outsideCount() {
        long total = 0;
        Map<Container, Set<Integer>> visited = new IdentityHashMap<>();
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) continue;
            Set<Integer> indices = visited.computeIfAbsent(slot.container, ignored -> new HashSet<>());
            if (indices.add(slot.getContainerSlot()) && ItemStack.isSameItemSameComponents(slot.getItem(), before))
                total += slot.getItem().getCount();
        }
        return total;
    }
}
