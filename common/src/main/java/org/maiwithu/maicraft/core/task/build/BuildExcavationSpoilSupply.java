// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ResourceSupply;
import org.maiwithu.maicraft.core.task.container.ContainerSupplySources;
import org.maiwithu.maicraft.core.task.container.SemanticContainerTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;
import java.util.Comparator;

/** 将施工确认可存的余料通过真实木桶界面分箱存好；保留工具、建材和返程安排，不直接清包或丢物。 */
public final class BuildExcavationSpoilSupply {
    public enum Status { RUNNING, DEPOSITED, FAILED }
    public record Tick(Status status, Map<String, Object> receipt) {}
    private LocalPlayer owner;
    private Level world;
    private BlockPos searchOrigin;
    private String callId, failure;
    private long deadline;
    private int radius, index, attempts, serial;
    private List<String> protectedLabels = List.of();
    private List<ResourceLocation> items = List.of();
    private final Map<ResourceLocation, Integer> proved = new LinkedHashMap<>(), retained = new LinkedHashMap<>(), deposited = new LinkedHashMap<>();
    private final Set<BlockPos> visited = new LinkedHashSet<>();
    private Task child;
    private TaskRecord childRecord;
    private ResourceLocation childItem;
    private int childLimit, childBefore;
    private boolean uncertain;
    private boolean aeAttempted, aeChild;
    private String ordinaryStorageFailure;
    private Map<ResourceLocation, Integer> aeLimits = Map.of(), aeBefore = Map.of();
    private Status status = Status.DEPOSITED;
    private Map<String, Object> lastContainer = Map.of();

