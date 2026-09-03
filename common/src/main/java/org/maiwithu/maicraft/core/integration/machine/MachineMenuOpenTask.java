// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
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

/** Exact observed machine approach/open; leaves the native menu available for a later receipt. */
public final class MachineMenuOpenTask extends AbstractCompanionTask<MachineMenuOpenTaskRecord> {
    private static final double REACH = 4.5;
    private enum Phase { START, APPROACH, HAND, AIM, CONFIRM }
    private final ActualViewConvergenceGate aimGate = new ActualViewConvergenceGate();
    private Phase phase = Phase.START;
    private BlockState before;
    private ResourceLocation blockId;
    private NativeActionReceipt receipt;
    private boolean openAttempted;
    private boolean verified;
    private int dudTicks;
    private String failureCode;
    private JsonObject menuReport;

    public MachineMenuOpenTask(LocalPlayer player, MachineMenuOpenTaskRecord record) { super(player, record); }

    @Override protected TaskState onTick() {
        BlockPos position = r.request.machinePosition();
        if (!r.request.dimension().equals(player.level().dimension().location().toString())
                || !player.level().isLoaded(position)) {
            return failure("machine_menu_target_lost", "The inspected machine is no longer loaded in this dimension.", FailureType.TARGET_LOST);
        }
        if (phase == Phase.CONFIRM) return confirm();
        if (player.containerMenu != player.inventoryMenu) {
            return failure("machine_menu_busy", "An unrelated menu is already open.", FailureType.UNKNOWN);
        }
        if (NavigationSafetyContext.protectsMutation(position)) {
            return failure("machine_menu_protected", "The selected machine is explicitly protected from use.", FailureType.UNSUPPORTED);
        }
        if (before != null && !player.level().getBlockState(position).equals(before)) {
            return failure("machine_menu_target_changed", "The observed machine changed before opening.", FailureType.TARGET_LOST);
        }
        return switch (phase) {
            case START -> startOpening();
            case APPROACH -> approach();
            case HAND -> prepareHand();
            case AIM -> aimAndOpen();
            case CONFIRM -> confirm();
        };
    }

    private TaskState startOpening() {
        if (!fresh()) return TaskState.FAILED;
        BlockPos position = r.request.machinePosition();
        before = player.level().getBlockState(position);
        blockId = BuiltInRegistries.BLOCK.getKey(before.getBlock());
        if (before.isAir() || (player.level().getBlockEntity(position) == null
                && before.getMenuProvider(player.level(), position) == null)) {
            return failure("machine_menu_unavailable", "The selected observed block has no machine or container entity.", FailureType.UNSUPPORTED);
        }
        phase = Phase.APPROACH;
        return TaskState.RUNNING;
    }

