// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.integration.machine.MachineMenu;
import org.maiwithu.maicraft.core.integration.machine.MachineSurvey;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.UnequipCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.UnequipTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 让物流分拣机只提取指定物品：腾出主手、打开菜单、等服务器同步、关闭无过滤自动弹出、保存精确过滤，最后关闭菜单。
 * 每项设置都等新服务器数据确认；已有不相容规则时报告冲突，不擅自删掉原规则。
 */
public final class MekanismFilterTask extends AbstractCompanionTask<MekanismFilterTaskRecord> {
    private enum Phase { HAND, OPEN, OBSERVE, CLOSE, DONE }
    private Phase phase;
    private Level world;
    private Task child;
    private TaskRecord childRecord;
    private AbstractContainerMenu ownedMenu;
    private NativeActionReceipt receipt;
    private MekanismFilterBridge.Snapshot before;
    private boolean disablingAuto, submitted, uncertain, filterVerified, closed;
    private int syncTicks, confirmedActions;
    private String failureCode = "", failureMessage = "";

    public MekanismFilterTask(LocalPlayer player, MekanismFilterTaskRecord record) { super(player, record); }
    @Override protected void onStart() {
        world = player.level();
        if (!world.isLoaded(r.target) || !BuiltInRegistries.ITEM.containsKey(r.itemId)
                || !BuiltInRegistries.BLOCK.getKey(world.getBlockState(r.target).getBlock()).toString().equals("mekanism:logistical_sorter")) {
            fail("The compiled sorter or requested output item is unavailable.", FailureType.TARGET_LOST); return;
        }
        var context = ClientRuntime.requireContext(player);
        if (player.containerMenu != player.inventoryMenu || context.minecraft().screen != null) {
            fail("An unrelated menu is open before sorter configuration.", FailureType.UNSUPPORTED); return;
        }
        if (player.getMainHandItem().isEmpty()) open();
        else {
            phase = Phase.HAND;
            childRecord = new UnequipTaskRecord(id(), deadline(200), List.of(EquipmentSlot.MAINHAND), "mainhand");
            child = new UnequipCompanionTask(player, (UnequipTaskRecord) childRecord);
        }
    }
    @Override protected TaskState onTick() {
        if (player.level() != world) return failure("sorter_world_changed", "The sorter's client world session changed.");
        if (child != null) return tickChild();
        if (world.getGameTime() >= r.getDeadlineGameTime()) return failure("sorter_configuration_timeout", "Sorter configuration timed out.");
        if (receipt != null) return confirm();
        if (submitted) return failure("sorter_receipt_missing", "A Sorter action was entered without a receipt; inspect before retrying.");
        if (phase == Phase.DONE) return TaskState.SUCCESS;
        if (player.containerMenu != ownedMenu || !world.isLoaded(r.target)) return failure("sorter_menu_changed", "The owned Sorter menu changed.");
        if (NavigationSafetyContext.protectsMutation(r.target)) return failure("sorter_protected", "The sorter is protected from configuration changes.");
        MekanismFilterBridge.Snapshot snapshot;
        try { snapshot = MekanismFilterBridge.inspect(player, r.target); }
        catch (IllegalArgumentException unavailable) { return failure("sorter_api_unavailable", unavailable.getMessage()); }
        // 过滤列表和自动弹出开关两份同步都要收到；当前最多等待一百六十次更新。
        if (!snapshot.sync().ready()) {
            if (++syncTicks > 160) return failure("sorter_filter_sync_unavailable", "The native Sorter did not synchronize its filter list and auto-eject tracker.");
            return TaskState.RUNNING;
        }
        var decision = MekanismFilterBridge.decide(snapshot.filters(), r.itemId.toString());
        if (decision == MekanismFilterBridge.Decision.CONFLICT) {
            return failure("sorter_existing_filters_conflict", "Existing Sorter filters conflict with the exact output contract; no filter was removed or hidden.");
        }
        if (!snapshot.autoEject() && decision == MekanismFilterBridge.Decision.READY) {
            filterVerified = true; close(); return TaskState.RUNNING;
        }
        var context = ClientRuntime.requireContext(player);
        if (!context.mutationAvailable() || !context.menus().ensureVisible(context)) return TaskState.RUNNING;
        // 先关掉绕过过滤的自动弹出，下一轮再保存过滤；先记为已经提交，异常后也不能盲目补发。
        before = snapshot; disablingAuto = snapshot.autoEject(); submitted = true;
        receipt = context.actions().submitProtocol(context, disablingAuto ? "sorter_disable_unfiltered_ejection" : "sorter_save_exact_item_filter",
                () -> {
                    if (disablingAuto) MekanismFilterBridge.disableAutoEject(player, r.target);
                    else MekanismFilterBridge.saveItemFilter(player, r.target, r.itemId, before);
                }, fresh -> observeReceipt(), 160);
        return TaskState.RUNNING;
    }

