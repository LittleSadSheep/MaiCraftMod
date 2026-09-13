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
import org.maiwithu.maicraft.core.task.container.ContainerSupplySources;
import org.maiwithu.maicraft.core.task.container.SemanticContainerTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** Deposits only the caller's proven excavation surplus. The caller retains tools/materials and owns returning to the worksite. */
public final class BuildExcavationSpoilSupply {
    public enum Status { RUNNING, DEPOSITED, FAILED }
    public record Tick(Status status, Map<String, Object> receipt) {}
    private LocalPlayer owner;
    private Level world;
    private String callId, failure;
    private long deadline;
    private int radius, index, attempts, serial;
    private List<String> protectedLabels = List.of();
    private List<ResourceLocation> items = List.of();
    private final Map<ResourceLocation, Integer> proved = new LinkedHashMap<>(), retained = new LinkedHashMap<>(), deposited = new LinkedHashMap<>();
    private final Set<BlockPos> visited = new LinkedHashSet<>();
    private Task child;
    private SemanticContainerTaskRecord childRecord;
    private ResourceLocation childItem;
    private int childLimit;
    private boolean uncertain;
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
        owner = player; world = player.level(); this.callId = callId == null ? "excavation-spoil" : callId;
        deadline = deadlineGameTime; this.radius = radius; this.protectedLabels = protectedLabels == null ? List.of() : List.copyOf(protectedLabels);
        for (var entry : provenCounts.entrySet()) {
            ResourceLocation item = entry.getKey(); Integer count = entry.getValue();
            if (item == null || !BuiltInRegistries.ITEM.containsKey(item) || BuiltInRegistries.ITEM.get(item) == Items.AIR
                    || count == null || count < 1 || count > SemanticContainerTaskRecord.MAX_COUNT || count > count(player, item))
                throw new IllegalArgumentException("excavation_spoil_quantity_not_carried");
            proved.put(item, count); retained.put(item, count(player, item) - count);
        }
        items = proved.keySet().stream().sorted(java.util.Comparator.comparing(ResourceLocation::toString)).toList();
        status = items.isEmpty() ? Status.DEPOSITED : Status.RUNNING;
    }
    public Tick tick(LocalPlayer player, Function<Task, TaskState> runChild) {
        if (status != Status.RUNNING) return new Tick(status, receipt());
        if (player != owner || player.level() != world) { cancel(owner); return fail("excavation_spoil_body_or_world_changed"); }
        if (child != null) return tickChild(runChild);
        if (!menuClosed()) return fail("excavation_spoil_foreign_menu_or_cursor");
        while (index < items.size() && remaining(items.get(index)) == 0) { index++; attempts = 0; visited.clear(); }
        if (index == items.size()) { status = Status.DEPOSITED; return new Tick(status, receipt()); }
        if (world.getGameTime() >= deadline) return fail("excavation_spoil_deadline");
        ResourceLocation item = items.get(index); int remaining = remaining(item);
        if (count(player, item) < retained.get(item) + remaining) return fail("excavation_spoil_inventory_changed_before_deposit");
        if (attempts >= ContainerSupplySources.MAX_ATTEMPTS) return fail("excavation_spoil_no_verified_storage_capacity");
        var candidates = ContainerSupplySources.candidates(player, player.blockPosition(), radius, List.of(item), visited, protectedLabels);
        if (candidates.isEmpty()) return fail("excavation_spoil_no_safe_loaded_container");
        var target = candidates.getFirst(); visited.addAll(target.footprint()); attempts++; childItem = item; childLimit = remaining;
        childRecord = SemanticContainerTaskRecord.depositAvailableAt(callId + "-deposit-" + (++serial),
                Math.min(deadline, world.getGameTime() + 2L * 60 * 20), item, remaining, target.position(), target.blockId(), protectedLabels);
        child = TaskFactory.create(player, childRecord);
        return new Tick(Status.RUNNING, receipt());
    }
    private Tick tickChild(Function<Task, TaskState> runChild) {
        TaskState terminal;
        if (world.getGameTime() >= childRecord.getDeadlineGameTime()) { child.stop(owner, Task.StopReason.REPLACED); terminal = TaskState.TIMEOUT; }
        else { terminal = runChild.apply(child); if (terminal == null || terminal == TaskState.RUNNING) return new Tick(Status.RUNNING, receipt()); }
        TaskResult result = child.result(terminal); child = null; childRecord = null;
        if (result == null || result.data() == null) return fail("excavation_spoil_receipt_missing");
        lastContainer = result.data(); uncertain |= Boolean.TRUE.equals(lastContainer.get("outcome_uncertain"));
        int moved;
        try { moved = verifiedCount(lastContainer, childItem, childLimit); }
        catch (IllegalArgumentException invalid) { uncertain = true; return fail("excavation_spoil_receipt_mismatch"); }
        deposited.merge(childItem, moved, Math::addExact);
        if (uncertain || terminal != TaskState.SUCCESS && Boolean.TRUE.equals(lastContainer.get("effects_started")))
            return fail("excavation_spoil_transfer_unsettled");
        if (!menuClosed()) return fail("excavation_spoil_menu_not_settled");
        return new Tick(Status.RUNNING, receipt());
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
    public long childDeadline() { return childRecord == null ? deadline : childRecord.getDeadlineGameTime(); }
    public boolean mustSettleBeforeSatisfiedCancellation() { return child != null && child.mustSettleBeforeSatisfiedCancellation(); }
    public void cancel(LocalPlayer player) {
        if (child != null) {
            child.stop(owner, Task.StopReason.REPLACED); var result = child.result(TaskState.CANCELLED);
            if (result != null && result.data() != null) lastContainer = result.data();
            try { deposited.merge(childItem, verifiedCount(lastContainer, childItem, childLimit), Math::addExact); }
            catch (IllegalArgumentException invalid) { uncertain = true; }
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
