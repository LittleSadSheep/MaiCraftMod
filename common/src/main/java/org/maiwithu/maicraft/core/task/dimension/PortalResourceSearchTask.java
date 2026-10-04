// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.explore.SemanticExploreTaskRecord;
import org.maiwithu.maicraft.core.task.locate.SemanticBlockSearchTaskRecord;
import org.maiwithu.maicraft.core.task.locate.SemanticBlockSearchTaskRecord.Purpose;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 看附近 -> 没有则跑图 -> 在新的视点继续看；发现所需资源就结束跑图，交回原来的备门流程。 */
final class PortalResourceSearchTask extends AbstractCompanionTask<PortalResourceSearchTaskRecord> {
    private final BiFunction<LocalPlayer, TaskRecord, Task> factory;
    private final List<Map<String, Object>> lookups = new ArrayList<>();
    private Task lookup, exploration;
    private TaskRecord lookupRecord, explorationRecord;
    private BlockPos lastView;
    private Map<String, Object> explorationReceipt = Map.of();
    private int serial;
    private boolean explored, explorationStarted, explorationEnded, cleaned;

    PortalResourceSearchTask(LocalPlayer player, PortalResourceSearchTaskRecord record) { this(player, record, TaskFactory::create); }
    PortalResourceSearchTask(LocalPlayer player, PortalResourceSearchTaskRecord record,
                             BiFunction<LocalPlayer, TaskRecord, Task> factory) {
        super(player, record); this.factory = factory;
    }
    @Override protected void onStart() { beginLookup(); }

    private void beginLookup() {
        lastView = player.blockPosition().immutable();
        boolean water = r.resource == PortalResourceSearchTaskRecord.Resource.WATER;
        boolean castingPool = r.resource == PortalResourceSearchTaskRecord.Resource.LAVA_POOL;
        var record = new SemanticBlockSearchTaskRecord(id(), deadline(), List.of(water ? Blocks.WATER : Blocks.LAVA),
                1, r.loadedRadius, castingPool ? Purpose.PORTAL_CASTING : Purpose.BLOCKS);
        if (!castingPool) record.sourceFluidsOnly();
        record.excluding(r.excluded);
        lookupRecord = record; lookup = factory.apply(player, record);
    }

    @Override protected TaskState onTick() {
        // 探索至少启动过一次后才允许因找到目标结束它，避免对未初始化的移动任务伪造取消回执。
        if (lookup != null) {
            TaskState terminal = runChild(lookup); r.extendDeadlineTo(lookupRecord.getDeadlineGameTime());
            if (terminal != null) {
                TaskResult result = lookup.result(terminal);
                if (result == null) { fail("resource lookup ended without a receipt", FailureType.UNKNOWN); return TaskState.FAILED; }
                lookups.add(Map.of("origin", NetherPortalCastingLayout.position(lastView), "success", result.success(), "data", result.data()));
                lookup = null; lookupRecord = null;
                if (terminal == TaskState.SUCCESS && result.success() && retainPosition(result)) {
                    closeExploration("resource_observed"); return TaskState.SUCCESS;
                }
                if (!explored) {
                    if (r.explorationDistance <= 0) return exhausted();
                    // 沿用有界地面探索，不读取种子、不强制加载区块；已有材料与点火授权不会因此扩大。
                    explorationRecord = new SemanticExploreTaskRecord(id(), deadline(), "survey", r.explorationDistance, r.mayAlterTerrain);
                    exploration = factory.apply(player, explorationRecord); explored = true;
                } else if (explorationEnded) return exhausted();
            }
        }
        if (exploration != null && !explorationEnded) {
            explorationStarted = true;
            TaskState terminal = runChild(exploration); r.extendDeadlineTo(explorationRecord.getDeadlineGameTime());
            if (terminal != null) {
                var result = exploration.result(terminal);
                explorationReceipt = receipt(result, terminal.name().toLowerCase());
                explorationEnded = true; exploration = null;
                // 最后一小段移动也可能露出水面；只有眼位真的变了才补这一次末端观察。
                if (lookup == null && !lastView.equals(player.blockPosition())) beginLookup();
                if (lookup == null) return exhausted();
            } else if (lookup == null && lastView.distSqr(player.blockPosition()) >= 64) beginLookup();
        }
        return TaskState.RUNNING;
    }

    private boolean retainPosition(TaskResult result) {
        if (!(result.data().get("nearest_match_position") instanceof Map<?, ?> position)
                || !(position.get("x") instanceof Number x) || !(position.get("y") instanceof Number y)
                || !(position.get("z") instanceof Number z)) return false;
        // 复用正式查找回执里的已观察坐标；后续真正取桶仍会复核源格和身体站位。
        r.observedPosition = new BlockPos(x.intValue(), y.intValue(), z.intValue());
        return true;
    }

    private TaskState exhausted() {
        fail("No " + r.resource.name().toLowerCase() + " observed after the bounded preparation search; unseen terrain remains unknown.", FailureType.TARGET_LOST);
        return TaskState.FAILED;
    }
    private long deadline() { return Math.max(r.getDeadlineGameTime(), player.level().getGameTime() + 6000); }
    private String id() { return r.getToolCallId() + "-resource-" + (++serial); }
    private Map<String, Object> receipt(TaskResult result, String reason) {
        return Map.of("reason", reason, "success", result.success(), "message", result.message(), "data", result.data());
    }
    private void closeExploration(String reason) {
        if (exploration == null || !explorationStarted) return;
        exploration.stop(player, StopReason.REPLACED);
        explorationReceipt = receipt(exploration.result(TaskState.CANCELLED), reason);
        exploration = null; explorationEnded = true;
    }
    @Override protected Map<String, Object> resultData() {
        var data = new LinkedHashMap<String, Object>();
        data.put("resource", r.resource.name().toLowerCase()); data.put("loaded_radius", r.loadedRadius);
        data.put("exploration_distance", r.explorationDistance); data.put("lookups", List.copyOf(lookups));
        data.put("exploration_attempted", explored); data.put("exploration_receipt", explorationReceipt);
        if (r.observedPosition != null) data.put("observed_position", NetherPortalCastingLayout.position(r.observedPosition));
        return data;
    }
    @Override public Map<String, Object> progress() {
        var data = new LinkedHashMap<>(resultData());
        data.put("phase", explored ? "exploring_for_resource" : "locating_visible_resource");
        if (lookup != null) data.put("lookup", lookup.progress());
        if (exploration != null) data.put("exploration", exploration.progress());
        return data;
    }
    @Override public String describeCurrentAction() { return r.describe() + (explored ? "：正在跑图并检查新视点" : "：检查已加载可见范围"); }
    @Override public void stop(LocalPlayer player, StopReason why) {
        if (lookup != null) lookup.stop(player, why);
        if (exploration != null) exploration.stop(player, why);
        super.stop(player, why);
    }
    @Override protected void cleanup() {
        if (cleaned) return; cleaned = true;
        if (lookup != null) { lookup.stop(player, StopReason.REPLACED); lookup.result(TaskState.CANCELLED); lookup = null; }
        closeExploration("preparation_search_ended"); super.cleanup();
    }
    @Override protected String successMessage() { return "Requested preparation resource observed; collection and construction have not been claimed."; }
}
