// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.Level;
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
import org.maiwithu.maicraft.core.task.inventory.EquipCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.EquipTaskRecord;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 把一个 Mekanism 接口切到明确模式，例如某面设为输出。先准备配置器、选工具模式，再走到能点准该面的地方。
 * 接口每次只前进一步，确认确实切到预期模式后才能继续；达到目标就停，一整轮仍不到目标则报告异常。
 */
public final class MekanismConfigureTask extends AbstractCompanionTask<MekanismConfigureTaskRecord> {
    private final ActualViewConvergenceGate aimGate = new ActualViewConvergenceGate();
    private EquipCompanionTask equip;
    private NativeActionReceipt receipt;
    private Block block;
    private Level world;
    private BlockPos stance;
    private final Set<Long> rejectedStances = new HashSet<>();
    private String beforeMode, expectedMode, failureCode = "";
    private int confirmedUses, aimTicks, modeSwitches, equipTicks;
    private boolean submitted, verified, switchingTool, relocate;

    public MekanismConfigureTask(LocalPlayer player, MekanismConfigureTaskRecord record) { super(player, record); }
    @Override protected void onStart() {
        world = player.level();
        MekanismNativeConfiguration.Observation observation = observe();
        if (!observation.cycle().contains(r.desiredMode)) { stop("configuration_mode_unsupported", "Desired mode is not supported by this installed machine."); return; }
        block = player.level().getBlockState(r.target).getBlock();
        // 已经是目标模式就完成，不再要求玩家拿到配置器。
        if (observation.current().equals(r.desiredMode)) { verified = true; return; }
        ResourceLocation id = ResourceLocation.parse("mekanism:configurator");
        if (!BuiltInRegistries.ITEM.containsKey(id)) { stop("configurator_unavailable", "The installed registry has no Mekanism configurator."); return; }
        equip = new EquipCompanionTask(player, new EquipTaskRecord("mekanism-configurator-equip", r.getDeadlineGameTime(),
                BuiltInRegistries.ITEM.get(id), EquipmentSlot.MAINHAND, "Mekanism configurator"));
    }
    @Override protected TaskState onTick() {
        if (player.level() != world) return stop("configuration_world_changed", "The machine belongs to a different client world session.");
        if (player.level().getGameTime() > r.getDeadlineGameTime()) {
            failureCode = "configuration_timeout"; return TaskState.TIMEOUT;
        }
        if (verified) return TaskState.SUCCESS;
        // 先收完上一次操作的结果；工具切模式和机器接口切模式共用这一等待入口，但分别核对各自状态。
        if (receipt != null) return confirm();
        if (submitted) return stop("configuration_receipt_missing", "Native configuration was submitted without a receipt; inspect before retrying.");
        if (equip != null) {
            if (++equipTicks > 200) { equip.result(TaskState.TIMEOUT); equip = null; return stop("configurator_equip_timeout", "Configurator inventory staging did not settle."); }
            TaskState state = runChild(equip);
            if (state != null) {
                equip.result(state); equip = null;
                if (state != TaskState.SUCCESS) return stop("configurator_unavailable", "Could not equip a Mekanism configurator.");
            }
            return TaskState.RUNNING;
        }
        if (!player.level().isLoaded(r.target) || player.level().getBlockState(r.target).getBlock() != block
                || NavigationSafetyContext.protectsMutation(r.target)) return stop("configuration_target_changed", "Configuration target changed or is protected.");
        if (!MekanismNativeConfiguration.toolReady(player.getMainHandItem(), r.medium)) {
            return switchToolMode();
        }
        var context = ClientRuntime.requireContext(player);
        if (context.minecraft().screen != null || player.containerMenu != player.inventoryMenu) {
            return stop("configuration_menu_busy", "Close the active menu before native configurator use.");
        }
        boolean inductionPort = r.medium.equals("induction_port");
        // 先试当前位置能否点准；不行再找已有站位。管道要点到指定分支，感应端口则任意一面都能切换。
        Vec3 aim = relocate ? null : aimFrom(player.getEyePosition());
        if (aim == null) return approach();
        stopNav(); InputDriver.halt(player); InputDriver.sneak(player, true); InputDriver.lookAt(player, aim);
        if (++aimTicks > 100) { rejectStance(); return TaskState.RUNNING; }
        if (!player.isSecondaryUseActive() || !aimGate.ready(player, aim.subtract(player.getEyePosition()))) return TaskState.RUNNING;
        HitResult trace = Interaction.nativeRaytrace(player, 4.5);
        if (!(trace instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !(inductionPort ? hit.getBlockPos().equals(r.target)
                        : MekanismNativeConfiguration.hitMatches(player, r.target, r.face, hit))) return TaskState.RUNNING;
        var observation = observe();
        if (observation.current().equals(r.desiredMode)) { verified = true; return TaskState.SUCCESS; }
        if (confirmedUses >= observation.cycle().size()) return stop("configuration_cycle_diverged", "The machine did not reach the requested mode after one verified cycle.");
        // 用刚读到的状态计算下一步，避免按照开始时的旧模式一直循环点击。
        beforeMode = observation.current(); expectedMode = observation.next();
        if (!context.mutationAvailable()) return TaskState.RUNNING;
        submitted = true;
        receipt = context.actions().useBlock(context, InteractionHand.MAIN_HAND, hit, fresh -> {
            try {
                String mode = observe().current();
                return mode.equals(expectedMode) ? NativeConfirmation.Verdict.APPLIED
                        : mode.equals(beforeMode) ? NativeConfirmation.Verdict.PENDING : NativeConfirmation.Verdict.DIVERGED;
            } catch (RuntimeException unavailable) { return NativeConfirmation.Verdict.DIVERGED; }
        }, 100);
        return TaskState.RUNNING;
    }
    private Vec3 aimFrom(Vec3 eye) { return MekanismInteractionGeometry.aimFrom(player, r.target, r.face, r.medium, eye); }
    private TaskState approach() {
        if (stance == null) stance = AssemblyInteractionGeometry.nearestStand(player, r.target, rejectedStances, this::aimFrom);
        if (stance == null) return stop("configuration_unreachable", "No existing stance can reach the requested machine face or pipe segment.");
        if (nav == null) nav = PlayerNav.to(player, () -> GoalCompiler.standOn(stance), 1.0,
                () -> Vec3.atBottomCenterOf(stance).distanceToSqr(player.position()) < .16 && aimFrom(player.getEyePosition()) != null,
                PlayerNav.ContextProvider.DEFAULT);
        PlayerNav.Status status = nav.tick();
        if (status == PlayerNav.Status.ARRIVED) {
            if (aimFrom(player.getEyePosition()) == null) rejectStance();
            else { stopNav(); relocate = false; aimTicks = 0; aimGate.reset(); }
        }
        else if (status == PlayerNav.Status.FAILED) { rejectedStances.add(stance.asLong()); stance = null; stopNav(); }
        return TaskState.RUNNING;
    }
    private void rejectStance() {
        rejectedStances.add(PlayerNav.playerFeet(player).asLong());
        if (stance != null) rejectedStances.add(stance.asLong());
        stance = null; relocate = true; aimTicks = 0; aimGate.reset(); stopNav();
    }
    // 确认失败就保留“已提交但未结清”；只有实际状态仍符合预期，才清除这一标记并允许下一次操作。
    private TaskState confirm() {
        var context = ClientRuntime.requireContext(player);
        receipt = context.actions().poll(context, receipt);
        if (!receipt.terminal()) return TaskState.RUNNING;
        if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            return stop("configuration_unconfirmed", "Native configuration was not confirmed; no blind retry was submitted.");
        }
        if (switchingTool) {
            if (!MekanismNativeConfiguration.toolMode(player.getMainHandItem()).equals(expectedMode)) {
                return stop("configurator_mode_diverged", "The configurator changed again during server confirmation.");
            }
            modeSwitches++; switchingTool = false; submitted = false; receipt = null;
            return TaskState.RUNNING;
        }
        if (!observe().current().equals(expectedMode)) return stop("configuration_changed", "Mode changed during confirmation.");
        confirmedUses++; submitted = false; receipt = null; aimTicks = 0; aimGate.reset();
        verified = expectedMode.equals(r.desiredMode);
        return verified ? TaskState.SUCCESS : TaskState.RUNNING;
    }
    // 逐次切换手持配置器的模式，每次等确认；当前最多尝试十六次，找不到可用模式就停止。
    private TaskState switchToolMode() {
        if (modeSwitches >= 16) return stop("configurator_mode_unavailable", "No supported configurator mode was reached after the observed mode cycle.");
        var context = ClientRuntime.requireContext(player);
        if (context.minecraft().screen != null || player.containerMenu != player.inventoryMenu) {
            return stop("configurator_screen_busy", "Native mode-switch requires the world view.");
        }
        stopNav(); InputDriver.halt(player);
        if (player.isSecondaryUseActive()) return TaskState.RUNNING;
        beforeMode = MekanismNativeConfiguration.toolMode(player.getMainHandItem());
        expectedMode = MekanismNativeConfiguration.nextToolMode(player.getMainHandItem());
        if (!context.mutationAvailable()) return TaskState.RUNNING;
        switchingTool = true; submitted = true;
        receipt = context.actions().submitControlProtocol(context, "mekanism_native_configurator_mode_scroll",
                () -> MekanismNativeConfiguration.advanceToolMode(player), fresh -> {
                    try {
                        String mode = MekanismNativeConfiguration.toolMode(fresh.player().getMainHandItem());
                        return mode.equals(expectedMode) ? NativeConfirmation.Verdict.APPLIED
                                : mode.equals(beforeMode) ? NativeConfirmation.Verdict.PENDING : NativeConfirmation.Verdict.DIVERGED;
                    } catch (RuntimeException unavailable) { return NativeConfirmation.Verdict.DIVERGED; }
                }, 100);
        return TaskState.RUNNING;
    }
    private MekanismNativeConfiguration.Observation observe() {
        return MekanismNativeConfiguration.inspect(player.level(), r.target, r.face, r.medium);
    }
    private TaskState stop(String code, String message) { failureCode = code; fail(message, FailureType.UNSUPPORTED); return TaskState.FAILED; }
    @Override protected void cleanup() {
        if (equip != null) { equip.result(TaskState.CANCELLED); equip = null; }
        if (receipt != null && !receipt.terminal()) {
            try {
                var context = ClientRuntime.requireContext(player);
                receipt = context.actions().retireOneShotForTaskBoundary(context, receipt, "Mekanism configuration ended");
            } catch (RuntimeException revoked) { /* 断线后由角色运行层收尾；这次配置仍可能已经生效。 */ }
        }
        InputDriver.halt(player); super.cleanup();
    }
    @Override public boolean mustSettleBeforeSatisfiedCancellation() { return submitted; }
    @Override public void stop(LocalPlayer companion, StopReason why) {
        if (equip != null) equip.stop(companion, why);
        super.stop(companion, why);
    }
    @Override protected String successMessage() { return "Mekanism mode observed as " + r.desiredMode + "."; }
    @Override protected Map<String, Object> resultData() {
        return Map.of("configuration_verified", verified, "medium", r.medium, "desired_mode", r.desiredMode,
                "confirmed_native_uses", confirmedUses, "configurator_mode_switches", modeSwitches,
                "failure_code", failureCode, "outcome_uncertain", submitted);
    }
}
