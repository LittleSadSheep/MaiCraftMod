// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

/** Default MultiPlayerGameMode action port. Immediate return values are never treated as success. */
public final class DefaultNativeActionPort implements NativeActionPort {
    private NativeActionReceipt active;
    /** Non-null while an ownerless break must be physically stopped by {@link #advance}. */
    private String pendingBreakCancellationReason;

    @Override
    public NativeActionReceipt startBreaking(LocalPlayerContext context, BlockHitResult hit, int timeoutTicks) {
        DefaultLocalPlayerContext current = requireSubmission(context);
        requireIdle();
        current.claimMutation();
        NativeActionReceipt receipt = new NativeActionReceipt(
                NativeActionReceipt.Kind.BREAK_BLOCK,
                current,
                timeoutTicks,
                2,
                NativeConfirmation.blockBecomesAir(
                        hit.getBlockPos(), current.level().getBlockState(hit.getBlockPos())),
                hit.getBlockPos(),
                hit.getDirection());
        install(receipt);
        try {
            current.gameMode().startDestroyBlock(hit.getBlockPos(), hit.getDirection());
            current.player().swing(InteractionHand.MAIN_HAND);
        } catch (RuntimeException failure) {
            receipt.finish(NativeActionReceipt.Status.UNCERTAIN,
                    "native mining start threw after entering the client action path");
        }
        return poll(current, receipt);
    }

    @Override
    public NativeActionReceipt cancelBreaking(LocalPlayerContext context, NativeActionReceipt receipt) {
        DefaultLocalPlayerContext current = requireSubmission(context);
        requireActive(receipt, NativeActionReceipt.Kind.BREAK_BLOCK);
        poll(current, receipt);
        if (receipt.terminal()) {
            pendingBreakCancellationReason = null;
            return receipt;
        }
        current.claimMutation();
        try {
            current.gameMode().stopDestroyBlock();
            receipt.finish(NativeActionReceipt.Status.CANCELLED,
                    "native mining was cancelled before confirmation");
        } catch (RuntimeException failure) {
            receipt.finish(NativeActionReceipt.Status.UNCERTAIN,
                    "native mining cancellation could not be confirmed");
        }
        pendingBreakCancellationReason = null;
        return receipt;
    }

    @Override
    public NativeActionReceipt cancelBreakingForTaskBoundary(
            LocalPlayerContext context,
            NativeActionReceipt receipt,
            String boundaryReason) {
        context.requireCurrent();
        requireActive(receipt, NativeActionReceipt.Kind.BREAK_BLOCK);
        poll(context, receipt);
        if (receipt.terminal()) {
            pendingBreakCancellationReason = null;
            return receipt;
        }
        String reason = boundaryReason == null || boundaryReason.isBlank()
                ? "the owning task ended before native mining confirmation"
                : boundaryReason;
        if (context.mutationAvailable()
                && context instanceof DefaultLocalPlayerContext current) {
            current.claimMutation();
            try {
                current.gameMode().stopDestroyBlock();
                receipt.finish(NativeActionReceipt.Status.CANCELLED, reason);
            } catch (RuntimeException failure) {
                receipt.finish(NativeActionReceipt.Status.UNCERTAIN,
                        reason + "; native mining cancellation could not be confirmed");
            }
            pendingBreakCancellationReason = null;
            return receipt;
        }
        pendingBreakCancellationReason = reason;
        return receipt;
    }

    @Override
    public NativeActionReceipt continueBreaking(LocalPlayerContext context, NativeActionReceipt receipt) {
        DefaultLocalPlayerContext current = requireSubmission(context);
        requireActive(receipt, NativeActionReceipt.Kind.BREAK_BLOCK);
        poll(current, receipt);
        if (receipt.terminal() || receipt.lastNativeTick() == current.tickRevision()) return receipt;
        current.claimMutation();
        try {
            current.gameMode().continueDestroyBlock(receipt.breakTarget(), receipt.breakFace());
            current.player().swing(InteractionHand.MAIN_HAND);
            receipt.nativeAdvanced(current.tickRevision());
        } catch (RuntimeException failure) {
            receipt.finish(NativeActionReceipt.Status.UNCERTAIN,
                    "native mining continuation threw before the block outcome was confirmed");
        }
        return poll(current, receipt);
    }

