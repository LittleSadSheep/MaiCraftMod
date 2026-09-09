// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
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
 * 在指定格安装一个 AE2 电缆或面板：拿到物品、找能点到的站位、潜行瞄准、只右键一次，然后核对部件与物品数量。
 * 其他槽里的部件必须保持原样；成功不代表 AE2 网络已经供电、有频道或能够生产。
 */
public final class AePartTask extends AbstractCompanionTask<AePartTaskRecord> {
    private record Candidate(BlockPos clicked, Vec3 aim) {}
    private final ActualViewConvergenceGate aimGate = new ActualViewConvergenceGate();
    private final List<Candidate> candidates = new ArrayList<>();
    private EquipCompanionTask equip;
    private NativeActionReceipt receipt;
    private Object[] beforeParts;
    private Level world;
    private BlockPos stance;
    private final Set<Long> rejectedStances = new HashSet<>();
    private int beforeCount, candidateIndex, aimTicks, equipTicks;
    private ServerBlockEntityReceipts.Watch serverWatch;
    private boolean submitted, verified, alreadyInstalled, relocate;
    private String failureCode = "";

    public AePartTask(LocalPlayer player, AePartTaskRecord record) { super(player, record); }

    @Override protected void onStart() {
        world = player.level();
        if (!player.level().isLoaded(r.target)) { stop("part_target_unloaded", "AE2 target is not loaded."); return; }
        // 已经装有相同部件就直接完成，不再拿材料，也不会因为保护区禁止修改而拒绝复用它。
        if (MachineInstallation.matches(player.level(), r.target, r.part)) {
            alreadyInstalled = true;
            return;
        }
        if (NavigationSafetyContext.protectsMutation(r.target)) {
            stop("part_target_protected", "AE2 target is protected."); return;
        }
        if (!MachineInstallation.canInstall(player.level(), r.target, r.part)) {
            stop("part_target_incompatible", "AE2 target cannot accept the requested part without replacing existing contents."); return;
        }
        beforeParts = MachineInstallation.parts(player.level(), r.target);
        int slot = r.part.side() == null ? 0 : r.part.side().ordinal() + 1;
        if (beforeParts[slot] != null) { stop("part_slot_occupied", "The requested AE2 part slot is occupied."); return; }
        // 依次尝试点宿主或相邻支撑面；能瞄准还不够，之后还要让 AE2 预测落点确实是目标槽。
        candidates.add(new Candidate(r.target, Vec3.atCenterOf(r.target)));
        for (Direction side : Direction.values()) {
            BlockPos support = r.target.relative(side);
            if (player.level().isLoaded(support) && !player.level().getBlockState(support).isAir()) {
                candidates.add(new Candidate(support, Vec3.atCenterOf(support)
                        .add(-side.getStepX() * .5, -side.getStepY() * .5, -side.getStepZ() * .5)));
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate -> player.getEyePosition().distanceToSqr(candidate.aim())));
        equip = new EquipCompanionTask(player, new EquipTaskRecord("ae-part-equip", r.getDeadlineGameTime(),
                r.part.item(), EquipmentSlot.MAINHAND, r.part.itemId()));
    }

