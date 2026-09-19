// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.inventory;

import com.mojang.serialization.JsonOps;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.ItemEntityReceipts;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.ActualViewConvergenceGate;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

/** 精确找料 → 原生拿到主手并关背包 → 平滑瞄准可达轨迹 → 一次投掷并等落点；不代替机器父任务移动或重复未知投料。 */
public final class TargetedDropCompanionTask extends AbstractCompanionTask<TargetedDropTaskRecord> {
    private final FirstPersonActionGate selection = new FirstPersonActionGate();
    private final ActualViewConvergenceGate view = new ActualViewConvergenceGate();
    private final Map<UUID, TargetedDropReceipt.Received> received = new LinkedHashMap<>();
    private ItemStack kind;
    private Object world;
    private Vec3 stance, aim;
    private int source = -1, removed, attempts;
    private boolean selected, uncertain;
    private NativeActionReceipt receipt;
    private TargetedDropReceipt evidence;
    private String issue;

    public TargetedDropCompanionTask(LocalPlayer player, TargetedDropTaskRecord record) { super(player, record); }

    @Override protected void onStart() {
        kind = r.exactItem(); world = player.level(); stance = player.position();
        if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) {
            reject("targeted_drop_existing_menu_or_cursor", FailureType.INTERRUPTED); return;
        }
        for (int slot = 1; slot <= 4; slot++) if (!player.inventoryMenu.getSlot(slot).getItem().isEmpty()) {
            reject("targeted_drop_existing_crafting_contents", FailureType.INTERRUPTED); return;
        }
        if (!settled()) { reject("targeted_drop_requires_stationary_support", FailureType.STANCE_DUD); return; }
        if (TargetedDropReceipt.count(player, kind) < r.count) { reject("targeted_drop_exact_material_shortage", FailureType.NO_MATERIAL); return; }
        aim = TargetedDropGeometry.aim(player, r.receiver, r.region).orElse(null);
        if (aim == null) reject("targeted_drop_no_clear_native_trajectory", FailureType.OUT_OF_REACH);
    }

    @Override protected TaskState onTick() {
        var context = ClientRuntime.requireContext(player);
        if (receipt != null) {
            receipt = context.actions().poll(context, receipt);
            if (!receipt.terminal()) return TaskState.RUNNING;
            if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED || evidence.received().isEmpty()) {
                uncertain = true; return reject("targeted_drop_outcome_uncertain", FailureType.UNKNOWN);
            }
            // 读取回执确认当刻冻结的增量，暂停后别处的库存变化不能改写这次已经确认的投料。
            acceptReceipt(); selected = false; source = -1; selection.reset(); view.reset();
            r.extendDeadlineTo(player.level().getGameTime() + 200);
        }
        if (removed == r.count) return TaskState.SUCCESS;
        if (player.level() != world || !player.level().isLoaded(r.receiver)) return reject("targeted_drop_receiver_world_changed", FailureType.TARGET_LOST);
        if (!context.permitsNativeActions()) return reject("targeted_drop_control_handed_over", FailureType.INTERRUPTED);
        context.body().applyMovement(BodyControlPort.Movement.STOPPED, context.tickRevision());
        if (!settled() || player.position().distanceToSqr(stance) > 0.04) return reject("targeted_drop_stance_changed", FailureType.STANCE_DUD);
        if (TargetedDropReceipt.count(player, kind) < r.count - removed) return reject("targeted_drop_material_changed", FailureType.NO_MATERIAL);
        if (!selected) {
            if (source < 0) source = sourceSlot();
            if (source < 0) return reject("targeted_drop_source_missing", FailureType.NO_MATERIAL);
            var status = selection.select(player, source);
            if (status == FirstPersonActionGate.Status.FAILED) return reject("targeted_drop_hand_preparation_failed", FailureType.UNKNOWN);
            if (status != FirstPersonActionGate.Status.READY) return TaskState.RUNNING;
            selected = true; return TaskState.RUNNING;
        }
        ItemStack held = player.getMainHandItem();
        if (!ItemStack.isSameItemSameComponents(kind, held)) return reject("targeted_drop_selected_item_changed", FailureType.TARGET_LOST);
        // 每次新出手前重新试算当前地形，但只通过身体入口平滑转头，不直接写玩家朝向。
        Vec3 currentAim = TargetedDropGeometry.aim(player, r.receiver, r.region).orElse(null);
        if (currentAim == null) return reject("targeted_drop_trajectory_changed", FailureType.OCCLUDED);
        if (currentAim.distanceToSqr(aim) > 0.0025) { aim = currentAim; view.reset(); }
        Vec3 direction = aim.subtract(player.getEyePosition());
        context.body().requestLook((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)),
                (float) -Math.toDegrees(Math.atan2(direction.y, Math.sqrt(direction.horizontalDistanceSqr()))), context.tickRevision());
        if (!view.ready(player, direction) || !context.mutationAvailable()) return TaskState.RUNNING;
        // 相机近似对齐后还要按真实朝向复核落点，不能把视角容差变成越出已审查接收域的许可。
        if (!TargetedDropGeometry.safeActualView(player, r.receiver, r.region)) return TaskState.RUNNING;
        if (!r.preThrowAllowed()) return reject("targeted_drop_native_input_neighborhood_changed", FailureType.TARGET_LOST);
        int amount = held.getCount() <= r.count - removed ? held.getCount() : 1;
        evidence = new TargetedDropReceipt(player, r.receiver, held, amount, r.region);
        // 来源不足一整堆时每次只丢一件；先进入不可重发状态，再让端口发原生 Q，异常也不能从头重新投料。
        attempts++;
        receipt = context.actions().dropSelected(context, held.copy(), amount == held.getCount(), evidence, 100);
        return TaskState.RUNNING;
    }

    private boolean settled() {
        return player.onGround() && player.getDeltaMovement().horizontalDistanceSqr() < 0.0025 && Math.abs(player.getDeltaMovement().y) < 0.15;
    }
    private int sourceSlot() {
        for (int slot = 0; slot < 36; slot++) if (ItemStack.isSameItemSameComponents(player.getInventory().getItem(slot), kind)) return slot;
        return -1;
    }
    private TaskState reject(String code, FailureType type) { issue = code; uncertain |= attempts > 0 && removed < r.count; fail(code, type); return TaskState.FAILED; }

    @Override protected void cleanup() {
        // 父任务暂停期间端口也可能完成回执；取消收尾先读取已冻结的成功，不能把确切移出量重新报成零。
        if (receipt != null && receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED && !evidence.received().isEmpty()) acceptReceipt();
        if (receipt != null && !receipt.terminal()) {
            uncertain = true;
            try { var context = ClientRuntime.requireContext(player); context.actions().retireOneShotForTaskBoundary(context, receipt, "targeted drop owner ended"); }
            catch (RuntimeException ignored) { /* 控制权已交给玩家时，不再争夺身体；未知投料仍禁止重发。 */ }
        }
        selection.reset(); super.cleanup();
    }

    private void acceptReceipt() {
        removed += evidence.amount();
        for (var credit : evidence.received()) received.merge(credit.observed().uuid(), credit,
                (old, next) -> new TargetedDropReceipt.Received(next.observed(), old.count() + next.count()));
        receipt = null; evidence = null;
    }

    @Override protected Map<String, Object> resultData() {
        var data = new LinkedHashMap<String, Object>();
        data.put("item_id", BuiltInRegistries.ITEM.getKey(r.exactItem().getItem()).toString()); data.put("requested_count", r.count);
        data.put("actual_removed_count", removed); data.put("confirmed_received_count", removed);
        data.put("observed_inventory_debit", evidence == null ? 0 : evidence.observedDebit());
        data.put("outcome_uncertain", uncertain || attempts > 0 && removed < r.count);
        data.put("mechanical_retry_allowed", attempts == 0); data.put("drop_attempts", attempts);
        data.put("received_entities", received.values().stream().map(this::describe).toList());
        if (issue != null) data.put("issue_code", issue);
        return data;
    }

    private Map<String, Object> describe(TargetedDropReceipt.Received credit) {
        var row = new LinkedHashMap<String, Object>(); var observed = credit.observed(); ItemStack stack = observed.stack();
        row.put("entity_id", observed.entityId()); row.put("entity_uuid", observed.uuid().toString());
        row.put("count", credit.count()); row.put("observed_count", stack.getCount());
        row.put("item_id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        try { row.put("stack", ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, player.registryAccess()), stack).getOrThrow()); }
        catch (RuntimeException missingRegistry) { row.put("stack_encoding_available", false); }
        return row;
    }

    @Override public Map<String, Object> progress() { return resultData(); }
    @Override protected String successMessage() { return "confirmed exactly " + removed + " items entered the receiving area through native drops"; }
    @Override protected String cancelledMessage() { return "targeted drop interrupted; any unconfirmed submitted items must not be sent again"; }
}
