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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.scan.LoadedBlockScan;
import org.maiwithu.maicraft.core.scan.ObservationVisibility;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.dimension.PortalLavaPoolSurvey;
import org.maiwithu.maicraft.core.task.locate.SemanticBlockSearchTaskRecord.Purpose;
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
    private PortalLavaPoolSurvey lavaPools;
    private final ProgressBudget scanBudget;
    /** 全部可见位置供连通池分析；默认回执只公开最近匹配位置及完整池面预算。 */
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
        // 查找岩浆时一并调查可见池面，不能把默认 count=1 命中的孤立源格当成足够浇筑的整池。
        // 只为腾桶找一个静源时直接交付源格；真正查施工池才需要完成整池几何和余量分析。
        if (r.blockTargets.contains(Blocks.LAVA) && (!r.sourceFluidsOnly || r.purpose == Purpose.PORTAL_CASTING))
            lavaPools = new PortalLavaPoolSurvey(scannedLevel, origin);
    }

    @Override
    protected TaskState onTick() {
        if (player.clientLevel != scannedLevel)
            return failSearch("find_block_world_changed", "the observed world changed before the scan completed", FailureType.INTERRUPTED);
        // 沿原游标检查当前高度附近到其他高度的已加载区块柱；直接眼位射线通过后才算观察到，隐藏候选不触发重新全扫。
        // 普通查块达到数量就可以收场，最近位置只代表已经检查过的命中；含岩浆时继续扫完整个范围供池面分析。
        scan.advance(SCAN_WORK_PER_TICK, SCAN_NANOS_PER_TICK, pos -> {
            observe(pos);
            return lavaPools == null && observed.size() >= r.count;
        });
        // 岩浆扫描沿原游标走完，再分刻分析连通池；不会借此走路、透视或加载新的区块。
        if (lavaPools != null && scan.complete()) lavaPools.advance(64, SCAN_NANOS_PER_TICK);
        // 单调的扫描游标直接接入公共预算；相同游标的轮询不能给父任务或本任务补时。
        scanBudget.observeCounter(player.level().getGameTime(), scan.processedCells()
                + (lavaPools == null ? 0 : lavaPools.processed()));
        if (lavaPools != null && !lavaPools.complete()) return TaskState.RUNNING;
        if (r.purpose == Purpose.PORTAL_CASTING) {
            // 合并远程坐标回执后，先把最近目标收敛到匹配池；找不到合适池时不返回孤立源坐标冒充结果。
            nearestMatchPos = lavaPools.nearestMatchingSource();
            nearestMatchDistance = nearestMatchPos == null ? -1 : Math.sqrt(nearestMatchPos.distSqr(origin));
            if (nearestMatchPos != null) {
                double dx = nearestMatchPos.getX() - origin.getX(), dz = nearestMatchPos.getZ() - origin.getZ();
                nearestHorizontalDistance = Math.sqrt(dx * dx + dz * dz);
                nearestVerticalOffset = nearestMatchPos.getY() - origin.getY();
            }
        }
        if (matchesRequested()) {
            return TaskState.SUCCESS;
        }
        if (scan.complete()) return exhausted();
        return TaskState.RUNNING;
    }

    private void observe(BlockPos pos) {
        // 候选位置先复核实际状态，再按视觉遮挡与外露部分判断能否看见；原生交互命中另行核对。
        BlockState state = player.clientLevel.getBlockState(pos);
        if (!r.blockTargets.contains(state.getBlock()) || r.excludedPositions.contains(pos)) {
            return;
        }
        // 取水前置必须找到真实静源，不能把看见流水当成已经找到可装桶的位置。
        if (r.sourceFluidsOnly && !state.getFluidState().isSource()) return;
        double dx = pos.getX() - origin.getX();
        double dz = pos.getZ() - origin.getZ();
        if (dx * dx + dz * dz > (double) r.maxDistance * r.maxDistance) {
            return;
        }
        // 已加载只说明客户端有地形数据；必须能从眼睛看见，才向模型报告找到。
        if (!ObservationVisibility.block(player, pos)) return;
        if (observed.putIfAbsent(pos.immutable(), state.getBlock()) == null) {
            if (lavaPools != null) lavaPools.observeVisible(pos, state);
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
        // 用户明确查找浇筑池时，发现岩浆但没有足量候选仍是未找到；所有小池事实继续保留给模型探索决策。
        if (r.purpose == Purpose.PORTAL_CASTING) return failSearch(
                matchedCount() == 0 ? "no_suitable_lava_pool_within_bound" : "insufficient_casting_pool_count",
                "observed " + matchedCount() + "/" + r.count + " lava pools with a casting start row and the required source reserve; "
                        + "only visible loaded terrain was checked, so hidden or unloaded pools remain unknown", FailureType.TARGET_LOST);
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

    private int matchedCount() {
        return r.purpose == Purpose.PORTAL_CASTING ? lavaPools == null ? 0 : lavaPools.matchingPools() : observed.size();
    }

    private boolean matchesRequested() {
        return matchedCount() >= r.count && (r.purpose != Purpose.PORTAL_CASTING || lavaPools != null && lavaPools.complete());
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
        // 用途筛选的 count 单位是池子，同时保留原始可见方块总数，不能把一池三十格说成找到三十个池子。
        data.put("search_purpose", r.purpose.id());
        if (r.sourceFluidsOnly) data.put("source_fluids_only", true);
        data.put("count_unit", r.purpose == Purpose.PORTAL_CASTING ? "casting_lava_pools" : "block_positions");
        data.put("observed_acceptable_count", matchedCount());
        data.put("observed_block_count", observed.size());
        data.put(r.purpose == Purpose.PORTAL_CASTING ? "observed_blocks_by_block_id" : "observed_acceptable_by_block_id", Map.copyOf(observedById));
        data.put("verified", matchesRequested());
        data.put("scope", "visible_loaded_client_blocks");
        data.put("visibility_required", true);
        data.put("max_distance", r.maxDistance);
        data.put("search_geometry", "horizontal_radius_across_loaded_sections");
        data.put("distance_metric", "euclidean_3d");
        data.put("distance_scope", r.purpose == Purpose.PORTAL_CASTING
                ? "nearest observed source in a matching casting pool; not a reachability result"
                : "nearest verified observed match; not a reachability result");
        data.putAll(progress());
        if (lavaPools != null) data.put("lava_pool_survey", lavaPools.facts());
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
        if (!matchesRequested()) {
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

    /** 扫描进度包含跳过区块段的体积；是否完整、实际读过多少状态和看见多少目标分开交付，不能把进度满格当成全世界无矿。 */
    @Override
    public Map<String, Object> progress() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", name()); data.put("phase", lavaPools != null && scan != null && scan.complete()
                ? "analyzing_visible_lava_pools" : "scanning_loaded_block_candidates");
        data.put("progress_unit", "section_cells_processed_including_unloaded_or_palette_skips");
        data.put("visible_matches", observed.size());
        data.put("scan_complete", scan != null && scan.complete());
        if (lavaPools != null) data.put("lava_pool_analysis_complete", lavaPools.complete());
        if (lavaPools != null) data.put("matching_casting_pools", lavaPools.matchingPools());
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
        if (r.purpose == Purpose.PORTAL_CASTING) return "verified " + matchedCount() + "/" + r.count
                + " lava pools with observed casting geometry and source reserve; native bucket access and casting remain unverified";
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
                + matchedCount() + "/" + r.count
                + (r.purpose == Purpose.PORTAL_CASTING ? " matching casting pools" : " matching positions")
                + "; partial evidence was not reported as success";
    }

    @Override
    protected String cancelledMessage() {
        return "block scan was interrupted; partial observations were not reported as success";
    }
}
