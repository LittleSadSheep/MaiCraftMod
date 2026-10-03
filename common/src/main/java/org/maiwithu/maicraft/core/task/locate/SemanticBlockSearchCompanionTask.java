// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.locate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.scan.LoadedBlockScan;
import org.maiwithu.maicraft.core.scan.ObservationVisibility;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.task.ProgressBudget;

/**
 * 只读扫描已加载区块中的目标方块；不移动身体也不改变世界。
 * 段内游标跨刻续进；卸载的区块被跳过，因此"查过没有"不等于"世界里没有"。
 */
public final class SemanticBlockSearchCompanionTask extends AbstractCompanionTask<SemanticBlockSearchTaskRecord> {
    /** 视线核查也纳入每刻时间片；密集石层分刻查完，不用固定候选数量截掉未检查的事实。 */
    private static final int SCAN_WORK_PER_TICK = 4096;
    private static final long SCAN_NANOS_PER_TICK = 2_000_000L;
    /** 扫描仍在推进时按此时间片为任务续期。 */
    private static final int PROGRESS_LEASE_TICKS = 200;
    /**
     * 命中后的指路话术：点名直接吃坐标的能力。能力名是公开指针，
     * AbilityPointerDriftTest 核对其仍在能力表里，改名漏改这里会立刻失败。
     */
    public static final String NEXT_STEP_POINTER = "Pass nearest_match_position to travel as destination, to harvest_block"
            + " (may_alter_terrain=true with matching block_id and expected_output_item_id; the live block"
            + " must still be a loaded solid resource without a block entity or fluid), or to interact"
            + " to use it in place.";

    private BlockPos origin;
    private BlockPos nearestMatchPos;
    private double nearestMatchDistance = -1;
    private double nearestHorizontalDistance;
    private int nearestVerticalOffset;
    private String failureCode;
    private ClientLevel scannedLevel;
    private LoadedBlockScan scan;
    private final ProgressBudget scanBudget;
    /** 具体位置只在 Mod 内部保存；公开结果仅输出数量与距离统计。 */
    private final Map<BlockPos, Block> observed = new LinkedHashMap<>();

    public SemanticBlockSearchCompanionTask(LocalPlayer player, SemanticBlockSearchTaskRecord record) {
        super(player, record);
        scanBudget = record.progressBudget(PROGRESS_LEASE_TICKS);
    }

    @Override
    protected void onStart() {
        origin = player.blockPosition().immutable();
        scannedLevel = player.clientLevel;
        scan = new LoadedBlockScan(scannedLevel, origin, r.blockTargets, r.maxDistance);
    }

    @Override
    protected TaskState onTick() {
        if (player.clientLevel != scannedLevel)
            return failSearch("find_block_world_changed", "the observed world changed before the scan completed", FailureType.INTERRUPTED);
        // 一次只沿同一个游标推进；隐藏目标继续逐个按原生视线判断，不再换排除集合重扫全范围。
        scan.advance(SCAN_WORK_PER_TICK, SCAN_NANOS_PER_TICK, pos -> {
            observe(pos);
            return observed.size() >= r.count;
        });
        // 单调的扫描游标直接接入公共预算；相同游标的轮询不能给父任务或本任务补时。
        scanBudget.observeCounter(player.level().getGameTime(), scan.processedCells());
        if (observed.size() >= r.count) {
            return TaskState.SUCCESS;
        }
        if (scan.complete()) return exhausted();
        return TaskState.RUNNING;
    }

    private void observe(BlockPos pos) {
        // 候选位置使用前复核实际状态；视线仍由现有原生碰撞射线判断。
        BlockState state = player.clientLevel.getBlockState(pos);
        if (!r.blockTargets.contains(state.getBlock())) {
            return;
        }
        double dx = pos.getX() - origin.getX();
        double dz = pos.getZ() - origin.getZ();
        if (dx * dx + dz * dz > (double) r.maxDistance * r.maxDistance) {
            return;
        }
        // 已加载只说明客户端有地形数据；必须能从眼睛看见，才向模型报告找到。
        if (!ObservationVisibility.block(player, pos)) return;
        if (observed.putIfAbsent(pos.immutable(), state.getBlock()) == null) {
            // 搜索仍覆盖水平区块柱；报告最近距离时必须算高度，不能把深处的矿说成脚边几格。
            double distance = Math.sqrt(pos.distSqr(origin));
            if (nearestMatchDistance < 0 || distance < nearestMatchDistance) {
                nearestMatchDistance = distance;
                nearestMatchPos = pos.immutable();
                nearestHorizontalDistance = Math.sqrt(dx * dx + dz * dz);
                nearestVerticalOffset = pos.getY() - origin.getY();
            }
        }
    }

