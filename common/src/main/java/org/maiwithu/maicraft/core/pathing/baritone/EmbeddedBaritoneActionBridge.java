// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.utils.input.Input;
import baritone.utils.InputOverrideHandler;
import baritone.utils.accessor.IPlayerControllerMP;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;

/**
 * Serializes Baritone's hand gestures through MaiCraft's one native-action slot.
 *
 * <p>Baritone still decides which low-level movement input it wants, but it never calls the game
 * mode directly. A click becomes a cross-tick receipt and only synchronized world facts may add a
 * mutation to the owning navigator's {@code TerrainBill}.</p>
 */
final class EmbeddedBaritoneActionBridge {
    private static final int USE_CONFIRM_TICKS = 20;
    private static final int HOTBAR_CONFIRM_TICKS = 10;

    private enum PendingKind { BREAK, BLOCK_USE, ITEM_USE, HOTBAR }

    private NativeActionReceipt receipt;
    private PendingKind pendingKind;
    private EmbeddedBaritoneNavigator receiptOwner;
    private BlockPos breakTarget;
    private BlockState breakBefore;
    private BlockPos clickedCell;
    private BlockState clickedBefore;
    private BlockPos placedCell;
    private BlockState placedBefore;
    private boolean terrainUse;
    private int hotbarTarget = -1;
    private int rightClickCooldown;
    private boolean stopBreakingRequested;
    private final BreakProgress breakProgress = new BreakProgress();

    void tick(
            LocalPlayerContext context,
            EmbeddedBaritoneNavigator navigator,
            InputOverrideHandler input) {
        if (context == null || navigator == null || context.player() == null) return;
        if (receiptOwner != null && receiptOwner != navigator) {
            suspend(context, receiptOwner, "navigation ownership changed");
            if (receipt != null) return;
        }

        settle(context);
        if (rightClickCooldown > 0) rightClickCooldown--;

        boolean left = input.isInputForcedDown(Input.CLICK_LEFT);
        boolean right = input.isInputForcedDown(Input.CLICK_RIGHT) && !left;
        if (receipt != null) {
            if (pendingKind == PendingKind.BREAK) {
                continueOrCancelBreak(context, left);
            }
            return;
        }
        if (!context.mutationAvailable()) return;

        if (left) {
            startBreak(context, navigator);
        } else if (right && rightClickCooldown == 0) {
            startUse(context, navigator, input.isInputForcedDown(Input.SNEAK));
        }
    }

    boolean ensureHotbarSelected(
            LocalPlayerContext context,
            EmbeddedBaritoneNavigator navigator,
            LocalPlayer player,
            int slot) {
        if (context == null || navigator == null || player == null
                || context.player() != player || slot < 0 || slot >= 9) {
            return false;
        }
        if (receiptOwner != null && receiptOwner != navigator) {
            suspend(context, receiptOwner, "navigation ownership changed before hotbar selection");
        }
        settle(context);
        if (player.getInventory().selected == slot && pendingKind != PendingKind.HOTBAR) {
            return true;
        }
        if (receipt != null) {
            return pendingKind == PendingKind.HOTBAR
                    && hotbarTarget == slot
                    && receipt.terminal()
                    && receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED;
        }
        if (!context.mutationAvailable()) return false;
        try {
            hotbarTarget = slot;
            receiptOwner = navigator;
            pendingKind = PendingKind.HOTBAR;
            receipt = context.actions().selectHotbar(context, slot, HOTBAR_CONFIRM_TICKS);
            settle(context);
        } catch (RuntimeException unavailable) {
            clearReceipt();
        }
        return receipt == null && player.getInventory().selected == slot;
    }

    void requestStopBreaking() {
        if (pendingKind == PendingKind.BREAK && receipt != null) {
            stopBreakingRequested = true;
        }
    }

    void suspend(
            LocalPlayerContext context,
            EmbeddedBaritoneNavigator navigator,
            String reason) {
        if (navigator == null || receiptOwner != navigator || receipt == null) return;
        if (context == null || !context.isCurrent() || context.player() == null) {
            return;
        }
        settle(context);
        if (receipt == null || receiptOwner != navigator) return;
        try {
            switch (pendingKind) {
                case BREAK -> {
                    receipt = context.actions().cancelBreakingForTaskBoundary(
                            context, receipt, reason);
                    settle(context);
                }
                case BLOCK_USE, HOTBAR -> {
                    receipt = context.actions().retireOneShotForTaskBoundary(
                            context, receipt, reason);
                    settle(context);
                }
                case ITEM_USE -> {
                    // Buckets are instant uses, but USE_ITEM is conservatively stoppable. Observe
                    // the world first; only submit a physical release when it is still pending and
                    // this tick owns the mutation slot.
                    if (!receipt.terminal() && context.mutationAvailable()) {
                        NativeActionReceipt release = context.actions().releaseUsingItem(
                                context, receipt);
                        receipt = context.actions().retireOneShotForTaskBoundary(
                                context, release, reason + " after physically releasing item use");
                        clearReceipt();
                    }
                }
            }
        } catch (RuntimeException unavailable) {
            // A boundary may arrive after another winner claimed this tick. Keep the receipt so
            // its authoritative actor-boundary polling can settle it on a later owned tick.
        }
    }