    @Override
    public NativeActionReceipt useBlock(LocalPlayerContext context, InteractionHand hand, BlockHitResult hit,
                                        NativeConfirmation confirmation, int timeoutTicks) {
        DefaultLocalPlayerContext current = requireSubmission(context);
        requireIdle();
        current.claimMutation();
        NativeActionReceipt receipt = oneShot(
                NativeActionReceipt.Kind.USE_BLOCK, current, confirmation, timeoutTicks);
        try {
            var result = current.gameMode().useItemOn(current.player(), hand, hit);
            if (result.shouldSwing()) current.player().swing(hand);
        } catch (RuntimeException failure) {
            receipt.finish(NativeActionReceipt.Status.UNCERTAIN,
                    "native block use threw after entering the client action path");
        }
        return poll(current, receipt);
    }

    @Override
    public NativeActionReceipt useItem(LocalPlayerContext context, InteractionHand hand,
                                       NativeConfirmation confirmation, int timeoutTicks) {
        DefaultLocalPlayerContext current = requireSubmission(context);
        requireIdle();
        current.claimMutation();
        NativeActionReceipt receipt = oneShot(
                NativeActionReceipt.Kind.USE_ITEM, current, confirmation, timeoutTicks);
        try {
            var result = current.gameMode().useItem(current.player(), hand);
            if (result.shouldSwing()) current.player().swing(hand);
        } catch (RuntimeException failure) {
            receipt.finish(NativeActionReceipt.Status.UNCERTAIN,
                    "native item use threw after entering the client action path");
        }
        return poll(current, receipt);
    }

    @Override
    public NativeActionReceipt releaseUsingItem(
            LocalPlayerContext context, NativeActionReceipt receipt) {
        DefaultLocalPlayerContext current = requireSubmission(context);
        requireActive(receipt, NativeActionReceipt.Kind.USE_ITEM);
        current.claimMutation();
        NativeActionReceipt release = oneShot(
                NativeActionReceipt.Kind.RELEASE_ITEM,
                current,
                NativeConfirmation.itemUseStopped(),
                10);
        try {
            current.gameMode().releaseUsingItem(current.player());
        } catch (RuntimeException failure) {
            release.finish(NativeActionReceipt.Status.UNCERTAIN,
                    "native use release threw before the outcome was confirmed");
        }
        return poll(current, release);
    }

    @Override
    public NativeActionReceipt selectHotbar(
            LocalPlayerContext context, int slot, int timeoutTicks) {
        DefaultLocalPlayerContext current = requireSubmission(context);
        if (slot < 0 || slot >= 9) {
            throw new IllegalArgumentException("hotbar slot must be between 0 and 8");
        }
        requireIdle();
        current.claimMutation();
        NativeActionReceipt receipt = oneShot(
                NativeActionReceipt.Kind.SELECT_HOTBAR,
                current,
                NativeConfirmation.hotbarSelected(slot),
                timeoutTicks);
        try {
            current.player().getInventory().selected = slot;
            current.connection().send(new ServerboundSetCarriedItemPacket(slot));
        } catch (RuntimeException failure) {
            receipt.finish(NativeActionReceipt.Status.UNCERTAIN,
                    "native hotbar selection threw before the outcome was confirmed");
        }
        return poll(current, receipt);
    }

    @Override
    public NativeActionReceipt creativeSetSlot(
            LocalPlayerContext context, int inventorySlot, ItemStack expected, int timeoutTicks) {
        DefaultLocalPlayerContext current = requireSubmission(context);
        if (inventorySlot < 0 || inventorySlot >= Math.min(
                36, current.player().getInventory().getContainerSize())) {
            throw new IllegalArgumentException("creative inventory slot must be between 0 and 35");
        }
        if (!current.player().getAbilities().instabuild) {
            throw new IllegalStateException("creative slot mutation requires creative mode");
        }
        if (!current.menus().ensureVisible(current)) {
            throw new IllegalStateException("creative inventory changes require a rendered player inventory GUI");
        }
        requireIdle();
        current.claimMutation();
        ItemStack frozen = expected.copy();
        NativeActionReceipt receipt = oneShot(
                NativeActionReceipt.Kind.CREATIVE_SET_SLOT,
                current,
                NativeConfirmation.inventorySlot(inventorySlot, frozen),
                timeoutTicks);
        try {
            int protocolSlot = inventorySlot < 9 ? 36 + inventorySlot : inventorySlot;
            current.connection().send(
                    new ServerboundSetCreativeModeSlotPacket(protocolSlot, frozen.copy()));
            current.menus().interactionSubmitted(current);
        } catch (RuntimeException failure) {
            receipt.finish(NativeActionReceipt.Status.UNCERTAIN,
                    "creative slot packet threw before synchronized inventory confirmation");
        }
        return poll(current, receipt);
    }

