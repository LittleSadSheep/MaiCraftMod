// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.explore;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeight;

/**
 * 估计从当前位置到候选落点的直线路线的水域占比：沿线按固定步长采样已加载列，
 * 用 motionBlocking 表面判水，水域样本过半且至少两列才判穿水——短促过河不惩罚。
 * 列判定按世界坐标缓存，同一批候选共用。结论只表达"优先陆行"：全水域环境里
 * 所有候选都穿水时调用方仍应选出最优者继续推进，穿水本身不构成不可行。
 */
public final class WaterCrossingProbe {
    private static final int SAMPLE_STEP = 8;

    private final ClientLevel level;
    private final Long2BooleanOpenHashMap waterColumns = new Long2BooleanOpenHashMap();

    public WaterCrossingProbe(ClientLevel level) {
        this.level = level;
    }

    /** 直线路线水域样本过半且至少两列判穿水；未加载列不计入样本。 */
    public boolean crossesWater(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double distance = Math.hypot(dx, dz);
        if (distance < 1.0) return false;
        int steps = Math.max(1, (int) Math.ceil(distance / SAMPLE_STEP));
        int water = 0;
        int samples = 0;
        for (int i = 1; i <= steps; i++) {
            int x = (int) Math.round(from.getX() + dx * i / (double) steps);
            int z = (int) Math.round(from.getZ() + dz * i / (double) steps);
            if (!columnLoaded(x, z)) continue;
            samples++;
            if (isWaterColumn(x, z)) water++;
        }
        return water >= 2 && water * 2 >= samples;
    }

    private boolean isWaterColumn(int x, int z) {
        long key = BlockPos.asLong(x, 0, z);
        if (waterColumns.containsKey(key)) return waterColumns.get(key);
        int height = Math.clamp(ClientSurfaceHeight.motionBlockingNoLeaves(level, x, z),
                level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 1);
        boolean water = level.getBlockState(new BlockPos(x, height - 1, z))
                .getFluidState().is(FluidTags.WATER);
        waterColumns.put(key, water);
        return water;
    }

    private boolean columnLoaded(int x, int z) {
        return level.isLoaded(new BlockPos(x, level.getMinBuildHeight() + 1, z));
    }
}
