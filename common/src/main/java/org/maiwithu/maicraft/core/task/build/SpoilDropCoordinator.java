// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.inventory.DropItemsTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 施工余土的丢弃通道：spoil_policy=drop 时把已核实的普通余土交给 maicraft:drop_items 原语，
 * 不搜索任何容器——野外浇门因此不需要「48 格内有箱子」前置。
 * 作用域与存入通道同源（BuildExcavationCargo 的普通物品 + 无自定义组件 + 超过自留底线），
 * 角色入场前随身携带的物品一概不在本清单内。
 * 对账只认背包净减量：丢弃子任务的回执状态只是线索，净减量才是真实世界证据；
 * 回执不确定且净减量未闭合时保留现场失败，不自动重发（消费可能已发生）。
 */
public final class SpoilDropCoordinator {
    public enum Status { RUNNING, DROPPED, FAILED }
    public record Tick(Status status, Map<String, Object> receipt) {}
    /** 同一种物品最多重发一次：仅当上一轮净减量为零且回执明确未执行时才安全。 */
    private static final int MAX_ATTEMPTS_PER_ITEM = 2;

    private LocalPlayer owner;
    private Level world;
    private String callId, failure;
    private long deadline;
    private int index, serial;
    private List<ResourceLocation> items = List.of();
    private final Map<ResourceLocation, Integer> proved = new LinkedHashMap<>(),
            retained = new LinkedHashMap<>(), dropped = new LinkedHashMap<>();
    private Task child;
    private TaskRecord childRecord;
    private ResourceLocation childItem;
    private int childLimit, childBefore, childAttempts;
    private boolean uncertain;
    private Status status = Status.DROPPED;

    public void begin(LocalPlayer player, String callId, long deadlineGameTime, Map<ResourceLocation, Integer> provenCounts) {
        if (active()) throw new IllegalStateException("excavation_spoil_drop_already_active");
        if (player == null || provenCounts == null || provenCounts.size() > 64)
            throw new IllegalArgumentException("excavation_spoil_drop_scope");
        proved.clear(); retained.clear(); dropped.clear(); index = serial = 0;
        status = Status.FAILED;
        failure = null; uncertain = false; child = null; childRecord = null; childAttempts = 0;
        owner = player; world = player.level();
        this.callId = callId == null ? "excavation-spoil-drop" : callId;
        deadline = Math.min(deadlineGameTime, player.level().getGameTime() + 10L * 60 * 20);
        for (var entry : provenCounts.entrySet()) {
            ResourceLocation item = entry.getKey(); Integer count = entry.getValue();
            // 与存入通道同一道闸：非普通物品、带组件的珍藏或不在背包里的数量都拒绝接单。
            if (item == null || !BuiltInRegistries.ITEM.containsKey(item) || BuiltInRegistries.ITEM.get(item) == Items.AIR
                    || !BuildExcavationCargo.ordinary(BuiltInRegistries.ITEM.get(item))
                    || !BuildExcavationCargo.plain(player, BuiltInRegistries.ITEM.get(item))
                    || count == null || count < 1 || count > count(player, item))
                throw new IllegalArgumentException("excavation_spoil_drop_quantity_not_carried");
            proved.put(item, count); retained.put(item, count(player, item) - count);
        }
        items = proved.keySet().stream().sorted(Comparator.comparing(ResourceLocation::toString)).toList();
        status = items.isEmpty() ? Status.DROPPED : Status.RUNNING;
    }

    public Tick tick(LocalPlayer player, Function<Task, TaskState> runChild) {
        if (status != Status.RUNNING) return new Tick(status, receipt());
        if (player != owner || player.level() != world) { cancel(owner); return fail("excavation_spoil_body_or_world_changed"); }
        if (child != null) return tickChild(runChild);
        while (index < items.size() && remaining(items.get(index)) == 0) { index++; childAttempts = 0; }
        if (index == items.size()) { status = Status.DROPPED; return new Tick(status, receipt()); }
        if (player.level().getGameTime() >= deadline) return fail("excavation_spoil_drop_deadline");
        ResourceLocation item = items.get(index);
        if (!BuildExcavationCargo.plain(player, BuiltInRegistries.ITEM.get(item)))
            return fail("excavation_spoil_item_components_changed");
        if (count(player, item) < retained.get(item) + remaining(item))
            return fail("excavation_spoil_inventory_changed_before_drop");
        childAttempts++; childItem = item; childLimit = remaining(item);
        childBefore = count(player, item);
        childRecord = new DropItemsTaskRecord(callId + "-drop-" + (++serial),
                Math.min(deadline, player.level().getGameTime() + 2L * 60 * 20),
                BuiltInRegistries.ITEM.get(item), childLimit,
                BuiltInRegistries.ITEM.getKey(BuiltInRegistries.ITEM.get(item)).getPath());
        child = TaskFactory.create(player, childRecord);
        return new Tick(Status.RUNNING, receipt());
    }

