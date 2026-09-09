package org.maiwithu.maicraft.core.task.container;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 按给定菜单槽位逐笔搬物品：快速移动，或先拿到鼠标上再放入／交换，最后处理鼠标残留物品。
 * 它不负责寻找箱子，开始前应已经打开正确菜单。部分步骤成功后失败，不会自动撤回已经完成的所有搬运。
 */
public final class ContainerTransferCompanionTask
        extends AbstractCompanionTask<ContainerTransferTaskRecord> {
    /** Confirmed native menu changes keep a large transfer alive; pending receipts do not. */
    private static final long CLICK_PROGRESS_LEASE_TICKS = 60L * 20L;
    private enum Phase { BEGIN, QUICK, PICKUP, PLACE_ALL, PLACE_ONE, SWAP_DEST, RETURN_CURSOR, CLOSE, FAILING }
    private int moveIndex;
    private Phase phase = Phase.BEGIN;
    private MenuReceipt receipt;
    private ItemStack sourceKind = ItemStack.EMPTY;
    private ItemStack sourceBefore = ItemStack.EMPTY;
    private ItemStack destinationBefore = ItemStack.EMPTY;
    private int requested;
    private int movedThis;
    private boolean swapMode;
    private boolean wholePlacement;
    private final List<Integer> moved = new ArrayList<>();
    private String pendingFailure;
    private AbstractContainerMenu menu;
    private boolean completed;

    public ContainerTransferCompanionTask(LocalPlayer player, ContainerTransferTaskRecord record) {
        super(player, record);
    }

    // 每刻只推进一小步，先确认菜单还是原来那个，再等待上一次点击结果。
    // 最后一笔成功后，按 closeAfter 决定保留菜单给父任务检查，还是关闭后结束。
    @Override protected TaskState onTick() {
        var context = ClientRuntime.requireContext(player);
        if (phase == Phase.CLOSE) return closeCompleted(context);
        if (player.containerMenu.containerId != r.expectedContainerId
                || menu != null && player.containerMenu != menu) {
            fail("active container changed before transfer completed; no rollback targeted the replacement menu", FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        if (menu == null) menu = player.containerMenu;
        if (receipt != null) {
            receipt = context.menus().poll(context, receipt);
            if (!receipt.terminal()) return TaskState.RUNNING;
            if (receipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
                String detail = receipt.detail();
                receipt = null;
                if (phase == Phase.RETURN_CURSOR) {
                    pendingFailure = (pendingFailure == null
                            ? "container transfer failed while returning the carried stack"
                            : pendingFailure)
                            + "; cursor rollback was not confirmed: " + detail;
                    phase = Phase.FAILING;
                    return TaskState.RUNNING;
                }
                return beginFailure("container click was not confirmed: " + detail);
            }
            receipt = null;
            r.extendDeadlineTo(player.level().getGameTime() + CLICK_PROGRESS_LEASE_TICKS);
            afterConfirmedClick();
        }
        if (phase == Phase.FAILING) {
            fail(pendingFailure, FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        if (moveIndex >= r.moves.size()) {
            if (!r.closeAfter) { completed = true; return TaskState.SUCCESS; }
            phase = Phase.CLOSE;
            return closeCompleted(context);
        }
        if (!context.menus().ensureVisible(context)) return TaskState.RUNNING;
        ContainerTransferTaskRecord.Move move = r.moves.get(moveIndex);
        return switch (phase) {
            case BEGIN -> beginMove(move);
            case QUICK -> submitQuick(context, move);
            case PICKUP -> submitPickup(context, move);
            case PLACE_ALL -> submitAll(context, move);
            case PLACE_ONE -> submitOne(context, move);
            case SWAP_DEST -> submitSwap(context, move);
            case RETURN_CURSOR -> submitReturn(context, move);
            case CLOSE -> closeCompleted(context);
            case FAILING -> TaskState.FAILED;
        };
    }

    private TaskState closeCompleted(LocalPlayerContext context) {
        if (receipt == null) {
            receipt = context.menus().close(context, 40);
            return TaskState.RUNNING;
        }
        receipt = context.menus().poll(context, receipt);
        if (!receipt.terminal()) return TaskState.RUNNING;
        if (receipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED
                || player.containerMenu != player.inventoryMenu || context.minecraft().screen != null) {
            fail("container transfer completed but its GUI close was not confirmed", FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        completed = true;
        return TaskState.SUCCESS;
    }

    // 检查源格、目标格和数量；同一格搬给自己算零件完成。整堆可快速移动，也可拾起后放到指定格。
    private TaskState beginMove(ContainerTransferTaskRecord.Move move) {
        if (!validSlot(move.from()) || (move.to() >= 0 && !validSlot(move.to()))) {
            return beginFailure("transfer references an unavailable menu slot");
        }
        ItemStack source = player.containerMenu.getSlot(move.from()).getItem();
        if (source.isEmpty()) return beginFailure("source slot " + move.from() + " is empty");
        if (move.from() == move.to()) {
            completeMove(0);
            return TaskState.RUNNING;
        }
        sourceBefore = source.copy();
        sourceKind = source.copyWithCount(1);
        requested = move.count() == 0 ? source.getCount() : move.count();
        if (requested > source.getCount()) {
            return beginFailure("source slot " + move.from() + " holds only " + source.getCount()
                    + " item(s), fewer than requested " + requested);
        }
        movedThis = 0;
        if (move.to() < 0) {
            if (requested != source.getCount()) {
                return beginFailure("QUICK_MOVE is whole-stack only; provide a destination for an exact count");
            }
            phase = Phase.QUICK;
            return TaskState.RUNNING;
        }
        ItemStack destination = player.containerMenu.getSlot(move.to()).getItem();
        destinationBefore = destination.copy();
        // 目标格是别的物品时，只有 count=0 的整堆操作才允许交换；指定精确数量时不擅自交换整堆。
        if (!destination.isEmpty() && !ItemStack.isSameItemSameComponents(destination, source)) {
            if (move.count() != 0) {
                return beginFailure("destination slot " + move.to()
                        + " contains an incompatible stack; only count=0 may swap whole stacks");
            }
            swapMode = true;
            phase = Phase.PICKUP;
            return TaskState.RUNNING;
        }
        int capacity = source.getMaxStackSize() - destination.getCount();
        if (capacity < requested) {
            return beginFailure("destination slot " + move.to() + " has room for only " + capacity
                    + " item(s), fewer than requested " + requested);
        }
        wholePlacement = requested == source.getCount();
        phase = Phase.PICKUP;
        return TaskState.RUNNING;
    }

    // 快速移动只观察源格是否改变；当前没有读取实际减少量，后面却把 requested 整堆记为已搬（A49）。
    private TaskState submitQuick(LocalPlayerContext context, ContainerTransferTaskRecord.Move move) {
        ItemStack before = player.containerMenu.getSlot(move.from()).getItem().copy();
        receipt = context.menus().click(
                context, move.from(), 0, ClickType.QUICK_MOVE,
                (c, ignored) -> !same(c.player().containerMenu.getSlot(move.from()).getItem(), before)
                        ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING, 20);
        return TaskState.RUNNING;
    }

    // 先把源物品拿到鼠标上；当前确认种类和组件匹配，没有在这一阶段核对准确数量。
    private TaskState submitPickup(LocalPlayerContext context, ContainerTransferTaskRecord.Move move) {
        receipt = context.menus().click(
                context, move.from(), 0, ClickType.PICKUP,
                (c, ignored) -> {
                    ItemStack carried = c.player().containerMenu.getCarried();
                    if (carried.isEmpty()) return MenuConfirmation.Verdict.PENDING;
                    return ItemStack.isSameItemSameComponents(carried, sourceKind)
                            ? MenuConfirmation.Verdict.APPLIED
                            : MenuConfirmation.Verdict.DIVERGED;
                }, 20);
        return TaskState.RUNNING;
    }

    // 右键放一个，通过鼠标上的数量少一来确认；要放几件就重复几次，剩余的随后还给源格。
    private TaskState submitOne(LocalPlayerContext context, ContainerTransferTaskRecord.Move move) {
        int before = player.containerMenu.getCarried().getCount();
        receipt = context.menus().click(
                context, move.to(), 1, ClickType.PICKUP,
                (c, ignored) -> c.player().containerMenu.getCarried().getCount() == before - 1
                        ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING, 20);
        return TaskState.RUNNING;
    }

    /** A full compatible stack is one ordinary left click, not one right click per item. */
    // 整堆放入一般要求目标格增加指定数量、鼠标清空。
    // 调用方明确允许机器立即消耗时，可改用源格准确扣减与鼠标清空来确认，不要求机器槽长期保留原物品。
    private TaskState submitAll(LocalPlayerContext context, ContainerTransferTaskRecord.Move move) {
        receipt = context.menus().click(
                context, move.to(), 0, ClickType.PICKUP,
                (c, ignored) -> {
                    ItemStack source = c.player().containerMenu.getSlot(move.from()).getItem();
                    ItemStack destination = c.player().containerMenu.getSlot(move.to()).getItem();
                    ItemStack carried = c.player().containerMenu.getCarried();
                    boolean exactDestination = ItemStack.isSameItemSameComponents(
                                    destination, sourceKind)
                            && destination.getCount() == destinationBefore.getCount() + requested;
                    // Some synchronized workstations consume or transform a deposited stack in
                    // the same server tick. Furnace fuel is the common case: one fuel item can
                    // leave the destination slot as burn time before the placement echo arrives.
                    // The exact source debit plus an empty cursor still proves that the explicit
                    // destination click settled; a rejected click restores the source/cursor and
                    // therefore cannot satisfy this postcondition.
                    boolean consumedAfterDeposit = move.destinationMode()
                                    == ContainerTransferTaskRecord.DestinationMode.MAY_MUTATE_AFTER_DEPOSIT
                            && sourceDebitConfirmed(source);
                    boolean placed = carried.isEmpty()
                            && (exactDestination || consumedAfterDeposit);
                    if (placed) return MenuConfirmation.Verdict.APPLIED;
                    boolean pending = same(destination, destinationBefore)
                            && ItemStack.isSameItemSameComponents(carried, sourceKind)
                            && carried.getCount() == requested;
                    return pending ? MenuConfirmation.Verdict.PENDING
                            : MenuConfirmation.Verdict.DIVERGED;
                }, 20);
        return TaskState.RUNNING;
    }

    private boolean sourceDebitConfirmed(ItemStack source) {
        int expectedRemaining = sourceBefore.getCount() - requested;
        if (expectedRemaining == 0) return source.isEmpty();
        return expectedRemaining > 0
                && ItemStack.isSameItemSameComponents(source, sourceBefore)
                && source.getCount() == expectedRemaining;
    }

    // 交换后应由目标格装着原源物品，鼠标拿着原目标物品，接下来再把它还到源格。
    private TaskState submitSwap(LocalPlayerContext context, ContainerTransferTaskRecord.Move move) {
        receipt = context.menus().click(
                context, move.to(), 0, ClickType.PICKUP,
                (c, ignored) -> {
                    ItemStack destination = c.player().containerMenu.getSlot(move.to()).getItem();
                    ItemStack carried = c.player().containerMenu.getCarried();
                    if (same(destination, sourceBefore) && same(carried, destinationBefore)) {
                        return MenuConfirmation.Verdict.APPLIED;
                    }
                    if (same(destination, destinationBefore) && same(carried, sourceBefore)) {
                        return MenuConfirmation.Verdict.PENDING;
                    }
                    return MenuConfirmation.Verdict.DIVERGED;
                }, 20);
        return TaskState.RUNNING;
    }

    // 把鼠标上剩下的物品放回原来源格；确认鼠标已空。它不是撤销此前所有已经成功的搬运。
    private TaskState submitReturn(LocalPlayerContext context, ContainerTransferTaskRecord.Move move) {
        receipt = context.menus().click(
                context, move.from(), 0, ClickType.PICKUP,
                (c, ignored) -> c.player().containerMenu.getCarried().isEmpty()
                        ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING, 20);
        return TaskState.RUNNING;
    }

    // 确认后才换阶段和记数量。快速移动当前直接记 requested，部分容量场景会误报整堆完成。
    private void afterConfirmedClick() {
        switch (phase) {
            case QUICK -> completeMove(requested);
            case PICKUP -> phase = swapMode ? Phase.SWAP_DEST
                    : wholePlacement ? Phase.PLACE_ALL : Phase.PLACE_ONE;
            case PLACE_ALL -> completeMove(requested);
            case PLACE_ONE -> {
                movedThis++;
                phase = movedThis >= requested
                        ? (player.containerMenu.getCarried().isEmpty() ? Phase.BEGIN : Phase.RETURN_CURSOR)
                        : Phase.PLACE_ONE;
                if (movedThis >= requested && player.containerMenu.getCarried().isEmpty()) completeMove(movedThis);
            }
            case SWAP_DEST -> {
                movedThis = requested;
                phase = Phase.RETURN_CURSOR;
            }
            case RETURN_CURSOR -> {
                if (pendingFailure != null) phase = Phase.FAILING;
                else completeMove(movedThis);
            }
            default -> { }
        }
    }

    private void completeMove(int count) {
        moved.add(count); moveIndex++; phase = Phase.BEGIN;
        sourceKind = ItemStack.EMPTY;
        sourceBefore = ItemStack.EMPTY;
        destinationBefore = ItemStack.EMPTY;
        requested = 0; movedThis = 0; swapMode = false; wholePlacement = false;
    }

    // 失败时若鼠标还拿着东西且源格有效，先尝试放回；放回后仍报告原失败，不把回收鼠标物品当任务成功。
    private TaskState beginFailure(String reason) {
        pendingFailure = reason;
        if (!player.containerMenu.getCarried().isEmpty() && moveIndex < r.moves.size()
                && validSlot(r.moves.get(moveIndex).from())) {
            phase = Phase.RETURN_CURSOR;
            return TaskState.RUNNING;
        }
        fail(reason, FailureType.UNKNOWN);
        return TaskState.FAILED;
    }

    private boolean validSlot(int slot) { return slot >= 0 && slot < player.containerMenu.slots.size(); }
    private static boolean same(ItemStack a, ItemStack b) {
        return a.getCount() == b.getCount() && ItemStack.isSameItemSameComponents(a, b);
    }
    // 只有玩家还在同一个菜单时才安排关闭；替换成别的菜单时不向它做回退或关闭。
    // 外层父任务也应保留这个限制，不能在这里正确停手后又把新菜单关掉（A51）。
    @Override protected void cleanup() {
        if (menu != null && player.containerMenu == menu && (!completed || r.closeAfter)
                && (receipt == null || receipt.terminal() || receipt.kind() != MenuReceipt.Kind.CLOSE)) {
            try {
                var context = ClientRuntime.requireContext(player);
                context.menus().closeForTaskBoundary(context, 40, "container transfer task ended");
            }
            catch (RuntimeException ignored) { }
        }
        receipt = null;
        super.cleanup();
    }
    @Override protected Map<String, Object> resultData() {
        return Map.of("completed_moves", moveIndex, "moved_counts", List.copyOf(moved));
    }
    @Override protected String successMessage() { return "confirmed " + moveIndex + " container transfer(s)"; }
    @Override protected String cancelledMessage() { return "container transfer interrupted"; }
}