    public void begin(LocalPlayer player, String callId, long deadlineGameTime, Map<ResourceLocation, Integer> provenCounts,
            List<String> protectedLabels, int radius) {
        if (active()) throw new IllegalStateException("excavation_spoil_deposit_already_active");
        if (player == null || provenCounts == null || provenCounts.size() > 64 || radius < 1 || radius > 48)
            throw new IllegalArgumentException("excavation_spoil_deposit_scope");
        proved.clear(); retained.clear(); deposited.clear(); visited.clear(); index = attempts = serial = 0;
        status = Status.FAILED;
        failure = null; uncertain = false; lastContainer = Map.of(); child = null; childRecord = null;
        aeAttempted = aeChild = false; aeLimits = aeBefore = Map.of(); ordinaryStorageFailure = null;
        owner = player; world = player.level(); this.callId = callId == null ? "excavation-spoil" : callId;
        // 固定出坑后的仓库搜索中心，并限制整次整理时长，不能走到一只箱子后再向外扩张搜索范围。
        searchOrigin = player.blockPosition().immutable();
        deadline = Math.min(deadlineGameTime, world.getGameTime() + 10L * 60 * 20);
        this.radius = radius; this.protectedLabels = protectedLabels == null ? List.of() : List.copyOf(protectedLabels);
        for (var entry : provenCounts.entrySet()) {
            ResourceLocation item = entry.getKey(); Integer count = entry.getValue();
            if (item == null || !BuiltInRegistries.ITEM.containsKey(item) || BuiltInRegistries.ITEM.get(item) == Items.AIR
                    || !BuildExcavationCargo.ordinary(BuiltInRegistries.ITEM.get(item))
                    || !BuildExcavationCargo.plain(player, BuiltInRegistries.ITEM.get(item))
                    || count == null || count < 1 || count > SemanticContainerTaskRecord.MAX_COUNT || count > count(player, item))
                throw new IllegalArgumentException("excavation_spoil_quantity_not_carried");
            proved.put(item, count); retained.put(item, count(player, item) - count);
        }
        items = proved.keySet().stream().sorted(Comparator.comparing(ResourceLocation::toString)).toList();
        status = items.isEmpty() ? Status.DEPOSITED : Status.RUNNING;
    }
    public Tick tick(LocalPlayer player, Function<Task, TaskState> runChild) {
        // 存土石 -> 确认背包与箱内数量变化 -> 关箱；外来界面或游标出现时先停下，不替玩家处理它们。
        if (status != Status.RUNNING) return new Tick(status, receipt());
        if (player != owner || player.level() != world) { cancel(owner); return fail("excavation_spoil_body_or_world_changed"); }
        if (child != null) return tickChild(runChild);
        if (!menuClosed()) return fail("excavation_spoil_foreign_menu_or_cursor");
        while (index < items.size() && remaining(items.get(index)) == 0) { index++; attempts = 0; visited.clear(); }
        if (index == items.size()) { status = Status.DEPOSITED; return new Tick(status, receipt()); }
        if (world.getGameTime() >= deadline) return fail("excavation_spoil_deadline");
        ResourceLocation item = items.get(index); int remaining = remaining(item);
        if (!BuildExcavationCargo.plain(player, BuiltInRegistries.ITEM.get(item))) return fail("excavation_spoil_item_components_changed");
        if (count(player, item) < retained.get(item) + remaining) return fail("excavation_spoil_inventory_changed_before_deposit");
        if (attempts >= ContainerSupplySources.MAX_ATTEMPTS) return tryAeStorage("excavation_spoil_no_verified_storage_capacity");
        var candidates = ContainerSupplySources.candidates(player, searchOrigin, radius, List.of(item), visited, protectedLabels).stream()
                .filter(candidate -> candidate.footprint().stream().allMatch(at -> at.distSqr(searchOrigin) <= (long) radius * radius))
                .sorted(Comparator.comparingDouble(candidate -> candidate.position().distSqr(player.blockPosition()))).toList();
        if (candidates.isEmpty()) return tryAeStorage("excavation_spoil_no_safe_loaded_container");
        var target = candidates.getFirst(); visited.addAll(target.footprint()); attempts++; childItem = item; childLimit = remaining;
        childBefore = count(player, item);
        childRecord = SemanticContainerTaskRecord.depositAvailableAt(callId + "-deposit-" + (++serial),
                Math.min(deadline, world.getGameTime() + 2L * 60 * 20), item, remaining, target.position(), target.blockId(), protectedLabels);
        child = TaskFactory.create(player, childRecord);
        return new Tick(Status.RUNNING, receipt());
    }
    private Tick tickChild(Function<Task, TaskState> runChild) {
        if (aeChild) return tickAeChild(runChild);
        // 去仓库途中若物品被改名或赋予组件，先结束自己的界面任务，不能继续按同一编号存入珍藏。
        if (!BuildExcavationCargo.plain(owner, BuiltInRegistries.ITEM.get(childItem))) {
            cancel(owner); return fail("excavation_spoil_item_components_changed");
        }
        TaskState terminal;
        if (world.getGameTime() >= Math.min(deadline, childRecord.getDeadlineGameTime())) { child.stop(owner, Task.StopReason.REPLACED); terminal = TaskState.TIMEOUT; }
        else { terminal = runChild.apply(child); if (terminal == null || terminal == TaskState.RUNNING) return new Tick(Status.RUNNING, receipt()); }
        TaskResult result = child.result(terminal); child = null; childRecord = null;
        if (result == null || result.data() == null) return fail("excavation_spoil_receipt_missing");
        var evidence = new LinkedHashMap<String, Object>(result.data());
        // 保留实际失败原因，终端适配失败不能只剩一个错误码，让续建检查无法解释为何没存进去。
        if (result.message() != null) evidence.put("message", result.message());
        lastContainer = Map.copyOf(evidence); uncertain |= Boolean.TRUE.equals(lastContainer.get("outcome_uncertain"));
        int moved;
        try { moved = verifiedCount(lastContainer, childItem, childLimit); }
        catch (IllegalArgumentException invalid) { uncertain = true; return fail("excavation_spoil_receipt_mismatch"); }
        // 除了箱子子任务的双侧回执，还复核真实背包差量；并发拾取或丢失导致不符时不盲目重放。
        if (count(owner, childItem) != childBefore - moved) { uncertain = true; return fail("excavation_spoil_inventory_delta_mismatch"); }
        deposited.merge(childItem, moved, Math::addExact);
        if (uncertain || terminal != TaskState.SUCCESS && Boolean.TRUE.equals(lastContainer.get("effects_started")))
            return fail("excavation_spoil_transfer_unsettled");
        if (!menuClosed()) return fail("excavation_spoil_menu_not_settled");
        return new Tick(Status.RUNNING, receipt());
    }

