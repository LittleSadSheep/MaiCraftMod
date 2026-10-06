// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 手持原料右键置物台顶面放上 -> 等台上原料被现场机器（注液器、压机、鼓风机等）原生加工掉 -> 空手右键收回台上物品与产物。
 * 两次右键都交给通用交互子任务完成走近、备物和瞄准；子任务结束后再读置物台实际物品与背包变化下结论，
 * 因为空手收取时方块状态和手上物品都可能不变，原生回执本身证明不了收没收到。
 * 动作是否完成与加工是否发生分开报告：等到上限仍未加工也照样收回，并如实说明原料没有被加工。
 */
final class DepotStationProcessTask extends AbstractCompanionTask<DepotStationProcessTaskRecord> {
    static final ResourceLocation DEPOT = ResourceLocation.fromNamespaceAndPath("create", "depot");
    /** 子任务结束后给服务器同步置物台与背包留出的观察窗口。 */
    private static final int SETTLE_TICKS = 20;
    /** 原料被加工掉后再稍等，让同一批的剩余产物落进置物台输出格。 */
    private static final int OUTPUT_SETTLE_TICKS = 10;

    private enum Phase { PLACE, CONFIRM_PLACE, WAIT, COLLECT, CONFIRM_COLLECT }

    private Phase phase = Phase.PLACE;
    private Task child;
    private InteractAtTaskRecord childRecord;
    private Map<String, Integer> baseline;
    private long phaseStarted, processedAt = -1;
    private int placedCount;
    private String heldAfterProcessing = "";
    private int heldAfterProcessingCount;
    private boolean placeAttempted, placed, processingObserved, processingTimedOut, collectAttempted, collected;
    private String placeReceipt, collectReceipt, failureCode;
    private Map<String, Integer> changes = Map.of();

    DepotStationProcessTask(LocalPlayer player, DepotStationProcessTaskRecord record) { super(player, record); }

    @Override protected void onStart() {
        baseline = inventoryCounts(player);
    }

    @Override protected TaskState onTick() {
        Level level = player.level();
        if (child != null) return tickChild(level);
        if (!level.isLoaded(r.station) || !isDepot(level.getBlockState(r.station).getBlock()))
            return failure("station_lost", "The depot is no longer present or loaded at the requested position.", FailureType.TARGET_LOST);
        if (NavigationSafetyContext.protectsUse(r.station))
            return failure("station_protected", "The depot is inside an explicitly protected area.", FailureType.UNSUPPORTED);
        long now = level.getGameTime();
        return switch (phase) {
            case PLACE -> {
                if (count(inventoryCounts(player), id(r.input)) <= 0)
                    yield failure("station_input_missing", "No " + id(r.input) + " is carried; acquire it before processing.", FailureType.NO_MATERIAL);
                // 手持原料右键顶面：原生逻辑先把台上旧物品与产物收回背包，再放上整叠原料。
                placeAttempted = true;
                yield startChild(new InteractAtTaskRecord(r.getToolCallId() + "-place", now + 60L * 20L,
                        MouseButton.RIGHT, r.station, 0, r.input, null, BuiltInRegistries.BLOCK.get(DEPOT)).withApproach(false));
            }
            case CONFIRM_PLACE -> {
                ItemStack held = heldItem(level, r.station);
                if (held != null && held.is(r.input)) {
                    placed = true; placedCount = held.getCount();
                    phase = Phase.WAIT; phaseStarted = now;
                    yield TaskState.RUNNING;
                }
                // 放上后可能立即被加工掉；只要背包里的原料确实少了，也算已放上。
                if (count(inventoryCounts(player), id(r.input)) < count(baseline, id(r.input))) {
                    placed = true; placedCount = count(baseline, id(r.input)) - count(inventoryCounts(player), id(r.input));
                    phase = Phase.WAIT; phaseStarted = now;
                    yield TaskState.RUNNING;
                }
                if (now - phaseStarted < SETTLE_TICKS) yield TaskState.RUNNING;
                yield failure("station_input_not_placed", "The depot does not hold the input after the right-click; nothing was processed.", FailureType.UNKNOWN);
            }
            case WAIT -> {
                ItemStack held = heldItem(level, r.station);
                boolean inputRemains = held == null || held.is(r.input);
                if (!inputRemains && processedAt < 0) processedAt = now;
                if (processedAt >= 0 && now - processedAt >= OUTPUT_SETTLE_TICKS) {
                    processingObserved = true;
                    recordHeld(held);
                    phase = Phase.COLLECT;
                } else if (processedAt < 0 && now - phaseStarted >= r.maxWaitTicks) {
                    // 等到上限仍是原料：照样收回，避免原料留在台上被遗忘，并如实报告没有加工。
                    processingTimedOut = true;
                    recordHeld(held);
                    phase = Phase.COLLECT;
                }
                yield TaskState.RUNNING;
            }
            case COLLECT -> {
                collectAttempted = true;
                InteractAtTaskRecord collect = new InteractAtTaskRecord(r.getToolCallId() + "-collect", now + 60L * 20L,
                        MouseButton.RIGHT, r.station, 0, null, null, BuiltInRegistries.BLOCK.get(DEPOT)).withApproach(false);
                collect.emptyHand = true;
                yield startChild(collect);
            }
            case CONFIRM_COLLECT -> {
                ItemStack held = heldItem(level, r.station);
                changes = inventoryChanges(baseline, inventoryCounts(player));
                if (held != null && held.isEmpty() && changes.entrySet().stream().anyMatch(entry -> entry.getValue() > 0)) {
                    collected = true;
                    yield TaskState.SUCCESS;
                }
                if (now - phaseStarted < SETTLE_TICKS) yield TaskState.RUNNING;
                yield failure("station_output_not_collected", "The depot still holds items or no item reached the inventory after the empty-hand right-click.", FailureType.UNKNOWN);
            }
        };
    }

