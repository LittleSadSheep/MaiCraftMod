// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

/** Native first-person action submission and read-only reconciliation. */
public interface NativeActionPort {
    NativeActionReceipt startBreaking(LocalPlayerContext context, BlockHitResult hit, int timeoutTicks);

    NativeActionReceipt cancelBreaking(LocalPlayerContext context, NativeActionReceipt receipt);

    /**
     * Request cancellation of a break whose task owner is about to disappear.  The actor boundary
     * performs the physical stop no later than its next tick, before another task may mutate the
     * body, so cancellation remains safe even when this tick's one mutation was already submitted.
     */
    NativeActionReceipt cancelBreakingForTaskBoundary(
            LocalPlayerContext context,
            NativeActionReceipt receipt,
            String boundaryReason);

    NativeActionReceipt continueBreaking(LocalPlayerContext context, NativeActionReceipt receipt);

    NativeActionReceipt useBlock(
            LocalPlayerContext context,
            InteractionHand hand,
            BlockHitResult hit,
            NativeConfirmation confirmation,
            int timeoutTicks);

    NativeActionReceipt useItem(
            LocalPlayerContext context,
            InteractionHand hand,
            NativeConfirmation confirmation,
            int timeoutTicks);

    NativeActionReceipt releaseUsingItem(LocalPlayerContext context, NativeActionReceipt receipt);

    NativeActionReceipt selectHotbar(LocalPlayerContext context, int slot, int timeoutTicks);

    NativeActionReceipt creativeSetSlot(
            LocalPlayerContext context, int inventorySlot, ItemStack expected, int timeoutTicks);

    NativeActionReceipt submitProtocol(
            LocalPlayerContext context,
            String operation,
            Runnable submission,
            NativeConfirmation confirmation,
            int timeoutTicks);

    /** Native world controls share action serialization and authority, without opening a menu. */
    NativeActionReceipt submitControlProtocol(
            LocalPlayerContext context,
            String operation,
            Runnable submission,
            NativeConfirmation confirmation,
            int timeoutTicks);

    NativeActionReceipt attack(

            LocalPlayerContext context,
            Entity target,
            NativeConfirmation confirmation,
            int timeoutTicks);

    NativeActionReceipt interact(
            LocalPlayerContext context,
            Entity target,
            InteractionHand hand,
            NativeConfirmation confirmation,
            int timeoutTicks);

    /**
     * Retire a submitted one-shot effect when its owning task ends before confirmation.
     *
     * <p>This does not pretend that the effect was rolled back: the returned receipt is marked
     * uncertain when its postcondition has not settled yet.  It only releases the serialized
     * actor slot so a discarded task-local receipt cannot block the next task.  Continuous native
     * actions ({@link NativeActionReceipt.Kind#BREAK_BLOCK BREAK_BLOCK} and
     * {@link NativeActionReceipt.Kind#USE_ITEM USE_ITEM}) must be physically stopped through their
     * dedicated APIs instead.
     */
    NativeActionReceipt retireOneShotForTaskBoundary(
            LocalPlayerContext context,
            NativeActionReceipt receipt,
            String boundaryReason);

    NativeActionReceipt poll(LocalPlayerContext context, NativeActionReceipt receipt);
}
