// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Locale;
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
import java.util.Set;
import net.minecraft.world.entity.Pose;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.integration.machine.assembly.AssemblyInteractionGeometry;

/**
 * 从已观察机器走到可交互位置，准备空手、瞄准并只右键一次；菜单真正出现后记录它的机器来源。
 */
public final class MachineMenuOpenTask extends AbstractCompanionTask<MachineMenuOpenTaskRecord> {
    // 显式开菜单成功后供后续观察和存取使用；失败的界面仍由普通收尾退出。
    @Override public boolean keepsGuiOnCompletion() { return verified; }
    private static final double REACH = 4.5;
    /** 原地等待（退出旧界面、腾手、瞄准、确认）连续这么久没有推进就放弃这台机器；走路由导航自己判定失败。 */
    private static final long STALL_TICKS = 30L * 20L;
    private enum Phase { START, APPROACH, HAND, AIM, CONFIRM }
    private final ActualViewConvergenceGate aimGate = new ActualViewConvergenceGate();
    private final MachineMenuHandParking handParking = new MachineMenuHandParking();
    private Phase phase = Phase.START;
    private BlockState before;
    private ResourceLocation blockId;
    private NativeActionReceipt receipt;
    private boolean openAttempted;
    private boolean verified;
    private boolean reusedMenu;
    private int dudTicks;
    private String failureCode;
    private JsonObject menuReport;
    private BlockPos faceStance;
    private Phase progressPhase;
    private long progressTick = -1;
    private long lastTick = -1;
    private String stalledPhase;

    public MachineMenuOpenTask(LocalPlayer player, MachineMenuOpenTaskRecord record) { super(player, record); }