    private Tick tryAeStorage(String ordinaryFailure) {
        // 普通箱子无空位时只尝试一次 AE，把全部剩余土石合并成一趟可见终端操作，不能逐种重复往返。
        if (aeAttempted || !Ae2ResourceSupply.available()) return fail(ordinaryFailure);
        aeAttempted = true; ordinaryStorageFailure = ordinaryFailure;
        Map<ResourceLocation, Integer> limits = new LinkedHashMap<>(), before = new LinkedHashMap<>();
        for (ResourceLocation item : items) if (remaining(item) > 0) {
            if (!BuildExcavationCargo.plain(owner, BuiltInRegistries.ITEM.get(item))
                    || count(owner, item) != retained.get(item) + remaining(item)) return fail("excavation_spoil_inventory_changed_before_deposit");
            limits.put(item, remaining(item)); before.put(item, count(owner, item));
        }
        aeLimits = Map.copyOf(limits); aeBefore = Map.copyOf(before);
        var request = new Ae2ResourceSupply.Request(limits.entrySet().stream()
                .map(entry -> new Ae2ResourceSupply.Group(entry.getKey(), entry.getValue())).toList(), false,
                Ae2ResourceSupply.Operation.DEPOSIT);
        childRecord = Ae2ResourceSupply.taskRecord(callId + "-ae-deposit", Math.min(deadline, world.getGameTime() + 2L * 60 * 20), request,
                // 固定终端仍受本次出坑位置、已加载范围与主人保护约束限制，不能借记忆中的终端走到别处。
                at -> owner.level() == world && at.distSqr(searchOrigin) <= (long) radius * radius
                        && ContainerSupplySources.accessAllowed(owner, at, protectedLabels));
        child = TaskFactory.create(owner, childRecord); aeChild = true;
        return new Tick(Status.RUNNING, receipt());
    }

    private Tick tickAeChild(Function<Task, TaskState> runChild) {
        TaskState terminal;
        if (world.getGameTime() >= Math.min(deadline, childRecord.getDeadlineGameTime())) {
            child.stop(owner, Task.StopReason.REPLACED); terminal = TaskState.TIMEOUT;
        } else {
            terminal = runChild.apply(child);
            if (terminal == null || terminal == TaskState.RUNNING) return new Tick(Status.RUNNING, receipt());
        }
        TaskResult result = child.result(terminal); child = null; childRecord = null;
        if (!accountAe(result)) return fail("excavation_spoil_ae_receipt_mismatch");
        if (uncertain || !menuClosed()) { uncertain = true; return fail("excavation_spoil_transfer_unsettled"); }
        if (terminal == TaskState.SUCCESS && items.stream().allMatch(item -> remaining(item) == 0)) {
            status = Status.DEPOSITED; return new Tick(status, receipt());
        }
        // 无终端或满网且没有发生存入时，保留原来“暂无存储容量”的原因，供施工判断是否可以稍后整理。
        return fail(Boolean.TRUE.equals(lastContainer.get("effects_started"))
                ? "excavation_spoil_ae_deposit_incomplete" : ordinaryStorageFailure);
    }

    private boolean accountAe(TaskResult result) {
        // AE 会话已经核对网络增加；整理器再核对每种物品的背包减少，不能只凭物品离开背包就记成入库。
        if (result == null || result.data() == null) { uncertain = true; return false; }
        var evidence = new LinkedHashMap<String, Object>(result.data());
        // AE 的协议或界面失败也保留原始说明，避免只回报“没有箱子”而隐藏真正需要修复的适配原因。
        if (result.message() != null) evidence.put("message", result.message());
        lastContainer = Map.copyOf(evidence); uncertain |= Boolean.TRUE.equals(lastContainer.get("outcome_uncertain"));
        try {
            Map<ResourceLocation, Integer> moved = verifiedAeCounts(lastContainer, aeLimits);
            for (ResourceLocation item : aeLimits.keySet()) if (count(owner, item) != aeBefore.get(item) - moved.getOrDefault(item, 0))
                throw new IllegalArgumentException("AE inventory delta mismatch");
            moved.forEach((item, count) -> deposited.merge(item, count, Math::addExact));
            return true;
        } catch (IllegalArgumentException | ArithmeticException invalid) { uncertain = true; return false; }
    }