    private TaskState exhausted() {
        if (!observed.isEmpty()) {
            return failSearch(
                    "insufficient_block_count",
                    "the visible loaded-block scan observed only " + observed.size() + "/"
                            + r.count + " matching positions",
                    FailureType.TARGET_LOST);
        }
        return failSearch(
                "no_block_evidence_within_bound",
                "the line-of-sight scan within the bounded loaded radius found no visible matching block; "
                        + "this is not evidence that none exists in hidden or unloaded terrain, or beyond the bound",
                FailureType.TARGET_LOST);
    }

    private TaskState failSearch(String code, String message, FailureType type) {
        failureCode = code;
        fail(message, type);
        return TaskState.FAILED;
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Integer> observedById = new LinkedHashMap<>();
        for (Block block : observed.values()) {
            observedById.merge(BuiltInRegistries.BLOCK.getKey(block).toString(), 1, Integer::sum);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("block_ids", r.blockTargets.stream()
                .map(BuiltInRegistries.BLOCK::getKey)
                .map(ResourceLocation::toString).toList());
        data.put("requested_count", r.count);
        data.put("observed_acceptable_count", observed.size());
        data.put("observed_acceptable_by_block_id", Map.copyOf(observedById));
        data.put("verified", observed.size() >= r.count);
        data.put("scope", "visible_loaded_client_blocks");
        data.put("visibility_required", true);
        data.put("max_distance", r.maxDistance);
        data.put("search_geometry", "horizontal_radius_across_loaded_sections");
        data.put("distance_metric", "euclidean_3d");
        data.put("distance_scope", "nearest verified observed match; not a reachability result");
        data.putAll(progress());
        if (nearestMatchDistance >= 0) {
            data.put("nearest_match_distance",
                    Math.round(nearestMatchDistance * 10.0) / 10.0);
            data.put("nearest_match_horizontal_distance", Math.round(nearestHorizontalDistance * 10.0) / 10.0);
            data.put("nearest_match_vertical_offset", nearestVerticalOffset);
            // 最近命中是过了视线闸的公平可知事实，坐标可以进回执供调用方直接消费。
            data.put("nearest_match_position", Map.of(
                    "x", nearestMatchPos.getX(), "y", nearestMatchPos.getY(), "z", nearestMatchPos.getZ()));
            data.put("next_step", NEXT_STEP_POINTER);
        }
        if (observed.size() < r.count) {
            if (failureCode != null) data.put("failure_code", failureCode);
            data.put("recoverable", true);
            data.put("requires_narration", true);
            data.put("suggestions", List.of(
                    "travel or explore to load the likely area, then retry",
                    "stop without treating the partial observed count as success"));
        }
        return data;
    }

    @Override
    protected Map<String, Object> resultData(TaskState terminal) {
        Map<String, Object> data = new LinkedHashMap<>(resultData());
        // 用户叫停不能冒充自然超时，更不能将尚未检查完的石层写成无目标结论。
        data.put("scan_outcome", terminal.name().toLowerCase(Locale.ROOT));
        if (terminal == TaskState.CANCELLED || terminal == TaskState.TIMEOUT)
            data.put("failure_code", terminal == TaskState.CANCELLED ? "find_block_cancelled" : "find_block_timeout");
        else if (terminal == TaskState.FAILED) data.putIfAbsent("failure_code", "find_block_failed");
        if (terminal == TaskState.CANCELLED)
            data.put("suggestions", List.of("retain partial observations; cancellation does not establish that the remaining scope has no target"));
        return data;
    }

    /** done/total 表示扫描游标处理的体积；未加载与调色板跳过另列，不能当成逐格观察数量。 */
    @Override
    public Map<String, Object> progress() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", name()); data.put("phase", "scanning_loaded_block_candidates");
        data.put("progress_unit", "section_cells_processed_including_unloaded_or_palette_skips");
        data.put("visible_matches", observed.size());
        data.put("scan_complete", scan != null && scan.complete());
        if (scan != null) {
            data.put("done", scan.processedCells()); data.put("total", scan.totalCells());
            data.put("examined_block_states", scan.examinedStates()); data.put("visibility_candidates_checked", scan.candidates());
            data.put("finished_sections", scan.finishedSections()); data.put("total_sections", scan.totalSections());
            data.put("unloaded_sections", scan.unloadedSections()); data.put("palette_empty_sections", scan.emptySections());
        }
        return data;
    }

    @Override
    protected String successMessage() {
        return "verified " + observed.size() + "/" + r.count + " matching block positions"
                + (nearestMatchDistance >= 0
                        ? ", nearest observed about " + Math.round(nearestMatchDistance) + " blocks away in 3D"
                                + " (horizontal " + Math.round(nearestHorizontalDistance) + ", height offset " + nearestVerticalOffset + ")"
                        : "")
                + " through direct line of sight in loaded client chunks";
    }

    @Override
    protected String timeoutMessage() {
        return "block scan stopped before completing the loaded-radius sweep with "
                + observed.size() + "/" + r.count
                + " matching positions; partial evidence was not reported as success";
    }

    @Override
    protected String cancelledMessage() {
        return "block scan was interrupted; partial observations were not reported as success";
    }
}
