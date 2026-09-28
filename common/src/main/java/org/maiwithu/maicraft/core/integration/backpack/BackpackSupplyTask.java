// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.backpack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.container.ContainerTransferTaskRecord;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskState;

/** 开包 -> 原生逐笔存取并核对主背包差量 -> 空鼠标关包；已提交的效果先结算，再归还身体。 */
public final class BackpackSupplyTask extends AbstractCompanionTask<BackpackSupplyTaskRecord> {
    interface Access {
        BackpackOpenSession.Status open(LocalPlayerContext context);
        BackpackOpenSession.Status close(LocalPlayerContext context);
        BackpackMenuAccess.Read read(LocalPlayer player);
        void cancel(LocalPlayerContext context);
        String failure();
        boolean uncertain();
    }
    private final Access access;
    private final Map<ResourceLocation, Integer> moved = new LinkedHashMap<>();
    private BackpackMenuAccess.Snapshot observed, transferView;
    private BackpackTransferPlan.Next planned;
    private ContainerTransferTaskRecord transferRecord;
    private Task transfer;
    private Map<String, Object> lastTransfer = Map.of();
    private int phase, before;
    private boolean started, closed, uncertain, settlementOnly;
    private String code;
    private FailureType failureType = FailureType.NO_MATERIAL;

    public BackpackSupplyTask(LocalPlayer player, BackpackSupplyTaskRecord record) { this(player, record, nativeAccess(record.backpackSlot)); }
    // 流程回放可替换开包观察口；实际物品搬运仍经同一任务工厂与确认账。
    BackpackSupplyTask(LocalPlayer player, BackpackSupplyTaskRecord record, Access access) { super(player, record); this.access = access; }
    private static Access nativeAccess(int slot) {
        var session = new BackpackOpenSession(slot);
        return new Access() {
            public BackpackOpenSession.Status open(LocalPlayerContext context) { return session.open(context); }
            public BackpackOpenSession.Status close(LocalPlayerContext context) { return session.close(context); }
            public BackpackMenuAccess.Read read(LocalPlayer player) { return BackpackMenuAccess.read(player); }
            public void cancel(LocalPlayerContext context) { session.cancel(context); }
            public String failure() { return session.failure(); }
            public boolean uncertain() { return session.uncertain(); }
        };
    }