    private TaskState startChild(InteractAtTaskRecord record) {
        childRecord = record;
        child = TaskFactory.create(player, record);
        return TaskState.RUNNING;
    }

    private TaskState tickChild(Level level) {
        TaskState state = level.getGameTime() >= childRecord.getDeadlineGameTime() ? TaskState.TIMEOUT : runChild(child);
        r.extendDeadlineTo(childRecord.getDeadlineGameTime() + r.maxWaitTicks + 40L * 20L);
        if (state == null) return TaskState.RUNNING;
        if (state != TaskState.SUCCESS) child.stop(player, Task.StopReason.REPLACED);
        TaskResult result = child.result(state);
        String receipt = state.name().toLowerCase(Locale.ROOT) + (result == null ? "" : ": " + result.message());
        child = null; childRecord = null;
        // 子任务结论只作为点击回执记录；放没放上、收没收到都以随后观察到的置物台与背包为准。
        if (phase == Phase.PLACE) { placeReceipt = receipt; phase = Phase.CONFIRM_PLACE; }
        else { collectReceipt = receipt; phase = Phase.CONFIRM_COLLECT; }
        phaseStarted = level.getGameTime();
        return TaskState.RUNNING;
    }

    private void recordHeld(ItemStack held) {
        if (held == null || held.isEmpty()) return;
        heldAfterProcessing = id(held.getItem());
        heldAfterProcessingCount = held.getCount();
    }

    private TaskState failure(String code, String message, FailureType type) {
        failureCode = code;
        if (baseline != null) changes = inventoryChanges(baseline, inventoryCounts(player));
        fail(message, type);
        return TaskState.FAILED;
    }

    @Override public String describeCurrentAction() {
        if (child != null) return child.describeCurrentAction();
        return switch (phase) {
            case PLACE, CONFIRM_PLACE -> "正在把原料放上置物台";
            case WAIT -> "正在等待置物台上的原料加工";
            case COLLECT, CONFIRM_COLLECT -> "正在收回置物台上的产物";
        };
    }

    @Override protected void cleanup() {
        super.cleanup();
        if (child != null) {
            child.stop(player, Task.StopReason.REPLACED);
            child.result(TaskState.CANCELLED);
            child = null;
        }
    }

    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "station_process");
        data.put("station", Map.of("x", r.station.getX(), "y", r.station.getY(), "z", r.station.getZ()));
        data.put("station_block", DEPOT.toString());
        data.put("input_item", id(r.input));
        data.put("place_attempted", placeAttempted);
        data.put("placed", placed);
        data.put("placed_count", placedCount);
        // 加工证据只来自置物台上原料被换成别的物品，不按配方表推断。
        data.put("processing_observed", processingObserved);
        data.put("processing_timed_out", processingTimedOut);
        if (!heldAfterProcessing.isEmpty())
            data.put("depot_held_before_collect", Map.of("item_id", heldAfterProcessing, "count", heldAfterProcessingCount));
        data.put("collect_attempted", collectAttempted);
        data.put("collected", collected);
        data.put("inventory_changes", changes);
        // 放料后没看到台上原料、又不是明确观察到“没放上”，或收取后没看到结果时，物品去向未知。
        data.put("outcome_uncertain", placeAttempted && !placed && !"station_input_not_placed".equals(failureCode)
                || collectAttempted && !collected);
        if (placeReceipt != null) data.put("place_click_receipt", placeReceipt);
        if (collectReceipt != null) data.put("collect_click_receipt", collectReceipt);
        if (failureCode != null) data.put("failure_code", failureCode);
        data.put("next_observation", processingObserved
                ? "inventory_changes lists what was collected; check the product id against the intended recipe"
                : "the input was not processed on this depot; inspect the machine (fluid, power, recipe) before retrying");
        return data;
    }

    @Override protected String successMessage() {
        return processingObserved
                ? "Input was processed on the depot and the depot contents were collected; see inventory_changes."
                : "The input was not processed within max_wait_seconds; it was collected back from the depot.";
    }

    // ---- 观察工具：只读客户端已同步的置物台与背包 ----

    static boolean isDepot(Block block) {
        return DEPOT.equals(BuiltInRegistries.BLOCK.getKey(block));
    }

    /** 读不到置物台物品时返回 null，交由调用方按“未知”处理，不当成空台。 */
    static ItemStack heldItem(Level level, BlockPos station) {
        var entity = level.getBlockEntity(station);
        if (entity == null) return null;
        try {
            Object held = NativeApi.call(entity, null, "getHeldItem");
            return held instanceof ItemStack stack ? stack.copy() : null;
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    static Map<String, Integer> inventoryCounts(LocalPlayer player) {
        Map<String, Integer> counts = new TreeMap<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) counts.merge(id(stack.getItem()), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    /** 只列出数量有变化的物品：正数是收进背包的，负数是放上置物台或被消耗的。 */
    static Map<String, Integer> inventoryChanges(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> changes = new TreeMap<>();
        for (String key : before.keySet()) changes.put(key, count(after, key) - count(before, key));
        for (String key : after.keySet()) changes.putIfAbsent(key, count(after, key) - count(before, key));
        changes.values().removeIf(delta -> delta == 0);
        return changes;
    }

    private static int count(Map<String, Integer> counts, String key) { return counts.getOrDefault(key, 0); }

    private static String id(Item item) { return BuiltInRegistries.ITEM.getKey(item).toString(); }
}
