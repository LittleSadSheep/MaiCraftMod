// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.core.task.container.ContainerSplitPlanner;
import org.maiwithu.maicraft.core.task.container.ContainerSplitPlanner.Side;

/** 先在背包中分出准确数量，再一次整份丢出；满包时用鼠标暂存，绝不靠连续单件投掷凑数。 */
public final class DropBatchPlan {
    private DropBatchPlan() {}

    public record Click(int slot, int button, ClickType type, Map<Integer, ItemStack> before,
                        Map<Integer, ItemStack> after, ItemStack cursorBefore, ItemStack cursorAfter, int dropped) {
        public boolean matches(AbstractContainerMenu menu, boolean applied) {
            var slots = applied ? after : before;
            return ItemStack.matches(menu.getCarried(), applied ? cursorAfter : cursorBefore)
                    && slots.entrySet().stream().allMatch(entry -> ItemStack.matches(menu.getSlot(entry.getKey()).getItem(), entry.getValue()));
        }
        public MenuConfirmation confirmation() {
            // 分堆核对来源、暂存格和鼠标，投掷核对精确扣数；菜单变化不能被误算成已丢出的数量。
            return (context, receipt) -> matches(context.player().containerMenu, true) ? MenuConfirmation.Verdict.APPLIED
                    : matches(context.player().containerMenu, false) ? MenuConfirmation.Verdict.NOT_APPLIED : MenuConfirmation.Verdict.DIVERGED;
        }
    }

    public static int source(Inventory inventory, Item item, int remaining) {
        int best = -1;
        // 优先选择不超出余量的最大整堆；只有找不到整堆时才分拆较小的一堆，减少界面操作。
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(item) || stack.isEmpty()) continue;
            int count = stack.getCount(), previous = best < 0 ? Integer.MAX_VALUE : inventory.getItem(best).getCount();
            if (best < 0 || count <= remaining && (previous > remaining || count > previous)
                    || count > remaining && previous > remaining && count < previous) best = slot;
        }
        return best;
    }

    public static List<Click> plan(AbstractContainerMenu menu, Inventory inventory, int inventorySlot, int amount) {
        int source = menu.findSlot(inventory, inventorySlot).orElseThrow();
        ItemStack kind = menu.getSlot(source).getItem().copy(); int count = kind.getCount();
        if (amount < 1 || amount > count || !menu.getCarried().isEmpty()) throw new IllegalArgumentException("drop batch requires an exact source and empty cursor");
        if (amount == count || amount == 1) return List.of(new Click(source, amount == count ? 1 : 0, ClickType.THROW,
                Map.of(source, kind), Map.of(source, kind.copyWithCount(count - amount)), ItemStack.EMPTY, ItemStack.EMPTY, amount));
        var clicks = new ArrayList<Click>();
        // 有空格时复用原生左右键分堆规划；例如 64 中分出 40，先在背包中组合，再对这份 40 使用 Ctrl+Q。
        int destination = -1;
        for (var slot : menu.slots) if (slot.container == inventory && slot.getContainerSlot() < 36 && !slot.hasItem()
                && slot.mayPlace(kind) && slot.getMaxStackSize(kind) >= amount) { destination = slot.index; break; }
        if (destination >= 0) {
            for (var step : ContainerSplitPlanner.plan(count, amount, menu.getSlot(source).getMaxStackSize(kind))) {
                var before = step.before(); var after = step.after();
                clicks.add(new Click(step.side() == Side.SOURCE ? source : destination, step.button(), ClickType.PICKUP,
                        Map.of(source, kind.copyWithCount(before.source()), destination, kind.copyWithCount(before.deposited())),
                        Map.of(source, kind.copyWithCount(after.source()), destination, kind.copyWithCount(after.deposited())),
                        kind.copyWithCount(before.cursor()), kind.copyWithCount(after.cursor()), 0));
            }
            clicks.add(new Click(destination, 1, ClickType.THROW,
                    Map.of(source, kind.copyWithCount(count - amount), destination, kind.copyWithCount(amount)),
                    Map.of(source, kind.copyWithCount(count - amount), destination, ItemStack.EMPTY), ItemStack.EMPTY, ItemStack.EMPTY, amount));
        } else {
            // 满包无法借空格：拿起足够的一份，把多拿的退回原槽，再左键界面外一次丢掉鼠标上的全部余量。
            int take = (count + 1) / 2 >= amount ? (count + 1) / 2 : count;
            int button = take == count ? 0 : 1;
            clicks.add(new Click(source, button, ClickType.PICKUP, Map.of(source, kind), Map.of(source, kind.copyWithCount(count - take)),
                    ItemStack.EMPTY, kind.copyWithCount(take), 0));
            for (int cursor = take; cursor > amount; cursor--) clicks.add(new Click(source, 1, ClickType.PICKUP,
                    Map.of(source, kind.copyWithCount(count - cursor)), Map.of(source, kind.copyWithCount(count - cursor + 1)),
                    kind.copyWithCount(cursor), kind.copyWithCount(cursor - 1), 0));
            clicks.add(new Click(-999, 0, ClickType.PICKUP, Map.of(source, kind.copyWithCount(count - amount)),
                    Map.of(source, kind.copyWithCount(count - amount)), kind.copyWithCount(amount), ItemStack.EMPTY, amount));
        }
        return List.copyOf(clicks);
    }
}