    @Override
    public NativeActionReceipt submitProtocol(
            LocalPlayerContext context,
            String operation,
            Runnable submission,
            NativeConfirmation confirmation,
            int timeoutTicks) {
        DefaultLocalPlayerContext current = requireSubmission(context);
        if (submission == null) throw new IllegalArgumentException("submission is required");
        if (confirmation == null) throw new IllegalArgumentException("confirmation is required");
        String name = operation == null || operation.isBlank() ? "mod protocol action" : operation;
        requireIdle();
        current.claimMutation();
        NativeActionReceipt receipt = oneShot(
                NativeActionReceipt.Kind.MOD_PROTOCOL, current, confirmation, timeoutTicks);
        try {
            submission.run();
            current.menus().interactionSubmitted(current);
        } catch (RuntimeException failure) {
            receipt.finish(NativeActionReceipt.Status.UNCERTAIN,
                    name + " threw after protocol submission began; application is unknown");
        }
        return poll(current, receipt);
    }

    @Override
    public NativeActionReceipt attack(LocalPlayerContext context, Entity target,
                                      NativeConfirmation confirmation, int timeoutTicks) {
        DefaultLocalPlayerContext current = requireSubmission(context);
        requireIdle();
        current.claimMutation();
        NativeActionReceipt receipt = oneShot(
                NativeActionReceipt.Kind.ATTACK_ENTITY, current, confirmation, timeoutTicks);
        try {
            current.gameMode().attack(current.player(), target);
            current.player().swing(InteractionHand.MAIN_HAND);
        } catch (RuntimeException failure) {
            receipt.finish(NativeActionReceipt.Status.UNCERTAIN,
                    "native attack threw after entering the client action path");
        }
        return poll(current, receipt);
    }

    @Override
    public NativeActionReceipt interact(LocalPlayerContext context, Entity target, InteractionHand hand,
                                        NativeConfirmation confirmation, int timeoutTicks) {
        DefaultLocalPlayerContext current = requireSubmission(context);
        requireIdle();
        current.claimMutation();
        NativeActionReceipt receipt = oneShot(
                NativeActionReceipt.Kind.INTERACT_ENTITY, current, confirmation, timeoutTicks);
        try {
            var result = current.gameMode().interact(current.player(), target, hand);
            if (result.shouldSwing()) current.player().swing(hand);
        } catch (RuntimeException failure) {
            receipt.finish(NativeActionReceipt.Status.UNCERTAIN,
                    "native entity interaction threw after entering the client action path");
        }
        return poll(current, receipt);
    }

    @Override
    public NativeActionReceipt retireOneShotForTaskBoundary(
            LocalPlayerContext context,
            NativeActionReceipt receipt,
            String boundaryReason) {
        context.requireCurrent();
        if (receipt == null) throw new IllegalArgumentException("receipt is required");
        requireActive(receipt, receipt.kind());
        if (receipt.kind() == NativeActionReceipt.Kind.BREAK_BLOCK
                || receipt.kind() == NativeActionReceipt.Kind.USE_ITEM) {
            throw new IllegalArgumentException(
                    "continuous native actions require their dedicated physical stop operation");
        }
        poll(context, receipt);
        if (!receipt.terminal()) {
            String reason = boundaryReason == null || boundaryReason.isBlank()
                    ? "the owning task ended before native confirmation"
                    : boundaryReason;
            receipt.finish(NativeActionReceipt.Status.UNCERTAIN,
                    reason + "; the submitted one-shot effect may already have applied");
        }
        return receipt;
    }

