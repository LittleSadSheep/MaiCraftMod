package org.maiwithu.maicraft.core.task.container;

import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuReceipt;

/** 在同一个真实菜单中逐次确认分堆；只动声明的两个槽，未知鼠标物品和外来界面原样保留。 */
final class ContainerSplitTransfer {
    enum Status { RUNNING, COMPLETE, FAILED }
    private record Snapshot(ItemStack source, ItemStack destination, ItemStack cursor) {}
    private final LocalPlayer player;
    private final AbstractContainerMenu menu;
    private final ContainerTransferTaskRecord.Move move;
    private final ItemStack kind, destinationBefore;
    private final List<ContainerSplitPlanner.Step> steps;
    private int index;
    private MenuReceipt receipt;
    private String failure;
    private boolean uncertain, confirmedThisTick;
    private boolean settlementRequested;

    ContainerSplitTransfer(LocalPlayer player, AbstractContainerMenu menu, ContainerTransferTaskRecord.Move move, int amount) {
        this.player = player; this.menu = menu; this.move = move;
        ItemStack source = menu.getSlot(move.from()).getItem(); kind = source.copyWithCount(1);
        destinationBefore = menu.getSlot(move.to()).getItem().copy();
        var sourceSlot = menu.getSlot(move.from());
        var destinationSlot = menu.getSlot(move.to());
        if (!menu.getCarried().isEmpty() || !sourceSlot.mayPickup(player)
                || !destinationBefore.isEmpty() && !ItemStack.isSameItemSameComponents(source, destinationBefore)
                || !destinationSlot.mayPlace(source) || amount > Math.min(source.getMaxStackSize(), destinationSlot.getMaxStackSize(source)) - destinationBefore.getCount())
            throw new IllegalArgumentException("split source, destination capacity or cursor is not available");
        int capacity = sourceSlot.mayPlace(source) ? Math.min(source.getMaxStackSize(), sourceSlot.getMaxStackSize(source)) : -1;
        steps = ContainerSplitPlanner.plan(source.getCount(), amount, capacity);
    }

    Status tick(LocalPlayerContext context) {
        confirmedThisTick = false;
        if (failure != null) return Status.FAILED;
        if (context.player() != player || player.containerMenu != menu) return fail("split transfer menu changed", true);
        if (receipt != null) {
            receipt = context.menus().poll(context, receipt);
            if (!receipt.terminal()) return Status.RUNNING;
            if (receipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED) return fail("split click was not confirmed: " + receipt.detail(), true);
            receipt = null; index++; confirmedThisTick = true;
        }
        if (index == steps.size() || settlementRequested && menu.getCarried().isEmpty()) {
            // 父目标已经满足时，手里这一小堆结清后就停止，不再拿起下一小堆。
            var last = index == 0 ? steps.getFirst().before() : steps.get(index - 1).after(); Snapshot actual = snapshot();
            return matches(actual.source, last.source()) && actual.cursor.isEmpty()
                    && (!exact() || matchesDestination(actual.destination, destinationBefore.getCount() + last.deposited()))
                    ? Status.COMPLETE : fail("split final inventory totals changed", true);
        }
        var step = steps.get(index); Snapshot before = snapshot();
        // 重新确认实际落槽，不能因为纯数量方案合理，就忽略别人换了物品或占用了目标格。
        if (!matches(before.source, step.before().source()) || !matches(before.cursor, step.before().cursor())
                || exact() && !matchesDestination(before.destination, destinationBefore.getCount() + step.before().deposited()))
            return fail("split source, destination or cursor changed before click", index > 0);
        if (!permitted(step, before)) return fail("split slot capacity or pickup permission changed", index > 0);
        if (!context.mutationAvailable()) return Status.RUNNING;
        int destinationCount = before.destination.getCount() + step.after().deposited() - step.before().deposited();
        Snapshot after = new Snapshot(stack(step.after().source()), step.side() == ContainerSplitPlanner.Side.DESTINATION
                ? stack(destinationCount) : before.destination.copy(), stack(step.after().cursor()));
        int slot = step.side() == ContainerSplitPlanner.Side.SOURCE ? move.from() : move.to();
        receipt = context.menus().click(context, slot, step.button(), ClickType.PICKUP,
                (c, ignored) -> confirm(before, after), 20);
        return Status.RUNNING;
    }