    @Override protected TaskState onTick() {
        var context = ClientRuntime.requireContext(player); InputDriver.halt(player);
        if (closed) return complete();
        if (settlementOnly && !started) { closed = true; return complete(); }
        if (phase == 0) {
            started = true; var status = access.open(context);
            if (status == BackpackOpenSession.Status.FAILED) return broken("backpack_open_failed", access.uncertain());
            if (status != BackpackOpenSession.Status.READY) return TaskState.RUNNING;
            phase = 1;
        }
        if (phase == 3) {
            var status = access.close(context);
            if (status == BackpackOpenSession.Status.FAILED) return broken("backpack_close_unconfirmed", true);
            if (status != BackpackOpenSession.Status.CLOSED) return TaskState.RUNNING;
            closed = true; return complete();
        }
        var read = access.read(player);
        if (read.snapshot() == null) return broken("backpack_observation_" + read.status(), transfer != null);
        observed = read.snapshot();
        if (transfer != null) {
            // 升级改变菜单布局时不再向旧槽号发新点击；未知鼠标余物与已有回执保留供恢复检查。
            if (observed.menu() != transferView.menu() || !observed.playerSlots().equals(transferView.playerSlots())
                    || !observed.storageSlots().equals(transferView.storageSlots())) return broken("backpack_slot_layout_changed", true);
            TaskState terminal;
            if (player.level().getGameTime() >= transferRecord.getDeadlineGameTime()) {
                transfer.stop(player, StopReason.REPLACED); terminal = TaskState.TIMEOUT;
            } else terminal = runChild(transfer);
            if (terminal == null) return TaskState.RUNNING;
            var result = transfer.result(terminal); lastTransfer = result == null || result.data() == null ? Map.of() : result.data();
            transfer = null;
            if (terminal != TaskState.SUCCESS || result == null || !result.success()) return broken("backpack_transfer_unconfirmed", true);
            Object counts = lastTransfer.get("moved_counts");
            int confirmed = counts instanceof List<?> list && list.size() == 1 && list.getFirst() instanceof Number n ? n.intValue() : 0;
            int delta = count(planned.item()) - before; if (r.operation == BackpackSupplyTaskRecord.Operation.DEPOSIT) delta = -delta;
            if (confirmed < 0 || confirmed > planned.move().count() || delta != confirmed
                    || !settlementOnly && confirmed != planned.move().count() || !observed.menu().getCarried().isEmpty())
                return broken("backpack_inventory_delta_mismatch", true);
            if (confirmed > 0) moved.merge(planned.item(), confirmed, Math::addExact);
            r.extendDeadlineTo(player.level().getGameTime() + 1200);
            return TaskState.RUNNING;
        }
        Map<ResourceLocation, Integer> remaining = remaining();
        if (settlementOnly || r.operation == BackpackSupplyTaskRecord.Operation.OBSERVE || remaining.values().stream().allMatch(n -> n == 0)) {
            phase = 3; return TaskState.RUNNING;
        }
        planned = BackpackTransferPlan.next(player, observed, r.operation == BackpackSupplyTaskRecord.Operation.DEPOSIT, remaining);
        if (planned.move() == null) {
            code = planned.blocked(); failureType = "inventory_full".equals(code) ? FailureType.NO_SPACE : FailureType.NO_MATERIAL;
            phase = 3; return TaskState.RUNNING;
        }
        before = count(planned.item()); transferView = observed;
        transferRecord = new ContainerTransferTaskRecord(r.getToolCallId() + "-backpack-move", player.level().getGameTime() + 1200,
                observed.menu().containerId, List.of(planned.move()), false);
        transfer = TaskFactory.create(player, transferRecord); return TaskState.RUNNING;
    }
    private int count(ResourceLocation id) { return PlayerInv.carriedCount(player.getInventory(), BuiltInRegistries.ITEM.get(id)); }
    private Map<ResourceLocation, Integer> remaining() {
        var result = new LinkedHashMap<ResourceLocation, Integer>();
        if (r.operation == BackpackSupplyTaskRecord.Operation.WITHDRAW) {
            int left = Math.max(0, r.amount - moved.values().stream().mapToInt(Integer::intValue).sum());
            r.items.forEach(item -> result.put(item, left));
        } else r.deposits.forEach((item, amount) -> result.put(item, Math.max(0, amount - moved.getOrDefault(item, 0))));
        return result;
    }
    private TaskState broken(String failure, boolean unknown) { code = failure; uncertain |= unknown; fail(failure, FailureType.TARGET_LOST); return TaskState.FAILED; }
    private TaskState complete() {
        if (code != null) { fail(code, failureType); return TaskState.FAILED; }
        return TaskState.SUCCESS;
    }
    @Override public boolean mustSettleBeforeSatisfiedCancellation() { return started && !closed; }
    @Override public void requestSatisfiedSettlement() { settlementOnly = true; if (transfer != null) transfer.requestSatisfiedSettlement(); }
    @Override public void stop(LocalPlayer companion, StopReason reason) {
        if (transfer != null) transfer.stop(companion, reason);
        if (reason != StopReason.PREEMPTED && !closed) uncertain = true;
        super.stop(companion, reason);
    }
    @Override protected void cleanup() {
        if (!closed && started) {
            boolean preserveTransfer = transfer != null || Boolean.TRUE.equals(lastTransfer.get("outcome_uncertain")) || uncertain && !lastTransfer.isEmpty();
            if (transfer != null) { transfer.stop(player, StopReason.REPLACED); var result = transfer.result(TaskState.CANCELLED);
                if (result != null && result.data() != null) lastTransfer = result.data(); transfer = null; uncertain = true; }
            // 存取点击未结算时，即使客户端鼠标暂时为空也不关包，避免晚到的物品因背包已满而掉在地上。
            if (!preserveTransfer) ClientRuntime.actor().activeContext().filter(context -> context.player() == player && context.isCurrent()).ifPresent(access::cancel);
            uncertain |= phase > 0 || preserveTransfer;
        }
        super.cleanup();
    }
    @Override protected Map<String, Object> resultData() {
        var data = new LinkedHashMap<String, Object>();
        data.put("operation", r.operation.name().toLowerCase(Locale.ROOT)); data.put("storage", "sophisticated_backpack");
        data.put(r.operation == BackpackSupplyTaskRecord.Operation.DEPOSIT ? "deposited" : "withdrawn", strings(moved));
        data.put("confirmed_moved_total", moved.values().stream().mapToInt(Integer::intValue).sum());
        if (r.operation == BackpackSupplyTaskRecord.Operation.DEPOSIT)
            data.put("confirmed_deposited_total", moved.values().stream().mapToInt(Integer::intValue).sum());
        data.put("effects_started", !moved.isEmpty() || transfer != null || !lastTransfer.isEmpty());
        data.put("outcome_uncertain", uncertain || access.uncertain()); data.put("menu_closed", closed);
        if (code != null) data.put("failure_code", code);
        if (access.failure() != null) data.put("access_detail", access.failure());
        if (observed != null) { data.put("storage_id", observed.storageId()); data.put("observed_game_tick", observed.observedTick());
            data.put("stored", strings(observed.stored())); data.put("extractable", strings(observed.extractable())); }
        if (!lastTransfer.isEmpty()) data.put("last_native_transfer", lastTransfer);
        return data;
    }
    @Override protected String successMessage() { return "native backpack " + r.operation.name().toLowerCase(Locale.ROOT) + " settled and its menu was closed"; }
    private static Map<String, Object> strings(Map<ResourceLocation, ?> values) {
        var result = new LinkedHashMap<String, Object>(); values.forEach((id, value) -> result.put(id.toString(), value)); return result;
    }
}