    private TaskState approach() {
        if (inReach()) {
            stopNav(); phase = Phase.HAND; return TaskState.RUNNING;
        }
        if (nav == null) nav = PlayerNav.to(player, () -> GoalCompiler.interact(r.request.machinePosition()),
                1.0, this::inReach, PlayerNav.ContextProvider.DEFAULT);
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> ++dudTicks < 10 ? TaskState.RUNNING : failure("machine_menu_no_stance",
                    "No clear interaction stance reached the selected machine.", FailureType.STANCE_DUD);
            case FAILED -> failure("machine_menu_unreachable", "The machine cannot be reached without changing terrain.", nav.failType());
        };
    }

    private TaskState prepareHand() {
        var context = ClientRuntime.requireContext(player);
        if (receipt != null) {
            receipt = context.actions().poll(context, receipt);
            if (!receipt.terminal()) return TaskState.RUNNING;
            if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
                return failure("machine_menu_hand_unconfirmed", "Empty-hand selection was not confirmed.", FailureType.UNKNOWN);
            }
            receipt = null;
        }
        if (player.getMainHandItem().isEmpty()) { phase = Phase.AIM; return TaskState.RUNNING; }
        for (int index = 0; index < 9; index++) {
            if (player.getInventory().getItem(index).isEmpty()) {
                if (!fresh()) return TaskState.FAILED;
                if (!context.mutationAvailable()) return TaskState.RUNNING;
                receipt = context.actions().selectHotbar(context, index, 20);
                return TaskState.RUNNING;
            }
        }
        return failure("machine_menu_empty_hand_required", "An empty main hand or empty hotbar entry is required for ordinary machine use.", FailureType.NO_SPACE);
    }

    private TaskState aimAndOpen() {
        if (!inReach()) { phase = Phase.APPROACH; aimGate.reset(); return TaskState.RUNNING; }
        if (!player.getMainHandItem().isEmpty()) return failure("machine_menu_hand_changed",
                "The empty main hand changed before opening.", FailureType.UNKNOWN);
        InputDriver.halt(player);
        if (player.isSecondaryUseActive()) return TaskState.RUNNING;
        Vec3 aim = aim();
        InputDriver.lookAt(player, aim);
        if (!aimGate.ready(player, aim.subtract(player.getEyePosition()))) return TaskState.RUNNING;
        HitResult trace = Interaction.nativeRaytrace(player, REACH);
        if (!(trace instanceof BlockHitResult hit) || trace.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(r.request.machinePosition())) {
            return failure("machine_menu_occluded", "The native crosshair does not hit the selected machine.", FailureType.OCCLUDED);
        }
        var context = ClientRuntime.requireContext(player);
        if (!context.mutationAvailable()) return TaskState.RUNNING;
        if (!fresh()) return TaskState.FAILED;
        openAttempted = true; phase = Phase.CONFIRM;
        receipt = context.actions().useBlock(context, InteractionHand.MAIN_HAND, hit,
                NativeConfirmation.menuChanged(player.inventoryMenu.containerId), 80);
        return TaskState.RUNNING;
    }

    private TaskState confirm() {
        if (receipt == null) return failure("machine_menu_open_unconfirmed",
                "Machine use entered without a complete receipt; no second use was attempted.", FailureType.UNKNOWN);
        var context = ClientRuntime.requireContext(player);
        receipt = context.actions().poll(context, receipt);
        if (!receipt.terminal()) return TaskState.RUNNING;
        if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED
                || player.containerMenu == player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()
                || !BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(r.request.machinePosition()).getBlock()).equals(blockId)) {
            return failure("machine_menu_open_unconfirmed", "No empty-cursor native menu was confirmed for the selected machine; no second use was attempted.", FailureType.UNKNOWN);
        }
        if (!context.menus().ensureVisible(context)) return TaskState.RUNNING;
        MachineMenu.bindOpened(player, player.containerMenu, r.request, blockId);
        menuReport = MachineMenu.inspect(player);
        verified = true;
        return TaskState.SUCCESS;
    }

    private boolean fresh() {
        int radius = r.request.radius();
        BlockPos center = r.request.center();
        for (BlockPos position : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            if (player.level().isOutsideBuildHeight(position) || !player.level().isLoaded(position)) {
                failure("machine_menu_region_incomplete", "The surveyed machine volume is not fully loaded.", FailureType.TARGET_LOST); return false;
            }
        }
        if (!r.request.structuralFingerprint().equals(MachineSurvey.fingerprint(player, center, radius))) {
            failure("machine_menu_structure_changed", "The machine structure changed; inspect it before opening.", FailureType.TARGET_LOST); return false;
        }
        return true;
    }

    private Vec3 aim() {
        var shape = before.getShape(player.level(), r.request.machinePosition());
        return shape.isEmpty() ? Vec3.atCenterOf(r.request.machinePosition())
                : shape.bounds().getCenter().add(r.request.machinePosition().getX(), r.request.machinePosition().getY(), r.request.machinePosition().getZ());
    }

    private boolean inReach() {
        Vec3 eye = player.getEyePosition(); Vec3 aim = aim();
        if (eye.distanceToSqr(aim) > REACH * REACH) return false;
        var hit = player.level().clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(r.request.machinePosition());
    }

    private TaskState failure(String code, String message, FailureType type) {
        failureCode = code; fail(message, type); return TaskState.FAILED;
    }

    @Override protected void cleanup() {
        super.cleanup(); aimGate.reset();
        if (receipt != null && !receipt.terminal()) {
            try {
                var context = ClientRuntime.requireContext(player);
                context.actions().retireOneShotForTaskBoundary(context, receipt, "machine menu opening ended");
            } catch (RuntimeException unavailable) { /* The actor owns old-body receipt retirement. */ }
        }
        if (openAttempted && !verified && player.containerMenu != player.inventoryMenu) {
            try {
                var context = ClientRuntime.requireContext(player);
                context.menus().closeForTaskBoundary(context, 40, "machine menu opening did not complete");
            } catch (RuntimeException unavailable) { /* Body/control handoff owns the old menu. */ }
        }
    }

    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("menu_open_verified", verified);
        data.put("effects_started", openAttempted);
        data.put("outcome_uncertain", openAttempted && !verified);
        data.put("mechanical_retry_allowed", !openAttempted);
        data.put("machine_production_verified", false);
        if (menuReport != null) data.put("menu_report", menuReport);
        if (failureCode != null) data.put("failure_code", failureCode);
        return data;
    }

    @Override public boolean mustSettleBeforeSatisfiedCancellation() { return openAttempted && !verified && receipt != null && !receipt.terminal(); }
    @Override protected String successMessage() { return "Opened and inspected the selected machine's native menu; item entries and data values are observed, recipe roles remain to be analyzed."; }
    @Override protected String cancelledMessage() { return "Machine menu opening interrupted; inspect the active menu before another use."; }
}
