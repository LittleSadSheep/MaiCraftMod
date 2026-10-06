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
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
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
import java.util.Locale;

/**
 * 把已有拉杆设为明确的开或关：先核对观察记录，再走近、空手瞄准，只点一次并等待世界状态确认。
 * 已经是目标状态就直接完成。拉杆状态正确只说明开关拨好了，机器是否接通、是否产出仍需另行观察。
 */
public final class MachineControlTask extends AbstractCompanionTask<MachineControlTaskRecord> {
    private static final double REACH = 4.5;
    private static final int CONFIRMATION_TICKS = 80;
    private enum Phase { SELECT, APPROACH, PREPARE_HAND, AIM, CONFIRM }

    private final ActualViewConvergenceGate aimGate = new ActualViewConvergenceGate();
    private final MachineMenuHandParking handParking = new MachineMenuHandParking();
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
    private String selectionBasis;
    private List<BlockPos> unresolvedCandidates = List.of();
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
        // 点过以后先收齐结果；此时拉杆本来就可能已经改变，不能再拿点击前状态把确认流程拦住。
        if (phase == Phase.CONFIRM) return confirm();
        // 腾手用的背包画面属于本次拨杆准备，先等它自己交换并关闭，不被下面的世界画面恢复抢先关掉。
        if (phase == Phase.PREPARE_HAND && handParking.started()) return prepareHand();
        // 拉杆或按钮尚未使用时先原生退出旧界面，保留选定控制器和实际目标状态，随后续本次控制。
        var context = ClientRuntime.requireContext(player);
        if (!context.menus().ensureWorldVisible(context)) return TaskState.RUNNING;
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