    private Tick tickChild(Function<Task, TaskState> runChild) {
        TaskState terminal;
        if (owner.level().getGameTime() >= Math.min(deadline, childRecord.getDeadlineGameTime())) {
            child.stop(owner, Task.StopReason.REPLACED); terminal = TaskState.TIMEOUT;
        } else {
            terminal = runChild.apply(child);
            if (terminal == null || terminal == TaskState.RUNNING) return new Tick(Status.RUNNING, receipt());
        }
        TaskResult result = child.result(terminal); child = null; childRecord = null;
        boolean receiptUncertain = result != null && result.data() != null
                && Boolean.TRUE.equals(result.data().get("outcome_uncertain"));
        // 世界证据优先：背包净减量即已离手数量；回执只补充"是否还有未知投掷在途"。
        int net = childBefore - count(owner, childItem);
        if (net < 0) { uncertain = true; return fail("excavation_spoil_inventory_grew_during_drop"); }
        if (net > childLimit) { uncertain = true; return fail("excavation_spoil_inventory_delta_mismatch"); }
        dropped.merge(childItem, net, Math::addExact);
        if (net == 0) {
            // 服务器明确拒绝时物品仍在背包、无消费，可按有限次数重试；其余零减量一律按不确定收场。
            if (receiptUncertain || terminal != TaskState.SUCCESS || childAttempts >= MAX_ATTEMPTS_PER_ITEM) {
                uncertain |= receiptUncertain || terminal != TaskState.SUCCESS;
                return fail("excavation_spoil_drop_unsettled");
            }
            return new Tick(Status.RUNNING, receipt());
        }
        if (remaining(childItem) > 0 && receiptUncertain) {
            // 部分离手后仍有未知投掷在途；先结清现场，不带着悬案继续丢下一种。
            uncertain = true; return fail("excavation_spoil_drop_unsettled");
        }
        childAttempts = 0;
        return new Tick(Status.RUNNING, receipt());
    }

    public boolean active() { return status == Status.RUNNING; }
    public long childDeadline() { return childRecord == null ? deadline : Math.min(deadline, childRecord.getDeadlineGameTime()); }
    public boolean mustSettleBeforeSatisfiedCancellation() { return child != null && child.mustSettleBeforeSatisfiedCancellation(); }

    public void cancel(LocalPlayer player) {
        if (child != null) {
            child.stop(owner, Task.StopReason.REPLACED);
            var result = child.result(TaskState.CANCELLED);
            int net = childBefore - count(owner, childItem);
            // 取消瞬间已确认的净减量如实入账；未结投掷按不确定上报。
            if (net > 0 && net <= childLimit) dropped.merge(childItem, net, Math::addExact);
            uncertain |= net != 0 || result == null || result.data() == null
                    || Boolean.TRUE.equals(result.data().get("outcome_uncertain"));
            child = null; childRecord = null;
        }
        if (active()) { failure = "excavation_spoil_drop_cancelled"; status = Status.FAILED; }
    }

    public Map<String, Object> receipt() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("proven_spoil", strings(proved)); result.put("retained_inventory_floor", strings(retained));
        result.put("confirmed_dropped", strings(dropped));
        Map<ResourceLocation, Integer> remaining = new LinkedHashMap<>();
        proved.keySet().forEach(item -> remaining.put(item, remaining(item)));
        result.put("remaining", strings(remaining));
        result.put("outcome_uncertain", uncertain);
        result.put("drop_attempts", serial);
        result.put("all_proven_spoil_dropped", status == Status.DROPPED);
        result.put("spoil_policy", "drop");
        if (failure != null) result.put("failure_code", failure);
        return Map.copyOf(result);
    }

    private Tick fail(String code) { failure = code; status = Status.FAILED; return new Tick(status, receipt()); }
    private int remaining(ResourceLocation item) { return Math.max(0, proved.getOrDefault(item, 0) - dropped.getOrDefault(item, 0)); }
    private static int count(LocalPlayer player, ResourceLocation item) { return PlayerInv.buildableCount(player.getInventory(), BuiltInRegistries.ITEM.get(item)); }
    private static Map<String, Integer> strings(Map<ResourceLocation, Integer> values) {
        Map<String, Integer> result = new LinkedHashMap<>();
        values.forEach((id, count) -> result.put(id.toString(), count));
        return Map.copyOf(result);
    }
}
