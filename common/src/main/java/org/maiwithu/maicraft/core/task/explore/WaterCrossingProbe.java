// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.explore;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeight;

/**
 * 估计从当前位置到候选落点的直线路线的水域占比：沿线按固定步长采样已加载列，
 * 用 motionBlocking 表面判水并按列内深度区分浅水与深水。水域样本过半且至少
 * 两列判穿水；路线中连续出现深水（水深两格起，无法涉水只能游泳）也判穿水——
 * 窄河在长路线里占不了样本多数，只看多数会把角色带进河心。浅滩与短促渡河
 * 不惩罚。列判定按世界坐标缓存，同一批候选共用。结论只表达"优先陆行"：
 * 全水域环境里所有候选都穿水时调用方仍应选出最优者继续推进，穿水本身不构成
 * 不可行。
 */
public final class WaterCrossingProbe {
    private static final int SAMPLE_STEP = 8;
    /** 单列深水门槛：两格起无法涉水，只能游泳，长距离游泳有溺水风险。 */
    private static final int DEEP_WATER_MIN_DEPTH = 2;
    /** 深水连段门槛：连续两个采样段判深水（每段步长 {@link #SAMPLE_STEP} 格）即视为穿水路线。 */
    private static final int DEEP_RUN_SAMPLES = 2;

    private final ClientLevel level;
    private final Long2ObjectOpenHashMap<Column> columns = new Long2ObjectOpenHashMap<>();

    public WaterCrossingProbe(ClientLevel level) {
        this.level = level;
    }

    /** 直线路线水域样本过半且至少两列，或出现连续深水段，即判穿水；未加载列不计入样本。 */
    public boolean crossesWater(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double distance = Math.hypot(dx, dz);
        if (distance < 1.0) return false;
        int steps = Math.max(1, (int) Math.ceil(distance / SAMPLE_STEP));
        int water = 0;
        int samples = 0;
        int deepRun = 0;
        int longestDeepRun = 0;
        for (int i = 1; i <= steps; i++) {
            int x = (int) Math.round(from.getX() + dx * i / (double) steps);
            int z = (int) Math.round(from.getZ() + dz * i / (double) steps);
            if (!columnLoaded(x, z)) {
                // 未加载列不可知，不能当作浅水接续深水连段。
                deepRun = 0;
                continue;
            }
            samples++;
            Column column = column(x, z);
            if (column.water()) water++;
            deepRun = column.deep() ? deepRun + 1 : 0;
            longestDeepRun = Math.max(longestDeepRun, deepRun);
        }
        return isCrossing(samples, water, longestDeepRun);
    }

    /** 纯判定供回归直测：多数水样规则或深水连段规则任一成立即穿水。 */
    static boolean isCrossing(int samples, int waterSamples, int longestDeepRun) {
        return waterSamples >= 2 && waterSamples * 2 >= samples
                || longestDeepRun >= DEEP_RUN_SAMPLES;
    }

    private Column column(int x, int z) {
        long key = BlockPos.asLong(x, 0, z);
        Column cached = columns.get(key);
        if (cached != null) return cached;
        int height = Math.clamp(ClientSurfaceHeight.motionBlockingNoLeaves(level, x, z),
                level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 1);
        BlockPos top = new BlockPos(x, height - 1, z);
        SemanticExploreCompanionTask.FluidColumn fluid =
                SemanticExploreCompanionTask.fluidColumn(
                        level.getBlockState(top).getFluidState(),
                        level, x, z, top, level.getMinBuildHeight());
        boolean water = fluid != null
                && fluid.kind() == SemanticExploreCompanionTask.SurfaceKind.WATER;
        Column result = new Column(water, water && fluid.depth() >= DEEP_WATER_MIN_DEPTH);
        columns.put(key, result);
        return result;
    }

    private record Column(boolean water, boolean deep) {}

    private boolean columnLoaded(int x, int z) {
        return level.isLoaded(new BlockPos(x, level.getMinBuildHeight() + 1, z));
    }
}