    // 确认源格、鼠标和目的格一起满足预期；分批同步只等待，未知数值或物品变化直接停下。
    private MenuConfirmation.Verdict confirm(Snapshot before, Snapshot after) {
        if (player.containerMenu != menu) return MenuConfirmation.Verdict.DIVERGED;
        Snapshot actual = snapshot();
        // 已明确允许即时消耗的机器槽沿用原契约；普通木桶仍必须看到目的格准确增加。
        boolean destinationMayConsume = !exact();
        if (same(actual.source, after.source) && same(actual.cursor, after.cursor)
                && (destinationMayConsume || same(actual.destination, after.destination))) return MenuConfirmation.Verdict.APPLIED;
        if (either(actual.source, before.source, after.source) && either(actual.cursor, before.cursor, after.cursor)
                && (!exact() || either(actual.destination, before.destination, after.destination))) return MenuConfirmation.Verdict.PENDING;
        return MenuConfirmation.Verdict.DIVERGED;
    }
    private boolean permitted(ContainerSplitPlanner.Step step, Snapshot before) {
        boolean source = step.side() == ContainerSplitPlanner.Side.SOURCE;
        var slot = menu.getSlot(source ? move.from() : move.to());
        if (source && step.before().cursor() == 0) return slot.mayPickup(player);
        ItemStack existing = source ? before.source : before.destination;
        if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, kind)) return false;
        int added = source ? step.after().source() - step.before().source() : step.after().deposited() - step.before().deposited();
        return slot.mayPlace(kind) && added <= Math.min(kind.getMaxStackSize(), slot.getMaxStackSize(kind)) - existing.getCount();
    }
    private boolean exact() { return move.destinationMode() == ContainerTransferTaskRecord.DestinationMode.EXACT; }
    private Snapshot snapshot() { return new Snapshot(menu.getSlot(move.from()).getItem().copy(), menu.getSlot(move.to()).getItem().copy(), menu.getCarried().copy()); }
    private ItemStack stack(int count) { return count == 0 ? ItemStack.EMPTY : kind.copyWithCount(count); }
    private boolean matches(ItemStack stack, int count) { return same(stack, stack(count)); }
    private boolean matchesDestination(ItemStack stack, int count) { return matches(stack, count); }
    private static boolean same(ItemStack a, ItemStack b) { return a.getCount() == b.getCount() && ItemStack.isSameItemSameComponents(a, b); }
    private static boolean either(ItemStack actual, ItemStack before, ItemStack after) { return same(actual, before) || same(actual, after); }
    private Status fail(String reason, boolean uncertain) { failure = reason; this.uncertain |= uncertain; return Status.FAILED; }
    boolean confirmedThisTick() { return confirmedThisTick; }
    boolean hasStarted() { return receipt != null || index > 0; }
    void requestSatisfiedSettlement() { settlementRequested = true; }
    int deposited() { return index == 0 ? 0 : steps.get(index - 1).after().deposited(); }
    String failure() { return failure; }
    // 中途取消时若鼠标仍拿着本次半堆，也保持界面可见，避免关闭背包把无法放回的物品丢在地上。
    boolean preserveMenu() { return receipt != null || !menu.getCarried().isEmpty() || failure != null; }
    Map<String, Object> evidence() {
        int deposited = deposited();
        return Map.of("planned_clicks", steps.size(), "confirmed_clicks", index, "confirmed_deposited", deposited,
                "outcome_uncertain", uncertain || receipt != null, "cursor_empty", menu.getCarried().isEmpty());
    }
}
