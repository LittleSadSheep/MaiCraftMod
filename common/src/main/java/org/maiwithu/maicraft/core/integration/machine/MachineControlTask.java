// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.task.ActualViewConvergenceGate;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.TaskState;

/**
 * One absolute control-state outcome, never a toggle script. A submitted lever use is reconciled
 * without replay; success proves the lever's state, not downstream wiring, production or safety.
 */
public final class MachineControlTask extends AbstractCompanionTask<MachineControlTaskRecord> {
    private static final double REACH = 4.5;
    private static final int CONFIRMATION_TICKS = 80;
    private enum Phase { SELECT, APPROACH, PREPARE_HAND, AIM, CONFIRM }

    private final ActualViewConvergenceGate aimGate = new ActualViewConvergenceGate();
    private Phase phase = Phase.SELECT;
    private BlockPos control;
    private BlockState before;
    private BlockState expected;
    private NativeActionReceipt receipt;
    private InteractionHand hand;
    private boolean controlAttempted;
    private boolean outcomeUncertain;
    private boolean controlStateVerified;
    private boolean alreadySatisfied;
    private int candidateCount;
    private int stanceDudTicks;
    private String failureCode;
    private String receiptStatus;

    public MachineControlTask(LocalPlayer player, MachineControlTaskRecord record) {
        super(player, record);
    }

    @Override protected TaskState onTick() {
        if (!r.request.dimension().equals(player.level().dimension().location().toString())) {
            return failure("machine_dimension_changed", "The surveyed machine belongs to a different dimension.",
                    FailureType.TARGET_LOST);
        }
        if (phase == Phase.CONFIRM) return confirm();
        if (player.containerMenu != player.inventoryMenu) {
            return failure("machine_menu_busy", "An unrelated menu is open; the machine control was not used.",
                    FailureType.UNKNOWN);
        }
        if (control != null && !targetUnchanged()) {
            return failure("machine_control_changed", "The selected lever changed before its use.",
                    FailureType.TARGET_LOST);
        }
        if (control != null && NavigationSafetyContext.protectsMutation(control)) {
            return failure("machine_control_protected", "The selected lever is inside an explicitly protected area.",
                    FailureType.UNSUPPORTED);
        }
        return switch (phase) {
            case SELECT -> select();
            case APPROACH -> approach();
            case PREPARE_HAND -> prepareHand();
            case AIM -> aimAndUse();
            case CONFIRM -> confirm();
        };
    }

    private TaskState select() {
        if (!freshSurvey()) return TaskState.FAILED;
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos center = r.request.center();
        int radius = r.request.radius();
        for (BlockPos position : BlockPos.betweenClosed(
                center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            if (player.level().getBlockState(position).is(Blocks.LEVER)) {
                candidates.add(position.immutable());
            }
        }
        candidateCount = candidates.size();
        control = MachineControl.selectControl(candidates, r.request.controlPosition());
        if (control == null) {
            return failure(candidateCount > 1 && r.request.controlPosition() == null
                            ? "ambiguous_machine_control" : "machine_control_unavailable",
                    candidateCount > 1 && r.request.controlPosition() == null
                            ? "Several levers are present; select one remembered control label. No switch was used."
                            : "The surveyed region does not contain the requested existing vanilla lever.",
                    FailureType.TARGET_LOST);
        }
        if (NavigationSafetyContext.protectsMutation(control)) {
            return failure("machine_control_protected", "The selected lever is explicitly protected.",
                    FailureType.UNSUPPORTED);
        }
        before = player.level().getBlockState(control);
        expected = before.setValue(LeverBlock.POWERED, r.request.desiredPowered());
        if (before.equals(expected)) {
            alreadySatisfied = true;
            controlStateVerified = true;
            return TaskState.SUCCESS;
        }
        phase = Phase.APPROACH;
        return TaskState.RUNNING;
    }

    private TaskState approach() {
        if (withinReach()) {
            stopNav();
            phase = Phase.PREPARE_HAND;
            return TaskState.RUNNING;
        }
        if (nav == null) {
            nav = PlayerNav.to(player, () -> GoalCompiler.interact(control), 1.0,
                    this::withinReach, PlayerNav.ContextProvider.DEFAULT);
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> ++stanceDudTicks < 10 ? TaskState.RUNNING
                    : failure("machine_control_out_of_reach", "No clear interaction stance reached the lever.",
                            FailureType.STANCE_DUD);
            case FAILED -> failure("machine_control_unreachable",
                    "The lever cannot be reached without changing terrain.", nav.failType());
        };
    }

    private TaskState prepareHand() {
        var context = ClientRuntime.requireContext(player);
        if (receipt != null) {
            receipt = context.actions().poll(context, receipt);
            if (!receipt.terminal()) return TaskState.RUNNING;
            receiptStatus = receipt.status().name().toLowerCase(java.util.Locale.ROOT);
            if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
                outcomeUncertain = true;
                return failure("machine_empty_hand_unconfirmed", "Empty-hand selection was not confirmed.",
                        FailureType.UNKNOWN);
            }
            receipt = null;
        }
        // An empty hand prevents a held mod item from intercepting use or placing something.
        // Vanilla's fallback useWithoutItem is entered only for MAIN_HAND, even if the offhand
        // is empty. Do not send an offhand packet that can never pull this lever.
        if (player.getMainHandItem().isEmpty()) hand = InteractionHand.MAIN_HAND;
        else {
            for (int slot = 0; slot < 9; slot++) {
                if (player.getInventory().getItem(slot).isEmpty()) {
                    if (!freshSurvey()) return TaskState.FAILED;
                    if (!context.mutationAvailable()) return TaskState.RUNNING;
                    receipt = context.actions().selectHotbar(context, slot, 20);
                    return TaskState.RUNNING;
                }
            }
            return failure("machine_empty_hand_required", "No empty main hand or empty hotbar slot is available for lever use.",
                    FailureType.NO_SPACE);
        }
        phase = Phase.AIM;
        return TaskState.RUNNING;
    }