    @Override
    public NativeActionReceipt poll(LocalPlayerContext context, NativeActionReceipt receipt) {
        context.requireCurrent();
        requireActive(receipt, receipt.kind());
        if (receipt.terminal()) return receipt;
        if (receipt.bodyEpoch() != context.bodyEpoch() ||
                receipt.controlRevision() != context.controlRevision() ||
                !context.permitsNativeActions()) {
            receipt.finish(NativeActionReceipt.Status.UNCERTAIN,
                    "body or control authority changed before confirmation");
            return receipt;
        }
        NativeConfirmation.Verdict verdict;
        try {
            verdict = receipt.confirmation().observe(context);
        } catch (RuntimeException observationFailure) {
            verdict = NativeConfirmation.Verdict.PENDING;
        }
        switch (verdict) {
            case APPLIED -> {
                if (receipt.countStable(context.tickRevision())) receipt.finish(
                        NativeActionReceipt.Status.CONFIRMED_APPLIED,
                        "authoritative client facts confirmed the native action");
            }
            case NOT_APPLIED -> receipt.finish(
                    NativeActionReceipt.Status.CONFIRMED_NOT_APPLIED,
                    "authoritative client facts confirmed that the action was not applied");
            case DIVERGED -> receipt.finish(
                    NativeActionReceipt.Status.DIVERGED,
                    "live facts diverged from both the frozen before and expected after state");
            case PENDING -> receipt.resetStable(context.tickRevision());
        }
        if (!receipt.terminal() && context.tickRevision() >= receipt.deadlineTick()) {
            receipt.finish(NativeActionReceipt.Status.UNCERTAIN,
                    "the bounded read-only confirmation window expired");
        }
        return receipt;
    }

    void revokeForBoundary(String reason) {
        if (active != null && !active.terminal()) {
            active.finish(NativeActionReceipt.Status.UNCERTAIN, reason);
        }
        pendingBreakCancellationReason = null;
    }

    private NativeActionReceipt oneShot(NativeActionReceipt.Kind kind, LocalPlayerContext context,
                                        NativeConfirmation confirmation, int timeoutTicks) {
        NativeActionReceipt receipt = new NativeActionReceipt(
                kind, context, timeoutTicks, 2, confirmation, null, null);
        install(receipt);
        return receipt;
    }

    private void install(NativeActionReceipt receipt) {
        active = receipt;
        pendingBreakCancellationReason = null;
    }

    private static DefaultLocalPlayerContext requireSubmission(LocalPlayerContext context) {
        if (!(context instanceof DefaultLocalPlayerContext current)) {
            throw new IllegalArgumentException("unsupported LocalPlayerContext implementation");
        }
        current.requireSubmissionAuthority();
        return current;
    }

    private void requireIdle() {
        if (active != null && !active.terminal()) {
            throw new IllegalStateException(
                    "a native action is already awaiting confirmation"
                            + " (kind=" + active.kind()
                            + ", id=" + active.id()
                            + ", status=" + active.status()
                            + ", submitted_tick=" + active.submittedTick()
                            + ", deadline_tick=" + active.deadlineTick() + ")");
        }
    }

    private void requireActive(NativeActionReceipt receipt, NativeActionReceipt.Kind kind) {
        if (receipt == null || receipt != active || receipt.kind() != kind) {
            throw new IllegalArgumentException("the receipt is not the active native action");
        }
    }
    void advance(LocalPlayerContext context) {
        NativeActionReceipt receipt = active;
        if (receipt == null || receipt.terminal()) {
            pendingBreakCancellationReason = null;
            return;
        }
        if (pendingBreakCancellationReason == null) {
            poll(context, receipt);
            return;
        }

        poll(context, receipt);
        if (receipt.terminal()) {
            pendingBreakCancellationReason = null;
            return;
        }
        if (receipt.kind() != NativeActionReceipt.Kind.BREAK_BLOCK) {
            receipt.finish(NativeActionReceipt.Status.UNCERTAIN,
                    "a task-boundary break cancellation no longer owned the active break receipt");
            pendingBreakCancellationReason = null;
            return;
        }
        if (!(context instanceof DefaultLocalPlayerContext current)
                || !context.permitsNativeActions()) {
            receipt.finish(NativeActionReceipt.Status.UNCERTAIN,
                    pendingBreakCancellationReason
                            + "; control changed before the physical stop could be submitted");
            pendingBreakCancellationReason = null;
            return;
        }
        current.claimMutation();
        try {
            current.gameMode().stopDestroyBlock();
            receipt.finish(NativeActionReceipt.Status.CANCELLED,
                    pendingBreakCancellationReason);
        } catch (RuntimeException failure) {
            receipt.finish(NativeActionReceipt.Status.UNCERTAIN,
                    pendingBreakCancellationReason
                            + "; native mining cancellation could not be confirmed");
        } finally {
            pendingBreakCancellationReason = null;
        }
    }
}
