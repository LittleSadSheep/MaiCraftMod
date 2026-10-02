// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.locate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.scan.ObservationVisibility;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 只读扫描已加载区块中的目标方块；不移动身体也不改变世界。
 * 索引查询跨刻续进；卸载的区块被跳过，因此"查过没有"不等于"世界里没有"。
 */
public final class SemanticBlockSearchCompanionTask extends AbstractCompanionTask<SemanticBlockSearchTaskRecord> {
    /** 单次查询的索引构建预算，与交互定位共用同一量级。 */
    private static final int SCAN_BUILD_BUDGET = 384;
    /** 扫描仍在推进时按此时间片为任务续期。 */
    private static final int PROGRESS_LEASE_TICKS = 200;

    private BlockPos origin;
    private double nearestMatchDistance = -1;
    private double nearestHorizontalDistance;
    private int nearestVerticalOffset;
    private String failureCode;
    /** 具体位置只在 Mod 内部保存；公开结果仅输出数量与距离统计。 */
    private final Map<BlockPos, Block> observed = new LinkedHashMap<>();
    // 一批查完再排除已看过的位置，近处墙后的同类方块不能占满窗口、遮住远处可见目标。
    private final Set<BlockPos> excluded = new HashSet<>();
    private final Set<BlockPos> inspected = new HashSet<>();

    public SemanticBlockSearchCompanionTask(LocalPlayer player, SemanticBlockSearchTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        origin = player.blockPosition().immutable();
        TargetIndex.register(player.clientLevel, r.blockTargets);
    }

    @Override
    protected TaskState onTick() {
        ClientLevel level = player.clientLevel;
        int chunkRadius = Math.max(1, (r.maxDistance + 15) / 16);
        // 索引仅提供候选；逐批核对视线，隐藏目标不会构成发现证据。
        int batch = Math.max(64, r.count);
        TargetIndex.Result result = TargetIndex.query(
                level, origin, r.blockTargets, batch, chunkRadius, SCAN_BUILD_BUDGET, excluded);
        absorb(result.hits());
        if (observed.size() >= r.count) {
            return TaskState.SUCCESS;
        }
        if (!result.complete()) {
            r.extendDeadlineTo(player.level().getGameTime() + PROGRESS_LEASE_TICKS);
            return TaskState.RUNNING;
        }
        if (result.hits().size() < batch) return exhausted();
        excluded.addAll(result.hits());
        r.extendDeadlineTo(player.level().getGameTime() + PROGRESS_LEASE_TICKS);
        return TaskState.RUNNING;
    }

    private void absorb(List<BlockPos> hits) {
        for (BlockPos pos : hits) {
            if (!inspected.add(pos.immutable())) continue;
            // 索引条目可能滞后于真实世界（客户端预测），使用位置前先核对实际状态。
            BlockState state = player.clientLevel.getBlockState(pos);
            if (!r.blockTargets.contains(state.getBlock())) {
                continue;
            }
            double dx = pos.getX() - origin.getX();
            double dz = pos.getZ() - origin.getZ();
            if (dx * dx + dz * dz > (double) r.maxDistance * r.maxDistance) {
                continue;
            }
            // 已加载只说明客户端有地形数据；必须能从眼睛看见，才向模型报告找到。
            if (!ObservationVisibility.block(player, pos)) continue;
            if (observed.putIfAbsent(pos.immutable(), state.getBlock()) == null) {
                // 搜索仍覆盖水平区块柱；报告最近距离时必须算高度，不能把深处的矿说成脚边几格。
                double distance = Math.sqrt(pos.distSqr(origin));
                if (nearestMatchDistance < 0 || distance < nearestMatchDistance) {
                    nearestMatchDistance = distance;
                    nearestHorizontalDistance = Math.sqrt(dx * dx + dz * dz);
                    nearestVerticalOffset = pos.getY() - origin.getY();
                }
            }
        }
    }

    private TaskState exhausted() {
        if (!observed.isEmpty()) {
            return failSearch(
                    "insufficient_block_count",
                    "the loaded-chunk scan observed only " + observed.size() + "/"
                            + r.count + " matching positions",
                    FailureType.TARGET_LOST);
        }
        return failSearch(
                "no_block_evidence_within_bound",
                "the line-of-sight scan within the bounded loaded radius found no visible matching block; "
                        + "this is not evidence that none exists outside loaded terrain",
                FailureType.TARGET_LOST);
    }

    private TaskState failSearch(String code, String message, FailureType type) {
        failureCode = code;
        fail(message, type);
        return TaskState.FAILED;
    }

    @Override
    protected void cleanup() {
        TargetIndex.unregister(player.clientLevel, r.blockTargets);
        super.cleanup();
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
        if (nearestMatchDistance >= 0) {
            data.put("nearest_match_distance",
                    Math.round(nearestMatchDistance * 10.0) / 10.0);
            data.put("nearest_match_horizontal_distance", Math.round(nearestHorizontalDistance * 10.0) / 10.0);
            data.put("nearest_match_vertical_offset", nearestVerticalOffset);
        }
        if (observed.size() < r.count) {
            String code = failureCode == null ? "find_block_timeout" : failureCode;
            data.put("failure_code", code);
            data.put("recoverable", true);
            data.put("requires_narration", true);
            data.put("suggestions", List.of(
                    "travel or explore to load the likely area, then retry",
                    "stop without treating the partial observed count as success"));
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