    private TaskState aimAndUse() {
        if (!withinReach()) {
            aimGate.reset();
            phase = Phase.APPROACH;
            return TaskState.RUNNING;
        }
        if (!player.getItemInHand(hand).isEmpty()) {
            return failure("machine_control_hand_changed", "The selected empty hand changed before lever use.",
                    FailureType.UNKNOWN);
        }
        InputDriver.halt(player);
        // Wait for the native input tick to release secondary use, rather than accidentally
        // bypassing ordinary block interaction while approaching from a sneaking stance.
        if (player.isSecondaryUseActive()) return TaskState.RUNNING;
        Vec3 aim = aimPoint();
        InputDriver.lookAt(player, aim);
        if (!aimGate.ready(player, aim.subtract(player.getEyePosition()))) return TaskState.RUNNING;
        HitResult trace = Interaction.nativeRaytrace(player, REACH);
        if (!(trace instanceof BlockHitResult hit) || trace.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(control)) {
            return failure("machine_control_occluded", "The native crosshair does not hit the selected lever.",
                    FailureType.OCCLUDED);
        }
        var context = ClientRuntime.requireContext(player);
        if (!context.mutationAvailable()) return TaskState.RUNNING;
        if (!freshSurvey()) return TaskState.FAILED;
        // Set the latch BEFORE entering a native action: an exception cannot permit another use.
        controlAttempted = true;
        outcomeUncertain = true;
        phase = Phase.CONFIRM;
        receipt = context.actions().useBlock(context, hand, hit,
                NativeConfirmation.blockState(control, before, expected), CONFIRMATION_TICKS);
        return TaskState.RUNNING;
    }

    private TaskState confirm() {
        if (receipt == null) {
            return failure("machine_control_unconfirmed", "Lever use was entered without a complete native receipt; inspect before retrying.",
                    FailureType.UNKNOWN);
        }
        var context = ClientRuntime.requireContext(player);
        receipt = context.actions().poll(context, receipt);
        if (!receipt.terminal()) return TaskState.RUNNING;
        receiptStatus = receipt.status().name().toLowerCase(java.util.Locale.ROOT);
        if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            return failure("machine_control_unconfirmed", "The lever's requested state was not confirmed; no second toggle was attempted.",
                    FailureType.UNKNOWN);
        }
        // Vanilla LeverBlock changes POWERED only on the server; its client use emits particles.
        // The exact expected state must survive the native receipt's distinct-tick stability gate.
        if (!player.level().isLoaded(control) || !player.level().getBlockState(control).equals(expected)) {
            return failure("machine_control_state_diverged", "The lever changed again while its outcome was being confirmed.",
                    FailureType.TARGET_LOST);
        }
        outcomeUncertain = false;
        controlStateVerified = true;
        return TaskState.SUCCESS;
    }

    private boolean freshSurvey() {
        BlockPos center = r.request.center();
        int radius = r.request.radius();
        for (BlockPos position : BlockPos.betweenClosed(
                center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            if (player.level().isOutsideBuildHeight(position) || !player.level().isLoaded(position)) {
                failure("machine_region_unloaded", "The complete surveyed machine region is no longer loaded.",
                        FailureType.TARGET_LOST);
                return false;
            }
        }
        String live = MachineSurvey.fingerprint(player, center, radius);
        if (!r.request.structuralFingerprint().equals(live)) {
            failure("machine_structure_changed", "The machine structure changed since inspection; obtain a fresh survey before operating.",
                    FailureType.TARGET_LOST);
            return false;
        }
        return true;
    }

    private boolean targetUnchanged() {
        return player.level().isLoaded(control) && before != null
                && player.level().getBlockState(control).equals(before);
    }

    private Vec3 aimPoint() {
        return before.getShape(player.level(), control).bounds().getCenter()
                .add(control.getX(), control.getY(), control.getZ());
    }

    private boolean withinReach() {
        if (control == null || !targetUnchanged()) return false;
        Vec3 eye = player.getEyePosition();
        Vec3 aim = aimPoint();
        if (eye.distanceToSqr(aim) > REACH * REACH) return false;
        BlockHitResult hit = player.level().clip(new ClipContext(
                eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(control);
    }

    private TaskState failure(String code, String message, FailureType type) {
        failureCode = code;
        fail(message, type);
        return TaskState.FAILED;
    }

    @Override public boolean mustSettleBeforeSatisfiedCancellation() {
        return controlAttempted && !controlStateVerified && receipt != null && !receipt.terminal();
    }

    @Override protected void cleanup() {
        super.cleanup();
        aimGate.reset();
        if (receipt != null && !receipt.terminal()) {
            try {
                var context = ClientRuntime.requireContext(player);
                receipt = context.actions().retireOneShotForTaskBoundary(context, receipt,
                        "machine control task ended before confirmation");
                receiptStatus = receipt.status().name().toLowerCase(java.util.Locale.ROOT);
            } catch (RuntimeException unavailable) {
                // The runtime retires old-body receipts; cleanup must preserve uncertain evidence.
            }
            outcomeUncertain = true;
        }
        if (controlAttempted && !controlStateVerified) outcomeUncertain = true;
    }