    // 从已观察范围中找原版拉杆。点名格是拉杆就只认它；点名格是告示牌等非拉杆格时取它两格内唯一一根；
    // 没有点名时必须恰好一根，不能随意选一个。选不出来就把附近候选拉杆的坐标交回调用方。
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
        var selection = MachineControl.selectControl(candidates, r.request.controlPosition());
        control = selection.position();
        selectionBasis = selection.basis();
        if (control == null) {
            unresolvedCandidates = selection.nearby();
            boolean named = r.request.controlPosition() != null;
            boolean ambiguous = unresolvedCandidates.size() > 1;
            return failure(ambiguous ? "ambiguous_machine_control" : "machine_control_unavailable",
                    ambiguous && named ? "The named cell is not a lever and several levers are within "
                            + MachineControl.NAMED_CELL_NEIGHBOUR_RANGE + " blocks of it; target the intended lever's coordinates. No switch was used."
                            : ambiguous ? "Several levers are present; name one with control_label or target its coordinates. No switch was used."
                            : named ? "Neither the named cell nor any cell within " + MachineControl.NAMED_CELL_NEIGHBOUR_RANGE
                                    + " blocks of it holds an existing vanilla lever."
                            : "The surveyed region does not contain the requested existing vanilla lever.",
                    FailureType.TARGET_LOST);
        }
        if (NavigationSafetyContext.protectsMutation(control)) {
            return failure("machine_control_protected", "The selected lever is explicitly protected.",
                    FailureType.UNSUPPORTED);
        }
        before = player.level().getBlockState(control);
        expected = before.setValue(LeverBlock.POWERED, r.request.desiredPowered());
        // 目标状态已经满足就无需走路或点击；注意当前保护区检查在这之前，所以受保护拉杆仍会被拒绝。
        if (before.equals(expected)) {
            alreadySatisfied = true;
            controlStateVerified = true;
            return TaskState.SUCCESS;
        }
        phase = Phase.APPROACH;
        return TaskState.RUNNING;
    }

    // 走到手够得到且视线能碰到拉杆的位置；导航说已到但仍不能交互，等待十次后报告站位不合适。
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
        // 背包腾手已经开始就先等它完成，不能同时切换别的快捷栏或提前右键拉杆。
        if (handParking.started()) return parkHand(context);
        if (receipt != null) {
            receipt = context.actions().poll(context, receipt);
            if (!receipt.terminal()) return TaskState.RUNNING;
            receiptStatus = receipt.status().name().toLowerCase(Locale.ROOT);
            if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
                outcomeUncertain = true;
                return failure("machine_empty_hand_unconfirmed", "Empty-hand selection was not confirmed.",
                        FailureType.UNKNOWN);
            }
            receipt = null;
        }
        // 空主手能避免手持物抢先处理右键；原版空手使用方块的后备分支只接受主手。
        // 先切到现成的空快捷栏；九格全满时把手持物经可见背包原样移到空主背包格，背包也满才报告缺空间。
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
            if (!freshSurvey()) return TaskState.FAILED;
            return parkHand(context);
        }
        phase = Phase.AIM;
        return TaskState.RUNNING;
    }

    // 停车只搬动现有物品，不丢不删；停好并关闭背包后主手为空，再去瞄准拉杆。
    private TaskState parkHand(LocalPlayerContext context) {
        var state = handParking.tick(context);
        if (state == MachineMenuHandParking.Status.FAILED) {
            return "machine_menu_empty_hand_required".equals(handParking.failure())
                    ? failure("machine_empty_hand_required", "No empty main hand, hotbar slot or main inventory slot is available for lever use.",
                            FailureType.NO_SPACE)
                    : failure(handParking.failure(), "Empty-hand inventory preparation stopped without discarding items: "
                            + handParking.failure(), FailureType.UNKNOWN);
        }
        if (state == MachineMenuHandParking.Status.READY) {
            hand = InteractionHand.MAIN_HAND;
            phase = Phase.AIM;
        }
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
        // 等游戏实际松开潜行再点。潜行右键可能绕过普通方块交互，光发出停止指令还不够。
        if (player.isSecondaryUseActive()) return TaskState.RUNNING;
        Vec3 aim = aimPoint();
        InputDriver.lookAt(player, aim);
        if (!aimGate.ready(player, aim.subtract(player.getEyePosition()))) return TaskState.RUNNING;
        // 镜头真正转到位后，用游戏准星再确认命中的就是这根拉杆，避免只根据预先计算的视线点击。
        HitResult trace = Interaction.nativeRaytrace(player, REACH);
        if (!(trace instanceof BlockHitResult hit) || trace.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(control)) {
            return failure("machine_control_occluded", "The native crosshair does not hit the selected lever.",
                    FailureType.OCCLUDED);
        }
        var context = ClientRuntime.requireContext(player);
        if (!context.mutationAvailable()) return TaskState.RUNNING;
        if (!freshSurvey()) return TaskState.FAILED;
        // 发出右键之前就记为“已尝试”；即使调用中抛出异常，也不能重试一次把拉杆又拨回去。
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
        receiptStatus = receipt.status().name().toLowerCase(Locale.ROOT);
        if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            return failure("machine_control_unconfirmed", "The lever's requested state was not confirmed; no second toggle was attempted.",
                    FailureType.UNKNOWN);
        }
        // 原版客户端点击拉杆只显示粒子，开关状态由服务器改变。先等动作确认，再核对拉杆仍是目标状态。
        if (!player.level().isLoaded(control) || !player.level().getBlockState(control).equals(expected)) {
            return failure("machine_control_state_diverged", "The lever changed again while its outcome was being confirmed.",
                    FailureType.TARGET_LOST);
        }
        outcomeUncertain = false;
        controlStateVerified = true;
        return TaskState.SUCCESS;
    }

    // 核对整个观察立方体都已加载且结构摘要没变。附近无关方块变化目前也会要求重新观察。
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
            failure("machine_structure_changed", "The machine structure changed since inspection; review the latest snapshot returned with this failure before operating.",
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

    /** 面板行动行的一句话汇报；阶段来自内部选杆/接近/瞄准 Phase，拉杆坐标是已确认事实。 */
    @Override public String describeCurrentAction() {
        if (phase == Phase.SELECT) return "正在寻找机器拉杆";
        String at = control == null ? "" : " (" + control.getX() + "," + control.getY() + "," + control.getZ() + ")";
        return switch (phase) {
            case APPROACH -> "正在走近拉杆" + at;
            case PREPARE_HAND -> handParking.started() ? "正在把手持物放进背包腾空主手" : "正在腾空主手";
            case AIM -> "正在拨动拉杆" + at;
            case CONFIRM -> "正在确认拉杆状态" + at;
            default -> "正在寻找机器拉杆";
        };
    }

    // 已经点过、确认还没结束时，父任务即使认为目标够了，也应先等这一笔点击结清。
    @Override public boolean mustSettleBeforeSatisfiedCancellation() {
        return handParking.settling() || controlAttempted && !controlStateVerified && receipt != null && !receipt.terminal();
    }

    @Override protected void cleanup() {
        super.cleanup();
        aimGate.reset();
        handParking.cleanup(player);
        if (receipt != null && !receipt.terminal()) {
            try {
                var context = ClientRuntime.requireContext(player);
                receipt = context.actions().retireOneShotForTaskBoundary(context, receipt,
                        "machine control task ended before confirmation");
                receiptStatus = receipt.status().name().toLowerCase(Locale.ROOT);
            } catch (RuntimeException unavailable) {
                // 角色或运行环境已更换时由运行层作废旧确认；这里仍保留“结果不确定”，不能声称没点过。
            }
            outcomeUncertain = true;
        }
        if (controlAttempted && !controlStateVerified) outcomeUncertain = true;
    }

    // 分别报告是否尝试点击、开关是否确认、结果是否不确定；机器生产和接线验证始终留给后续观察。
    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "set_control");
        data.put("requested_powered", r.request.desiredPowered());
        data.put("control_state_verified", controlStateVerified);
        data.put("machine_production_verified", false);
        data.put("connection_semantics_verified", false);
        data.put("already_satisfied", alreadySatisfied);
        data.put("effects_started", controlAttempted);
        data.put("outcome_uncertain", outcomeUncertain || handParking.uncertain());
        data.put("mechanical_retry_allowed", !controlAttempted && !outcomeUncertain && !handParking.uncertain());
        data.put("candidate_controls", candidateCount);
        if (selectionBasis != null) data.put("control_selected_by", selectionBasis);
        if (r.request.controlPosition() != null) data.put("named_control_cell", Map.of("x", r.request.controlPosition().getX(),
                "y", r.request.controlPosition().getY(), "z", r.request.controlPosition().getZ()));
        if (!unresolvedCandidates.isEmpty()) data.put("candidate_control_positions", unresolvedCandidates.stream()
                .map(position -> Map.of("x", position.getX(), "y", position.getY(), "z", position.getZ())).toList());
        if (handParking.started()) data.put("hand_preparation", handParking.evidence());
        data.put("structure_fingerprint", r.request.structuralFingerprint());
        data.put("next_observation", "survey the machine again and verify its actual output separately");
        if (control != null) data.put("control_position", Map.of(
                "x", control.getX(), "y", control.getY(), "z", control.getZ()));
        if (before != null) data.put("before_powered", before.getValue(LeverBlock.POWERED));
        if (controlStateVerified) data.put("after_powered", r.request.desiredPowered());
        if (failureCode != null) data.put("failure_code", failureCode);
        if (receiptStatus != null) data.put("native_receipt_status", receiptStatus);
        return data;
    }

    @Override protected String successMessage() {
        return "Existing lever is " + (r.request.desiredPowered() ? "powered" : "unpowered")
                + "; downstream machine operation and production require separate observation.";
    }

    @Override protected String timeoutMessage() {
        return controlAttempted ? "Machine control timed out after one use; inspect its state before retrying."
                : "Machine control timed out before lever use.";
    }

    @Override protected String cancelledMessage() {
        return controlAttempted ? "Machine control was interrupted after one use; its state needs inspection."
                : "Machine control was interrupted before lever use.";
    }
}
