// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.movements.MovementFall;
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
import org.maiwithu.maicraft.core.pathing.baritone.landing.LandingAssistPolicy;
import org.maiwithu.maicraft.core.pathing.moves.movements.BuildPlacementRegistry;

/**
 * 把 Baritone 的左键、右键和快捷栏选择请求转成项目统一的原生操作，并跟踪每次操作的观察结果。
 * 普通移动按键由运行器另行提交；落地救援在这里优先交给专门控制器，避免与普通点击抢操作机会。
 */
final class EmbeddedBaritoneActionBridge {
    private static final int USE_CONFIRM_TICKS = 20;
    private static final int HOTBAR_CONFIRM_TICKS = 10;

    private enum PendingKind { BREAK, BLOCK_USE, HOTBAR }

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
    boolean pending() { return receipt != null; }

    void tick(
            LocalPlayerContext context,
            EmbeddedBaritoneNavigator navigator,
            InputOverrideHandler input) {
        if (context == null || navigator == null || context.player() == null) return;
        // 前一位导航还有操作记录时先处理它，不能把旧结果算给新导航。
        if (receiptOwner != null && receiptOwner != navigator) {
            suspend(context, receiptOwner, "navigation ownership changed");
            if (receipt != null) return;
        }

        settle(context);
        if (rightClickCooldown > 0) rightClickCooldown--;
        MovementFall activeFall = EmbeddedBaritoneRuntime.currentFall(navigator);
        if (receipt == null && activeFall != null && activeFall.landingBoat() != null) {
            activeFall.tickLandingBoat(context);
            var boat = activeFall.landingBoat();
            input.setInputForceState(Input.SNEAK, boat.wantsSneak());
            LandingAssistPolicy.report(boat.diagnostics());
            return;
        }
        if (receipt == null && activeFall != null && activeFall.landingAssist() != null) {
            var assist = activeFall.landingAssist();
            assist.tick(context);
            // 放置可能需要副手使用键，但刚放下的黏液块会在下次实际着陆前松开该键，无需等待回执驻留时间。
            if (!context.player().onGround()) input.setInputForceState(Input.SNEAK, assist.wantsSneak(context));
            LandingAssistPolicy.report(assist.diagnostics());
            for (var change : assist.drainChanges()) {
                navigator.recordConfirmedNativeAction();
                if (change.before().getBlock() != change.after().getBlock()) {
                    if (!change.before().isAir()) navigator.recordConfirmedBreak(change.position(), change.before());
                    if (!change.after().isAir()) navigator.recordConfirmedPlace(change.position(), change.after());
                }
            }
            return;
        }

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
            }
        } catch (RuntimeException unavailable) {
            // 其他行为可能先取得本 tick 控制权，随后才到达边界；保留回执，以便之后由拥有控制权的 tick 通过权威角色边界轮询完成结算。
        }
    }

    /** 即使导航器已释放运行时所有权，也继续回收该回执。 */
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

    // 持续挖掘每次都重新检查按键、准星目标和保护许可；条件不再满足时发出停止挖掘。
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
        // 移动指令会在此动作阶段之后提交。等待真实角色松开副手使用键，否则原版可能跳过开门并放置手持方块。
        if (openable && !passageUseReady(sneakRequested, context.player().isSecondaryUseActive())) return;
        InteractionHand hand = chooseUseHand(clickedState, sneakRequested,
                context.player().getMainHandItem(), context.player().getOffhandItem());
        ItemStack held = context.player().getItemInHand(hand);
        boolean terrainItem = held.getItem() instanceof BlockItem;

        // 普通开门走通行交互分支，检查身体禁入范围；它与挖掘、放置所需的地形修改许可不同。
        if (openable) {
            BlockPos otherHalf = otherDoorHalf(clicked, clickedState);
            // 保护结构免受挖掘或放置影响时，仍必须允许操作其中的门。
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
        if (!BuildPlacementRegistry.scaffoldUseAllowed(context.player(), held)) return;
        BlockPos actual = placementCell(clicked, clickedState, hit);
        if (!navigator.permitsTemporaryScaffold(actual)) { navigator.rejectedTemporaryScaffold(actual); return; }
        boolean protectedSupport = EmbeddedBaritonePolicy.protects(clicked);
        if (EmbeddedBaritonePolicy.protects(actual) || protectedSupport
                && (!context.player().isSecondaryUseActive() || actual.equals(clicked)
                || !navigator.permitsScaffoldSupport(clicked, actual, clickedState))) {
            return;
        }
        BlockState actualBefore = context.level().getBlockState(actual);
        submitBlockUse(context, navigator, hand, hit, clicked, clickedState,
                actual, actualBefore, true, protectedSupport);
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
        submitBlockUse(context, navigator, hand, hit, clicked, beforeClicked,
                actual, beforeActual, mutatesTerrain, false);
    }

    private void submitBlockUse(LocalPlayerContext context, EmbeddedBaritoneNavigator navigator,
            InteractionHand hand, BlockHitResult hit, BlockPos clicked, BlockState beforeClicked,
            BlockPos actual, BlockState beforeActual, boolean mutatesTerrain, boolean preserveSupport) {
        List<NativeConfirmation> confirmations = new ArrayList<>();
        // 当前右键只看点击格或预计作用格有没有变化；这里没有逐项检查最终方块，也没有声明必须等待服务器方块序号确认。
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
                    preserveSupport ? fresh -> {
                        if (!fresh.level().isLoaded(clicked) || !fresh.level().isLoaded(actual))
                            return NativeConfirmation.Verdict.PENDING;
                        if (!fresh.level().getBlockState(clicked).equals(beforeClicked))
                            return NativeConfirmation.Verdict.DIVERGED;
                        return fresh.level().getBlockState(actual).equals(beforeActual)
                                ? NativeConfirmation.Verdict.PENDING : NativeConfirmation.Verdict.APPLIED;
                    } : NativeConfirmation.anyOf(confirmations.toArray(NativeConfirmation[]::new)),
                    USE_CONFIRM_TICKS);
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

    // 确认成功才把动作计入导航进展和改动记录；当前失败或不确定记录也会在末尾清掉，后续仍有按键请求时可能再次提交。
    private void settle(LocalPlayerContext context) {
        NativeActionReceipt current = receipt;
        if (current == null) return;
        if (!current.terminal() && pendingKind == PendingKind.BREAK
                && receiptOwner != null
                && context.gameMode() instanceof IPlayerControllerMP controller
                && controller.isHittingBlock()
                && breakTarget.equals(controller.getCurrentBlock())) {
            float progress = controller.getDestroyProgress();
            // 持续按住鼠标键不算进展。重试破坏同一方块时保留最高破坏进度，避免服务器拒绝操作后仍让导航永远保持活动。
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
            if (pendingKind == PendingKind.BREAK || pendingKind == PendingKind.BLOCK_USE) {
                receiptOwner.recordConfirmedNativeAction();
            }
            if (pendingKind == PendingKind.BREAK
                    && breakTarget != null && breakBefore != null) {
                receiptOwner.recordConfirmedBreak(breakTarget, breakBefore);
                // 后续同一坐标上的替换方块属于新的挖掘事件。失败尝试保留最高进度，但确认破坏后即结束本次事件。
                breakProgress.reset();
            } else if (pendingKind == PendingKind.BLOCK_USE && terrainUse) {
                recordWorldDelta(context, receiptOwner, clickedCell, clickedBefore);
                if (placedCell != null && !placedCell.equals(clickedCell)) {
                    recordWorldDelta(context, receiptOwner, placedCell, placedBefore);
                }
            }
        }
        clearReceipt();
    }

    // 按观察到的空气、实体方块和流体变化记账；这只记录该操作窗口里的差异，不能证明每个变化都来自这次点击。
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
        // 原版只会在 MAIN_HAND 调用门的 useWithoutItem。若此处使用副手方块，会绕过开门并进入物品放置回退流程。
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

    /** 跨重试跟踪一次挖掘，直到确认方块已被移除。 */
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
     * 根据原版实时逐刻破坏进度设置确认期限，而不是施加固定墙上时间限制。这样使用差工具破坏坚硬方块等较慢但有效的操作仍可完成；
     * 额外半段时长和同步余量仍会限制服务器事实始终未到达的回执。
     */
    private static int breakConfirmationTicks(float destroyProgress) {
        long expected = Math.max(1L, (long) Math.ceil(1.0D / destroyProgress));
        long synchronizationMargin = Math.max(40L, expected / 2L);
        return (int) Math.min(Integer.MAX_VALUE, expected + synchronizationMargin);
    }
}