    static Map<ResourceLocation, Integer> verifiedAeCounts(Map<String, Object> data, Map<ResourceLocation, Integer> limits) {
        if (!"deposit".equals(data.get("operation")) || !(data.get("deposited") instanceof Map<?, ?> rows)
                || !(data.get("confirmed_deposited_total") instanceof Number total)) throw new IllegalArgumentException("missing AE deposit evidence");
        Map<ResourceLocation, Integer> result = new LinkedHashMap<>(); int sum = 0;
        for (var row : rows.entrySet()) {
            if (!(row.getKey() instanceof String key) || !(row.getValue() instanceof Number count)
                    || count.doubleValue() != count.intValue() || count.intValue() < 0) throw new IllegalArgumentException("invalid AE deposit count");
            ResourceLocation item = ResourceLocation.tryParse(key);
            if (item == null || !limits.containsKey(item) || count.intValue() > limits.get(item)) throw new IllegalArgumentException("unapproved AE deposit");
            result.put(item, count.intValue()); sum = Math.addExact(sum, count.intValue());
        }
        if (total.doubleValue() != sum) throw new IllegalArgumentException("AE deposit totals differ");
        return Map.copyOf(result);
    }
    static int verifiedCount(Map<String, Object> data, ResourceLocation item, int maximum) {
        if (!"deposit".equals(data.get("operation")) || !Boolean.TRUE.equals(data.get("bounded_storage_deposit")))
            throw new IllegalArgumentException("not an exact deposit receipt");
        Object raw = data.get("moved_count");
        if (!(raw instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 0 || n.intValue() > maximum
                || !(data.get("moved_items") instanceof Map<?, ?> moved)) throw new IllegalArgumentException("unverified deposit count");
        int actual = 0;
        for (var entry : moved.entrySet()) {
            if (!item.toString().equals(entry.getKey()) || !(entry.getValue() instanceof Number count)
                    || count.doubleValue() != count.intValue() || count.intValue() < 0) throw new IllegalArgumentException("unexpected deposited item");
            actual = Math.addExact(actual, count.intValue());
        }
        if (actual != n.intValue()) throw new IllegalArgumentException("deposit totals differ");
        return actual;
    }
    public boolean active() { return status == Status.RUNNING; }
    public long childDeadline() { return childRecord == null ? deadline : Math.min(deadline, childRecord.getDeadlineGameTime()); }
    public boolean mustSettleBeforeSatisfiedCancellation() { return child != null && child.mustSettleBeforeSatisfiedCancellation(); }
    public void cancel(LocalPlayer player) {
        if (child != null) {
            child.stop(owner, Task.StopReason.REPLACED); var result = child.result(TaskState.CANCELLED);
            if (aeChild) accountAe(result);
            else {
                if (result != null && result.data() != null) lastContainer = result.data();
                try { deposited.merge(childItem, verifiedCount(lastContainer, childItem, childLimit), Math::addExact); }
                catch (IllegalArgumentException invalid) { uncertain = true; }
            }
            uncertain |= Boolean.TRUE.equals(lastContainer.get("outcome_uncertain")) || Boolean.TRUE.equals(lastContainer.get("effects_started"));
            child = null; childRecord = null;
        }
        if (active()) { failure = "excavation_spoil_cancelled"; status = Status.FAILED; }
    }
    public Map<String, Object> receipt() {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("proven_spoil", strings(proved)); result.put("retained_inventory_floor", strings(retained));
        result.put("confirmed_deposited", strings(deposited)); Map<ResourceLocation, Integer> remaining = new LinkedHashMap<>(); proved.keySet().forEach(item -> remaining.put(item, remaining(item)));
        result.put("remaining", strings(remaining)); result.put("outcome_uncertain", uncertain); result.put("warehouse_attempts", serial);
        result.put("all_proven_spoil_deposited", status == Status.DEPOSITED); result.put("discard_action_requested", false);
        if (searchOrigin != null) result.put("storage_search_origin", List.of(searchOrigin.getX(), searchOrigin.getY(), searchOrigin.getZ()));
        result.put("storage_search_radius", radius);
        result.put("ae_storage_attempted", aeAttempted);
        if (failure != null) result.put("failure_code", failure);
        if (!lastContainer.isEmpty()) result.put("last_container_receipt", lastContainer);
        return Map.copyOf(result);
    }
    private Tick fail(String code) { failure = code; status = Status.FAILED; return new Tick(status, receipt()); }
    private boolean menuClosed() { return owner.containerMenu == owner.inventoryMenu && owner.inventoryMenu.getCarried().isEmpty() && Minecraft.getInstance().screen == null; }
    private int remaining(ResourceLocation item) { return Math.max(0, proved.getOrDefault(item, 0) - deposited.getOrDefault(item, 0)); }
    private static int count(LocalPlayer player, ResourceLocation item) { return PlayerInv.buildableCount(player.getInventory(), BuiltInRegistries.ITEM.get(item)); }
    private static Map<String, Integer> strings(Map<ResourceLocation, Integer> values) { Map<String, Integer> result = new LinkedHashMap<>(); values.forEach((id, count) -> result.put(id.toString(), count)); return Map.copyOf(result); }
}