    @Override protected TaskState onTick() {
        if (player.level() != world) return stop("part_world_changed", "AE2 part target belongs to a different client world session.");
        if (player.level().getGameTime() > r.getDeadlineGameTime()) { failureCode = "part_install_timeout"; return TaskState.TIMEOUT; }
        if (alreadyInstalled) return TaskState.SUCCESS;
        if (submitted) return confirm();
        if (equip != null) {
            if (++equipTicks > 200) { equip.result(TaskState.TIMEOUT); equip = null; return stop("part_equip_timeout", "AE2 inventory staging did not settle."); }
            TaskState state = runChild(equip);
            if (state != null) {
                equip.result(state);
                equip = null;
                if (state != TaskState.SUCCESS) return stop("part_material_unavailable", "Could not equip the AE2 part item.");
            }
            return TaskState.RUNNING;
        }
        if (candidateIndex >= candidates.size()) return stop("part_stance_unavailable", "No native placement stance reaches this AE2 part slot.");
        if (!player.level().isLoaded(r.target) || !MachineInstallation.otherPartsUnchanged(beforeParts,
                MachineInstallation.parts(player.level(), r.target), r.part.side())) {
            return stop("part_host_changed", "Existing AE2 parts changed before placement.");
        }
        // 当前只接受没有附加设置的普通部件物品；带复制配置等组件的同种物品也会被拒绝。
        if (!ItemStack.isSameItemSameComponents(player.getMainHandItem(), new ItemStack(r.part.item()))) {
            return stop("part_held_item_changed", "AE2 placement requires the original plain part item without copied settings.");
        }
        var context = ClientRuntime.requireContext(player);
        if (player.containerMenu != player.inventoryMenu || context.minecraft().screen != null) {
            return stop("part_menu_busy", "A menu is open while preparing AE2 placement.");
        }
        Candidate candidate = candidates.get(candidateIndex);
        if (relocate || aimFrom(candidate, player.getEyePosition()) == null) return approach(candidate);
        stopNav();
        InputDriver.halt(player);
        InputDriver.sneak(player, true);
        InputDriver.lookAt(player, candidate.aim());
        if (++aimTicks > 80) { rejectStance(); return TaskState.RUNNING; }
        if (!player.isSecondaryUseActive() || !aimGate.ready(player, candidate.aim().subtract(player.getEyePosition()))) {
            return TaskState.RUNNING;
        }
        HitResult trace = Interaction.nativeRaytrace(player, 4.5);
        if (!(trace instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !MachineInstallation.predicts(player, r.part, r.target, hit)) {
            rejectStance();
            return TaskState.RUNNING;
        }
        Object[] live = MachineInstallation.parts(player.level(), r.target);
        int slot = r.part.side() == null ? 0 : r.part.side().ordinal() + 1;
        if (live[slot] != null || NavigationSafetyContext.protectsMutation(r.target)) {
            return stop("part_target_changed", "AE2 target slot changed before use.");
        }
        if (!context.mutationAvailable()) return TaskState.RUNNING;
        beforeCount = countItem();
        // 在点击前记下这格最新的服务器同步编号；创造模式物品不会减少，因此还必须等到新的同步。
        serverWatch = ServerBlockEntityReceipts.watch(player.level(), r.target);
        submitted = true;
        receipt = context.actions().useBlock(context, InteractionHand.MAIN_HAND, hit, fresh -> {
            if (!fresh.level().isLoaded(r.target)) return NativeConfirmation.Verdict.PENDING;
            if (!MachineInstallation.otherPartsUnchanged(beforeParts, MachineInstallation.parts(fresh.level(), r.target), r.part.side())) {
                return NativeConfirmation.Verdict.DIVERGED;
            }
            return receiptVerdict(MachineInstallation.matches(fresh.level(), r.target, r.part), beforeCount - countItem(),
                    player.isCreative(), serverWatch.advanced());
        }, 120);
        return TaskState.RUNNING;
    }

    private Vec3 aimFrom(Candidate candidate, Vec3 eye) {
        BlockHitResult hit = AssemblyInteractionGeometry.hit(player, eye, candidate.aim());
        return hit != null && MachineInstallation.predicts(player, r.part, r.target, hit) ? candidate.aim() : null;
    }
    // 在附近已有地面上找站位；到达后仍点不到就记为失败站位，再找其他位置或换一个支撑面。
    private TaskState approach(Candidate candidate) {
        if (stance == null) stance = AssemblyInteractionGeometry.nearestStand(player, r.target, rejectedStances, eye -> aimFrom(candidate, eye));
        if (stance == null) { nextCandidate(); return TaskState.RUNNING; }
        if (nav == null) nav = PlayerNav.to(player, () -> GoalCompiler.standOn(stance), 1.0,
                () -> Vec3.atBottomCenterOf(stance).distanceToSqr(player.position()) < .16 && aimFrom(candidate, player.getEyePosition()) != null,
                PlayerNav.ContextProvider.DEFAULT);
        PlayerNav.Status status = nav.tick();
        if (status == PlayerNav.Status.ARRIVED) {
            if (aimFrom(candidate, player.getEyePosition()) == null) rejectStance();
            else { stopNav(); relocate = false; aimTicks = 0; aimGate.reset(); }
        } else if (status == PlayerNav.Status.FAILED) { rejectedStances.add(stance.asLong()); stance = null; stopNav(); }
        return TaskState.RUNNING;
    }
    private void rejectStance() {
        rejectedStances.add(PlayerNav.playerFeet(player).asLong());
        if (stance != null) rejectedStances.add(stance.asLong());
        stance = null; relocate = true; aimTicks = 0; aimGate.reset(); stopNav();
    }

    static NativeConfirmation.Verdict receiptVerdict(boolean present, int itemDecrease) {
        return receiptVerdict(present, itemDecrease, false, false);
    }

    // 生存模式要求部件出现且恰好少一件物品；创造模式要求数量不变、部件出现、并收到这格的新服务器同步。
    static NativeConfirmation.Verdict receiptVerdict(boolean present, int itemDecrease, boolean creative, boolean serverUpdate) {
        if (creative) {
            if (itemDecrease != 0) return NativeConfirmation.Verdict.DIVERGED;
            return present && serverUpdate ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING;
        }
        if (itemDecrease < 0 || itemDecrease > 1) return NativeConfirmation.Verdict.DIVERGED;
        return present && itemDecrease == 1 ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING;
    }

    private int countItem() {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameComponents(stack, new ItemStack(r.part.item()))) count += stack.getCount();
        }
        return count;
    }

    // 等待本次右键结果，再复查部件与数量。确认失败就保留不确定结果，不再补点一次。
    private TaskState confirm() {
        if (receipt == null) return stop("part_receipt_missing", "AE2 use has no receipt; inspect before retrying.");
        var context = ClientRuntime.requireContext(player);
        receipt = context.actions().poll(context, receipt);
        if (!receipt.terminal()) return TaskState.RUNNING;
        if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            return stop("part_installation_unconfirmed", "AE2 part installation was not confirmed; no repeated use was submitted.");
        }
        verified = receiptVerdict(MachineInstallation.matches(player.level(), r.target, r.part), beforeCount - countItem(),
                player.isCreative(), serverWatch != null && serverWatch.advanced())
                == NativeConfirmation.Verdict.APPLIED;
        return verified ? TaskState.SUCCESS : stop("part_result_diverged", "AE2 installation changed during confirmation.");
    }

    private void nextCandidate() {
        stopNav(); candidateIndex++; aimTicks = 0; aimGate.reset(); stance = null; relocate = false; rejectedStances.clear();
    }
    private TaskState stop(String code, String message) {
        failureCode = code; fail(message, FailureType.UNSUPPORTED); return TaskState.FAILED;
    }
    @Override protected void cleanup() {
        if (serverWatch != null) serverWatch.close();
        if (equip != null) { equip.result(TaskState.CANCELLED); equip = null; }
        if (receipt != null && !receipt.terminal()) {
            try {
                var context = ClientRuntime.requireContext(player);
                receipt = context.actions().retireOneShotForTaskBoundary(context, receipt, "AE2 part task ended");
            } catch (RuntimeException revoked) { /* 断线后由角色运行层收尾；这里仍不能确认安装是否发生。 */ }
        }
        InputDriver.halt(player);
        super.cleanup();
    }
    @Override public boolean mustSettleBeforeSatisfiedCancellation() { return submitted && !verified; }
    @Override public void stop(LocalPlayer companion, StopReason why) {
        if (equip != null) equip.stop(companion, why);
        super.stop(companion, why);
    }
    @Override protected String successMessage() { return alreadyInstalled ? "Exact AE2 part already observed." : "AE2 part installed with server confirmation."; }
    @Override protected Map<String, Object> resultData() {
        return Map.of("part_item", r.part.itemId(), "part_installed", verified || alreadyInstalled,
                "native_effect_verified", verified, "already_installed", alreadyInstalled,
                "failure_code", failureCode, "outcome_uncertain", submitted && !verified,
                "machine_production_verified", false);
    }
}