    // 关闭自动弹出时要求原过滤不变；修改过滤时要求弹出仍关闭，并收到比提交前更新的服务器数据。
    private NativeConfirmation.Verdict observeReceipt() {
        if (player.containerMenu != ownedMenu || player.level() != world) return NativeConfirmation.Verdict.DIVERGED;
        MekanismFilterBridge.Snapshot live;
        try { live = MekanismFilterBridge.inspect(player, r.target); }
        catch (RuntimeException unavailable) { return NativeConfirmation.Verdict.DIVERGED; }
        if (disablingAuto) {
            if (!live.originals().equals(before.originals())) return NativeConfirmation.Verdict.DIVERGED;
            return !live.autoEject() && live.sync().autoEjectRevision() > before.sync().autoEjectRevision()
                    ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING;
        }
        if (live.autoEject()) return NativeConfirmation.Verdict.DIVERGED;
        if (live.sync().filtersRevision() <= before.sync().filtersRevision()) return NativeConfirmation.Verdict.PENDING;
        return MekanismFilterBridge.decide(live.filters(), r.itemId.toString()) == MekanismFilterBridge.Decision.READY
                ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.DIVERGED;
    }
    private TaskState confirm() {
        var context = ClientRuntime.requireContext(player);
        receipt = context.actions().poll(context, receipt);
        if (!receipt.terminal()) return TaskState.RUNNING;
        if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            uncertain = true; receipt = null;
            return failure("sorter_action_unconfirmed", "The Sorter action was not confirmed by server tracker data; no blind replay was submitted.");
        }
        confirmedActions++; receipt = null; submitted = false; return TaskState.RUNNING;
    }
    private TaskState tickChild() {
        TaskState state = world.getGameTime() >= childRecord.getDeadlineGameTime() ? TaskState.TIMEOUT : runChild(child);
        r.extendDeadlineTo(childRecord.getDeadlineGameTime());
        if (state == null) return TaskState.RUNNING;
        if (state == TaskState.TIMEOUT) child.stop(player, StopReason.REPLACED);
        TaskResult result = child.result(state); child = null; childRecord = null;
        if (result == null || !result.success()) {
            uncertain |= result != null && result.data() != null && Boolean.TRUE.equals(result.data().get("outcome_uncertain"));
            if (phase == Phase.CLOSE) { fail("The owned Sorter menu did not close cleanly.", FailureType.UNKNOWN); return TaskState.FAILED; }
            return failure("sorter_native_stage_failed", result == null ? "Native Sorter stage returned no receipt." : result.message());
        }
        if (phase == Phase.HAND) {
            if (!player.getMainHandItem().isEmpty()) return failure("sorter_empty_hand_unavailable", "No inventory space is available for ordinary sorter opening.");
            open();
        } else if (phase == Phase.OPEN) { ownedMenu = player.containerMenu; phase = Phase.OBSERVE; }
        else if (phase == Phase.CLOSE) {
            closed = true; ownedMenu = null;
            if (!failureCode.isEmpty()) { fail(failureMessage, FailureType.UNSUPPORTED); return TaskState.FAILED; }
            phase = Phase.DONE; return TaskState.SUCCESS;
        }
        return TaskState.RUNNING;
    }
    private void open() {
        phase = Phase.OPEN;
        start(MachineMenu.openTask(id(), deadline(3600), new MachineMenu.OpenRequest(world.dimension().location().toString(),
                r.target, 0, MachineSurvey.fingerprint(player, r.target, 0), r.target)));
    }
    private void close() { phase = Phase.CLOSE; start(MachineMenu.closeTask(id(), deadline(100))); }
    private void start(TaskRecord record) { childRecord = record; child = TaskFactory.create(player, record); }
    private long deadline(long duration) { return Math.max(r.getDeadlineGameTime(), world.getGameTime() + duration); }
    private String id() { return r.getToolCallId() + "-sorter-" + phase + "-" + confirmedActions; }
    // 失败时若仍拥有原菜单且没有待确认请求，先尝试关好菜单，再把原失败原因交给父任务。
    private TaskState failure(String code, String message) {
        failureCode = code; failureMessage = message;
        if (ownedMenu != null && player.containerMenu == ownedMenu && receipt == null) { close(); return TaskState.RUNNING; }
        fail(message, FailureType.UNKNOWN); return TaskState.FAILED;
    }
    @Override protected void cleanup() {
        if (submitted && (receipt == null || receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED)) uncertain = true;
        if (child != null) { child.stop(player, StopReason.REPLACED); child.result(TaskState.CANCELLED); child = null; }
        try {
            var context = ClientRuntime.requireContext(player);
            if (receipt != null && !receipt.terminal()) {
                uncertain = true; context.actions().retireOneShotForTaskBoundary(context, receipt, "sorter filter task ended");
            }
            if (!closed && ownedMenu != null && player.containerMenu == ownedMenu) {
                context.menus().closeForTaskBoundary(context, 80, "sorter filter task owns its menu cleanup");
            }
        } catch (RuntimeException revoked) { /* 世界或角色已更换时，旧动作和旧界面由运行层收尾。 */ }
        super.cleanup();
    }
    @Override public void stop(LocalPlayer companion, StopReason why) { if (child != null) child.stop(companion, why); super.stop(companion, why); }
    @Override public boolean mustSettleBeforeSatisfiedCancellation() { return submitted || child != null || ownedMenu != null && !closed; }
    @Override protected String successMessage() { return "Sorter confirmed to extract only " + r.itemId + " with unfiltered auto-eject disabled; menu closed."; }
    @Override protected Map<String, Object> resultData() {
        return Map.of("filter_verified", filterVerified, "filtered_item_id", r.itemId.toString(), "owned_menu_closed", closed,
                "confirmed_native_actions", confirmedActions, "outcome_uncertain", uncertain,
                "effects_settled", receipt == null && !uncertain && closed, "failure_code", failureCode);
    }
}
