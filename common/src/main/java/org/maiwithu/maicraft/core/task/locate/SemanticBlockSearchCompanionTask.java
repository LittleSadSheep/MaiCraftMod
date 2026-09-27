// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.locate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.scan.TargetIndex;
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
    private String failureCode;
    /** 具体位置只在 Mod 内部保存；公开结果仅输出数量与距离统计。 */
    private final Map<BlockPos, Block> observed = new LinkedHashMap<>();

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
        // 同一查询键跨刻续进；want 取请求计数，攒够且比下一环可能最近值还近时才收工。
        TargetIndex.Result result = TargetIndex.query(
                level, origin, r.blockTargets, r.count, chunkRadius, SCAN_BUILD_BUDGET);
        absorb(result.hits());
        if (observed.size() >= r.count) {
            return TaskState.SUCCESS;
        }
        if (!result.complete()) {
            r.extendDeadlineTo(player.level().getGameTime() + PROGRESS_LEASE_TICKS);
            return TaskState.RUNNING;
        }
        return exhausted();
    }

    private void absorb(List<BlockPos> hits) {
        for (BlockPos pos : hits) {
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
            if (observed.putIfAbsent(pos.immutable(), state.getBlock()) == null) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (nearestMatchDistance < 0 || distance < nearestMatchDistance) {
                    nearestMatchDistance = distance;
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
                "the loaded-chunk scan within the bounded radius found no matching block; "
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
        data.put("scope", "loaded_client_blocks");
        data.put("max_distance", r.maxDistance);
        if (nearestMatchDistance >= 0) {
            data.put("nearest_match_distance",
                    Math.round(nearestMatchDistance * 10.0) / 10.0);
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
                        ? ", nearest about " + Math.round(nearestMatchDistance) + " blocks away"
                        : "")
                + " through loaded client chunks";
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