    /** Keep retiring a receipt after its navigator has already released runtime ownership. */
    void suspendAny(LocalPlayerContext context, String reason) {
        EmbeddedBaritoneNavigator navigator = receiptOwner;
        if (navigator != null) suspend(context, navigator, reason);
    }

    void bodyGone() {
        clearReceipt();
        breakProgress.reset();
        rightClickCooldown = 0;
        stopBreakingRequested = false;
    }

    private void startBreak(
            LocalPlayerContext context,
            EmbeddedBaritoneNavigator navigator) {
        if (!BaritoneAPI.getSettings().allowBreak.value) return;
        HitResult trace = navigator.objectMouseOver();
        if (!(trace instanceof BlockHitResult hit)
                || trace.getType() != HitResult.Type.BLOCK
                || EmbeddedBaritonePolicy.protects(hit.getBlockPos())) {
            return;
        }
        BlockState before = context.level().getBlockState(hit.getBlockPos());
        if (before.isAir()) return;
        float destroyProgress = before.getDestroyProgress(
                context.player(), context.level(), hit.getBlockPos());
        if (!(destroyProgress > 0.0F) || !Float.isFinite(destroyProgress)) return;
        try {
            receiptOwner = navigator;
            pendingKind = PendingKind.BREAK;
            breakTarget = hit.getBlockPos().immutable();
            breakBefore = before;
            breakProgress.begin(breakTarget, before);
            stopBreakingRequested = false;
            receipt = context.actions().startBreaking(
                    context, hit, breakConfirmationTicks(destroyProgress));
            settle(context);
        } catch (RuntimeException unavailable) {
            clearReceipt();
        }
    }

    private void continueOrCancelBreak(LocalPlayerContext context, boolean leftRequested) {
        if (receipt == null || pendingKind != PendingKind.BREAK) return;
        HitResult trace = receiptOwner == null ? null : receiptOwner.objectMouseOver();
        boolean sameTarget = trace instanceof BlockHitResult hit
                && trace.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(breakTarget);
        boolean allowed = BaritoneAPI.getSettings().allowBreak.value
                && !EmbeddedBaritonePolicy.protects(breakTarget);
        if (stopBreakingRequested || !leftRequested || !sameTarget || !allowed) {
            if (!context.mutationAvailable()) return;
            try {
                receipt = context.actions().cancelBreaking(context, receipt);
                settle(context);
            } catch (RuntimeException unavailable) {
                stopBreakingRequested = true;
            }
            return;
        }
        if (!context.mutationAvailable()) return;
        try {
            receipt = context.actions().continueBreaking(context, receipt);
            settle(context);
        } catch (RuntimeException unavailable) {
            stopBreakingRequested = true;
        }
    }