    @Override protected TaskState onTick() {
        BlockPos position = r.request.machinePosition();
        if (!r.request.dimension().equals(player.level().dimension().location().toString())
                || !player.level().isLoaded(position)) {
            return failure("machine_menu_target_lost", "The inspected machine is no longer loaded in this dimension.", FailureType.TARGET_LOST);
        }
        // 阶段向前推进或导航仍在移动就重新计时；原地等待超过上限就放弃这台机器，让调用方换下一个目标，
        // 不让一只打不开的箱子把整个取料任务拖上十分钟。
        long now = player.level().getGameTime();
        // 任务被暂停（例如玩家临时接管身体）期间不执行，恢复后把暂停的那段时间从等待计时里扣掉。
        if (lastTick >= 0 && now - lastTick > 20 && progressTick >= 0) progressTick += now - lastTick - 1;
        lastTick = now;
        if (progressTick < 0 || phase.ordinal() > progressPhase.ordinal()) { progressPhase = phase; progressTick = now; }
        if (now - progressTick > STALL_TICKS) {
            stalledPhase = phase.name().toLowerCase(Locale.ROOT);
            return failure("machine_menu_open_stalled", "Opening the machine menu made no progress for "
                    + STALL_TICKS / 20 + " seconds in phase " + stalledPhase + "; this machine was skipped.", FailureType.UNKNOWN);
        }
        if (phase == Phase.CONFIRM) return confirm();
        // 腾手期间的背包属于当前开菜单准备，先完成它自己的交换和关闭，不被外层世界恢复抢先退出。
        if (phase == Phase.HAND) return prepareHand();
        if (player.containerMenu != player.inventoryMenu) {
            // 同一目标已由其他原生工具打开时，直接沿已确认来源读取当前菜单，不再重复右键或要求先关后开。
            if (phase == Phase.START && MachineMenu.openedAt(player, position)
                    && player.containerMenu.getCarried().isEmpty() && !NavigationSafetyContext.protectsUse(position)) {
                if (!fresh()) return TaskState.FAILED;
                menuReport = MachineMenu.inspect(player); verified = true; reusedMenu = true;
                return TaskState.SUCCESS;
            }
        }
        // 合适的目标菜单已经在上方复用；其他旧界面由 Mod 退出，再继续原机器的开菜单步骤。
        var context = ClientRuntime.requireContext(player);
        if (!context.menus().ensureWorldVisible(context)) return TaskState.RUNNING;
        if (NavigationSafetyContext.protectsUse(position)) {
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
        if (nav == null) {
            if (r.request.face() != null) {
                faceStance = AssemblyInteractionGeometry.nearestStand(player,
                        r.request.machinePosition(), Set.of(), eyes -> visibleFrom(eyes) ? aim() : null,
                        Pose.STANDING);
                if (faceStance == null) return failure("machine_menu_face_unreachable", "The requested native part face has no usable stance.", FailureType.OCCLUDED);
            }
            nav = PlayerNav.to(player, () -> r.request.face() == null ? GoalCompiler.interact(r.request.machinePosition()) : GoalCompiler.standOn(faceStance),
                    1.0, this::inReach, PlayerNav.ContextProvider.DEFAULT);
        }
        return switch (nav.tick()) {
            case RUNNING -> { progressTick = player.level().getGameTime(); yield TaskState.RUNNING; }
            case ARRIVED -> ++dudTicks < 10 ? TaskState.RUNNING : failure("machine_menu_no_stance",
                    "No clear interaction stance reached the selected machine.", FailureType.STANCE_DUD);
            case FAILED -> failure("machine_menu_unreachable", "The machine cannot be reached without changing terrain.", nav.failType());
        };
    }

    // 先用现成空快捷栏；全满时把手持物经可见背包移到真正的空主背包格，并等关闭确认。
    private TaskState prepareHand() {
        var context = ClientRuntime.requireContext(player);
        // 尚未开始腾手时处理刚出现的挡路页面；一旦开始，就保留所需背包直到停车自身结清。
        if (!handParking.started() && !context.menus().ensureWorldVisible(context)) return TaskState.RUNNING;
        // 背包腾手已经开始就先等它完成，不能同时切换别的快捷栏或提前右键机器。
        if (handParking.started()) return parkHand(context);
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
        if (!fresh()) return TaskState.FAILED;
        return parkHand(context);
    }

    private TaskState parkHand(LocalPlayerContext context) {
        // 空手准备只搬存现有物品；背包也满时明确缺空间，工具不会被删除或扔掉。
        var state = handParking.tick(context);
        if (state == MachineMenuHandParking.Status.FAILED) return failure(handParking.failure(),
                "Empty-hand inventory preparation stopped without discarding items: " + handParking.failure(),
                "machine_menu_empty_hand_required".equals(handParking.failure()) ? FailureType.NO_SPACE : FailureType.UNKNOWN);
        if (state == MachineMenuHandParking.Status.READY) phase = Phase.AIM;
        return TaskState.RUNNING;
    }

    private TaskState aimAndOpen() {
        // 背包关闭并重新确认主手为空后才进入世界右键，避免手持扳手、铲子等触发另一种方块操作。
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
                || !hit.getBlockPos().equals(r.request.machinePosition()) || r.request.face() != null && hit.getDirection() != r.request.face()) {
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

    // 必须观察到新菜单、空鼠标和未变的目标方块种类，再等待界面可见；没有确认就不重复右键。
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

    // 准备阶段多次重查整个观察范围和结构摘要，不只核对这一个将要打开的方块。
    private boolean fresh() {
        int radius = r.request.radius();
        BlockPos center = r.request.center();
        for (BlockPos position : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            if (player.level().isOutsideBuildHeight(position) || !player.level().isLoaded(position)) {
                failure("machine_menu_region_incomplete", "The surveyed machine volume is not fully loaded.", FailureType.TARGET_LOST); return false;
            }
        }
        if (!r.request.structuralFingerprint().equals(MachineSurvey.fingerprint(player, center, radius))) {
            failure("machine_menu_structure_changed", "The machine structure changed; review the latest snapshot returned with this failure before opening.", FailureType.TARGET_LOST); return false;
        }
        return true;
    }

    private Vec3 aim() {
        if (r.request.face() != null) return Vec3.atCenterOf(r.request.machinePosition()).add(
                r.request.face().getStepX() * .499, r.request.face().getStepY() * .499, r.request.face().getStepZ() * .499);
        var shape = before.getShape(player.level(), r.request.machinePosition());
        return shape.isEmpty() ? Vec3.atCenterOf(r.request.machinePosition())
                : shape.bounds().getCenter().add(r.request.machinePosition().getX(), r.request.machinePosition().getY(), r.request.machinePosition().getZ());
    }

    private boolean inReach() {
        return visibleFrom(player.getEyePosition());
    }
    private boolean visibleFrom(Vec3 eye) {
        Vec3 aim = aim();
        if (eye.distanceToSqr(aim) > REACH * REACH) return false;
        var hit = player.level().clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(r.request.machinePosition())
                && (r.request.face() == null || hit.getDirection() == r.request.face());
    }

    private TaskState failure(String code, String message, FailureType type) {
        failureCode = code; fail(message, type); return TaskState.FAILED;
    }

    @Override protected void cleanup() {
        super.cleanup(); aimGate.reset();
        handParking.cleanup(player);
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
        data.put("menu_reused", reusedMenu);
        data.put("effects_started", openAttempted);
        data.put("outcome_uncertain", openAttempted && !verified || handParking.uncertain());
        data.put("hand_preparation", handParking.evidence());
        data.put("mechanical_retry_allowed", !openAttempted && !handParking.uncertain());
        data.put("machine_production_verified", false);
        if (menuReport != null) data.put("menu_report", menuReport);
        if (failureCode != null) data.put("failure_code", failureCode);
        if (stalledPhase != null) data.put("stalled_phase", stalledPhase);
        return data;
    }

    /** 面板行动行的一句话汇报；阶段来自内部接近/腾手/开门 Phase，坐标是正在交互的机器位置。 */
    @Override public String describeCurrentAction() {
        BlockPos position = r.request.machinePosition();
        String at = " (" + position.getX() + "," + position.getY() + "," + position.getZ() + ")";
        return switch (phase) {
            case START -> "正在检查机器菜单目标" + at;
            case APPROACH -> "正在走近机器" + at;
            case HAND -> "正在腾空主手";
            case AIM -> "正在右键打开机器菜单" + at;
            case CONFIRM -> "正在确认机器菜单已打开" + at;
        };
    }

    @Override public boolean mustSettleBeforeSatisfiedCancellation() { return handParking.settling() || openAttempted && !verified && receipt != null && !receipt.terminal(); }
    @Override protected String successMessage() { return reusedMenu
            ? "Reused and inspected the already open native menu; no new block use was submitted."
            : "Opened and inspected the selected machine's native menu; item entries and data values are observed, recipe roles remain to be analyzed."; }
    @Override protected String cancelledMessage() { return "Machine menu opening interrupted; inspect the active menu before another use."; }
}
