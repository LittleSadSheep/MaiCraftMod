// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 记录本任务确认放下的临时垫脚块，换一批材料时继续保留，之后可据此清理。
 * 记录的是坐标和完整方块状态；当前状态相同才视为仍属于本任务，无法识别别人换上了完全相同的方块。
 */
final class BuildScaffoldLedger {
    private final Map<BlockPos, BlockState> placed = new LinkedHashMap<>();

    // 空气和含流体的状态不登记为可清理的临时支撑。
    void confirmed(BlockPos pos, BlockState state) {
        if (!state.isAir() && state.getFluidState().isEmpty()) placed.put(pos.immutable(), state);
    }
    void cleared(BlockPos pos) { placed.remove(pos); }
    boolean contains(BlockPos pos) { return placed.containsKey(pos); }
    boolean isEmpty() { return placed.isEmpty(); }
    boolean owns(BlockPos pos, BlockState current) { return current.equals(placed.get(pos)); }
    Map<BlockPos, BlockState> snapshot() { return Map.copyOf(placed); }

    /**
     * 只有主人没有保护、最终计划为空或没有目标、现场没有流体，并且当前为空或仍是已登记支撑时，才允许临时借用。
     */
    boolean permits(BuildTaskRecord.Target target, BlockState current, boolean protectedByOwner) {
        return !protectedByOwner && (target == null || target.desiredState().isAir())
                && current.getFluidState().isEmpty()
                && (current.isAir() || target != null && owns(target.pos(), current));
    }

    // 先合并全部保护格。完成预检后，只对允许借用的计划空气格解除基础保护；继承的保护和额外保护仍然优先。
    LongSet navigationProtection(LongSet base, LongSet inherited, LongSet additional,
            LongSet airCells, Map<Long, BuildTaskRecord.Target> targets,
            BlockGetter world, Predicate<BlockPos> loaded, boolean preflightComplete) {
        LongOpenHashSet result = new LongOpenHashSet(base);
        result.addAll(inherited);
        if (additional != null) result.addAll(additional);
        if (!preflightComplete) return result;
        for (long key : airCells) {
            BlockPos pos = BlockPos.of(key);
            if (!loaded.test(pos)) continue;
            boolean hard = inherited.contains(key) || additional != null && additional.contains(key);
            if (permits(targets.get(key), world.getBlockState(pos), hard)) result.remove(key);
        }
        return result;
    }
}