    private void startUse(
            LocalPlayerContext context,
            EmbeddedBaritoneNavigator navigator,
            boolean sneakRequested) {
        if (context.player().isHandsBusy()) return;
        HitResult trace = navigator.objectMouseOver();
        if (!(trace instanceof BlockHitResult hit)
                || trace.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos clicked = hit.getBlockPos().immutable();
        BlockState clickedState = context.level().getBlockState(clicked);
        boolean openable = isHandOpenable(clickedState) && !sneakRequested;
        // The movement command is submitted after this action phase. Wait for the real body to
        // release secondary use; otherwise vanilla skips the door and may place a held block.
        if (openable && !passageUseReady(sneakRequested, context.player().isSecondaryUseActive())) return;
        InteractionHand hand = chooseUseHand(clickedState, sneakRequested,
                context.player().getMainHandItem(), context.player().getOffhandItem());
        ItemStack held = context.player().getItemInHand(hand);
        boolean terrainItem = held.getItem() instanceof BlockItem
                || held.getItem() instanceof BucketItem;

        if (openable) {
            BlockPos otherHalf = otherDoorHalf(clicked, clickedState);
            // Protecting a structure from mining/placement must still allow its doors to work.
            if (EmbeddedBaritonePolicy.forbidsBody(clicked)
                    || EmbeddedBaritonePolicy.forbidsBody(otherHalf)) {
                return;
            }
            submitBlockUse(context, navigator, hand, hit, clicked, clickedState,
                    otherHalf, otherHalf == null ? null : context.level().getBlockState(otherHalf),
                    false);
            return;
        }

        if (!terrainItem
                || navigator.permit() != TerrainPermit.TERRAFORM
                || !BaritoneAPI.getSettings().allowPlace.value) {
            return;
        }
        BlockPos actual = placementCell(clicked, clickedState, hit);
        if (EmbeddedBaritonePolicy.protects(clicked)
                || EmbeddedBaritonePolicy.protects(actual)) {
            return;
        }
        BlockState actualBefore = context.level().getBlockState(actual);
        if (held.getItem() instanceof BucketItem) {
            submitItemUse(context, navigator, hand, clicked, clickedState, actual, actualBefore);
        } else {
            submitBlockUse(context, navigator, hand, hit, clicked, clickedState,
                    actual, actualBefore, true);
        }
    }

    private void submitBlockUse(
            LocalPlayerContext context,
            EmbeddedBaritoneNavigator navigator,
            InteractionHand hand,
            BlockHitResult hit,
            BlockPos clicked,
            BlockState beforeClicked,
            BlockPos actual,
            BlockState beforeActual,
            boolean mutatesTerrain) {
        List<NativeConfirmation> confirmations = new ArrayList<>();
        confirmations.add(NativeConfirmation.blockChanged(clicked, beforeClicked));
        if (actual != null && !actual.equals(clicked) && beforeActual != null) {
            confirmations.add(NativeConfirmation.blockChanged(actual, beforeActual));
        }
        try {
            rememberUse(navigator, clicked, beforeClicked, actual, beforeActual, mutatesTerrain);
            pendingKind = PendingKind.BLOCK_USE;
            receipt = context.actions().useBlock(
                    context,
                    hand,
                    hit,
                    NativeConfirmation.anyOf(confirmations.toArray(NativeConfirmation[]::new)),
                    USE_CONFIRM_TICKS);
            rightClickCooldown = Math.max(0,
                    BaritoneAPI.getSettings().rightClickSpeed.value - 1);
            settle(context);
        } catch (RuntimeException unavailable) {
            clearReceipt();
        }
    }

    private void submitItemUse(
            LocalPlayerContext context,
            EmbeddedBaritoneNavigator navigator,
            InteractionHand hand,
            BlockPos clicked,
            BlockState beforeClicked,
            BlockPos actual,
            BlockState beforeActual) {
        NativeConfirmation confirmation = NativeConfirmation.anyOf(
                NativeConfirmation.blockChanged(clicked, beforeClicked),
                NativeConfirmation.blockChanged(actual, beforeActual));
        try {
            rememberUse(navigator, clicked, beforeClicked, actual, beforeActual, true);
            pendingKind = PendingKind.ITEM_USE;
            receipt = context.actions().useItem(
                    context, hand, confirmation, USE_CONFIRM_TICKS);
            rightClickCooldown = Math.max(0,
                    BaritoneAPI.getSettings().rightClickSpeed.value - 1);
            settle(context);
        } catch (RuntimeException unavailable) {
            clearReceipt();
        }
    }

    private void rememberUse(
            EmbeddedBaritoneNavigator navigator,
            BlockPos clicked,
            BlockState beforeClicked,
            BlockPos actual,
            BlockState beforeActual,
            boolean mutatesTerrain) {
        receiptOwner = navigator;
        clickedCell = clicked;
        clickedBefore = beforeClicked;
        placedCell = actual;
        placedBefore = beforeActual;
        terrainUse = mutatesTerrain;
    }

    private void settle(LocalPlayerContext context) {
        NativeActionReceipt current = receipt;
        if (current == null) return;
        if (!current.terminal() && pendingKind == PendingKind.BREAK
                && receiptOwner != null
                && context.gameMode() instanceof IPlayerControllerMP controller
                && controller.isHittingBlock()
                && breakTarget.equals(controller.getCurrentBlock())) {
            float progress = controller.getDestroyProgress();
            // A held mouse button is not progress. Keep the high-water mark across retries of
            // this block so a server-rejected break cannot keep the navigation alive forever.
            if (breakProgress.observe(progress)) {
                receiptOwner.recordConfirmedNativeAction();
            }
        }
        if (!current.terminal()) {
            try {
                current = context.actions().poll(context, current);
                receipt = current;
            } catch (RuntimeException unavailable) {
                return;
            }
        }
        if (!current.terminal()) return;
        if (current.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED
                && receiptOwner != null) {
            if (pendingKind == PendingKind.BREAK
                    || pendingKind == PendingKind.BLOCK_USE
                    || pendingKind == PendingKind.ITEM_USE) {
                receiptOwner.recordConfirmedNativeAction();
            }
            if (pendingKind == PendingKind.BREAK
                    && breakTarget != null && breakBefore != null) {
                receiptOwner.recordConfirmedBreak(breakTarget, breakBefore);
                // A later replacement at the same coordinate starts a new excavation. Failed
                // attempts retain their high-water mark, but a confirmed break ends that episode.
                breakProgress.reset();
            } else if ((pendingKind == PendingKind.BLOCK_USE
                    || pendingKind == PendingKind.ITEM_USE) && terrainUse) {
                recordWorldDelta(context, receiptOwner, clickedCell, clickedBefore);
                if (placedCell != null && !placedCell.equals(clickedCell)) {
                    recordWorldDelta(context, receiptOwner, placedCell, placedBefore);
                }
            }
        }
        clearReceipt();
    }

    private static void recordWorldDelta(
            LocalPlayerContext context,
            EmbeddedBaritoneNavigator navigator,
            BlockPos cell,
            BlockState before) {
        if (cell == null || before == null || !context.level().isLoaded(cell)) return;
        BlockState after = context.level().getBlockState(cell);
        if (after.equals(before)) return;
        boolean removedSolid = !before.isAir() && after.isAir();
        boolean removedFluid = !before.getFluidState().isEmpty()
                && after.getFluidState().isEmpty();
        if (removedSolid || removedFluid) {
            navigator.recordConfirmedBreak(cell, before);
        }
        boolean placedSolid = before.canBeReplaced() && !after.canBeReplaced();
        boolean placedFluid = before.getFluidState().isEmpty()
                && !after.getFluidState().isEmpty();
        if (placedSolid || placedFluid) {
            navigator.recordConfirmedPlace(cell, after);
        }
    }

    private void clearReceipt() {
        receipt = null;
        pendingKind = null;
        receiptOwner = null;
        breakTarget = null;
        breakBefore = null;
        clickedCell = null;
        clickedBefore = null;
        placedCell = null;
        placedBefore = null;
        terrainUse = false;
        hotbarTarget = -1;
        stopBreakingRequested = false;
    }

    static InteractionHand chooseUseHand(BlockState clicked, boolean sneakRequested,
                                         ItemStack main, ItemStack off) {
        // Vanilla invokes a door's useWithoutItem only for MAIN_HAND. An offhand block here
        // would bypass opening and reach the item's placement fallback instead.
        if (isHandOpenable(clicked) && !sneakRequested) return InteractionHand.MAIN_HAND;
        if (main.getItem() instanceof BlockItem || main.getItem() instanceof BucketItem) {
            return InteractionHand.MAIN_HAND;
        }
        if (off.getItem() instanceof BlockItem || off.getItem() instanceof BucketItem) {
            return InteractionHand.OFF_HAND;
        }
        return InteractionHand.MAIN_HAND;
    }

    static boolean passageUseReady(boolean sneakRequested, boolean actualSecondaryUse) {
        return !sneakRequested && !actualSecondaryUse;
    }

    /** Tracks one excavation across retries, until its block is confirmed removed. */
    static final class BreakProgress {
        private BlockPos target;
        private BlockState state;
        private float best;

        void begin(BlockPos nextTarget, BlockState nextState) {
            if (!nextTarget.equals(target) || !nextState.equals(state)) {
                target = nextTarget.immutable();
                state = nextState;
                best = 0;
            }
        }

        boolean observe(float value) {
            if (!Float.isFinite(value) || value <= best) return false;
            best = value;
            return true;
        }

        void reset() {
            target = null;
            state = null;
            best = 0;
        }
    }

    static boolean isHandOpenable(BlockState state) {
        return state.getBlock() instanceof DoorBlock door && door.type().canOpenByHand()
                || state.getBlock() instanceof FenceGateBlock;
    }

    private static BlockPos otherDoorHalf(BlockPos clicked, BlockState state) {
        if (!(state.getBlock() instanceof DoorBlock)
                || !state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return null;
        }
        return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                ? clicked.above() : clicked.below();
    }

    private static BlockPos placementCell(
            BlockPos clicked,
            BlockState clickedState,
            BlockHitResult hit) {
        return (clickedState.canBeReplaced()
                ? clicked : clicked.relative(hit.getDirection())).immutable();
    }

    /**
     * Size the confirmation lease from vanilla's live per-tick destroy progress instead of
     * imposing a fixed wall-clock cutoff. Slow but valid work (for example a poor tool against a
     * hard block) therefore remains valid, while the extra half-duration plus synchronization
     * margin still bounds a receipt whose server facts never arrive.
     */
    private static int breakConfirmationTicks(float destroyProgress) {
        long expected = Math.max(1L, (long) Math.ceil(1.0D / destroyProgress));
        long synchronizationMargin = Math.max(40L, expected / 2L);
        return (int) Math.min(Integer.MAX_VALUE, expected + synchronizationMargin);
    }
}
