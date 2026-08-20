package org.maiwithu.maicraft.core.task.container;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

/** Cursor-safe, live-state container transfer task. */
public final class ContainerTransferCompanionTask
        extends AbstractCompanionTask<ContainerTransferTaskRecord> {
    /** Confirmed native menu changes keep a large transfer alive; pending receipts do not. */
    private static final long CLICK_PROGRESS_LEASE_TICKS = 60L * 20L;
    private enum Phase { BEGIN, QUICK, PICKUP, PLACE_ALL, PLACE_ONE, SWAP_DEST, RETURN_CURSOR, FAILING }
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

    public ContainerTransferCompanionTask(LocalPlayer player, ContainerTransferTaskRecord record) {
        super(player, record);
    }

    @Override protected TaskState onTick() {
        if (player.containerMenu.containerId != r.expectedContainerId) {
            return beginFailure("active container changed before transfer completed");
        }
        var context = ClientRuntime.requireContext(player);
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
        if (moveIndex >= r.moves.size()) return TaskState.SUCCESS;
        ContainerTransferTaskRecord.Move move = r.moves.get(moveIndex);
        return switch (phase) {
            case BEGIN -> beginMove(move);
            case QUICK -> submitQuick(context, move);
            case PICKUP -> submitPickup(context, move);
            case PLACE_ALL -> submitAll(context, move);
            case PLACE_ONE -> submitOne(context, move);
            case SWAP_DEST -> submitSwap(context, move);
            case RETURN_CURSOR -> submitReturn(context, move);
            case FAILING -> TaskState.FAILED;
        };
    }

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

    private TaskState submitQuick(LocalPlayerContext context, ContainerTransferTaskRecord.Move move) {
        ItemStack before = player.containerMenu.getSlot(move.from()).getItem().copy();
        receipt = context.menus().click(
                context, move.from(), 0, ClickType.QUICK_MOVE,
                (c, ignored) -> !same(c.player().containerMenu.getSlot(move.from()).getItem(), before)
                        ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING, 20);
        return TaskState.RUNNING;
    }

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

    private TaskState submitOne(LocalPlayerContext context, ContainerTransferTaskRecord.Move move) {
        int before = player.containerMenu.getCarried().getCount();
        receipt = context.menus().click(
                context, move.to(), 1, ClickType.PICKUP,
                (c, ignored) -> c.player().containerMenu.getCarried().getCount() == before - 1
                        ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING, 20);
        return TaskState.RUNNING;
    }

    /** A full compatible stack is one ordinary left click, not one right click per item. */
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

    private TaskState submitReturn(LocalPlayerContext context, ContainerTransferTaskRecord.Move move) {
        receipt = context.menus().click(
                context, move.from(), 0, ClickType.PICKUP,
                (c, ignored) -> c.player().containerMenu.getCarried().isEmpty()
                        ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING, 20);
        return TaskState.RUNNING;
    }

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
    @Override protected void cleanup() {
        receipt = null;
        if (!player.containerMenu.getCarried().isEmpty()) {
            try { ClientRuntime.requireContext(player).menus().close(ClientRuntime.requireContext(player), 20); }
            catch (RuntimeException ignored) { }
        }
    }
    @Override protected Map<String, Object> resultData() {
        return Map.of("completed_moves", moveIndex, "moved_counts", List.copyOf(moved));
    }
    @Override protected String successMessage() { return "confirmed " + moveIndex + " container transfer(s)"; }
    @Override protected String cancelledMessage() { return "container transfer interrupted"; }
}
