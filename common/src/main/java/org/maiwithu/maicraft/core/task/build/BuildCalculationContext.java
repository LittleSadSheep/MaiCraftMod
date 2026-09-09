package org.maiwithu.maicraft.core.task.build;
import org.maiwithu.maicraft.core.build.BuildValidity;

import org.maiwithu.maicraft.core.pathing.moves.CalculationContext;
import org.maiwithu.maicraft.core.pathing.moves.ChunkLoadedTest;
import org.maiwithu.maicraft.core.pathing.moves.MovementHelper;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.pathing.settings.NavSettings;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Set;

import static org.maiwithu.maicraft.core.pathing.moves.ActionCosts.COST_INF;

/** 保留的施工寻路成本实现；当前 Baritone 主导航没有调用创建它的 ContextProvider.forSearch／forExecution。 */
final class BuildCalculationContext extends CalculationContext {

    private final Map<Long, BuildTaskRecord.Target> activeTargets;
    private final Set<BlockState> availableStates;
    private final boolean replaceExisting;
    BuildCalculationContext(LocalPlayer player, BlockGetter view, ChunkLoadedTest loadedTest,
                            boolean safeForThreadedUse, LongSet sacred, LongSet deniedPlace,
                            LongSet forbiddenBodyCells,
                            TerrainPermit permit,
                            Map<Long, BuildTaskRecord.Target> activeTargets,
                            Set<BlockState> availableStates, boolean replaceExisting) {
        super(player, view, loadedTest, safeForThreadedUse, sacred, deniedPlace,
                forbiddenBodyCells, permit);
        this.activeTargets = Map.copyOf(activeTargets);
        this.availableStates = Set.copyOf(availableStates);
        this.replaceExisting = replaceExisting;
        this.jumpPenalty += 10.0;
        this.backtrackCostFavoringCoefficient = 1.0;
    }

    @Override
    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        // 如果使用这套成本：正确目标材料可顺路低成本放下，临时放错材料则加价，保护或越界位置禁止放。
        long key = BlockPos.asLong(x, y, z);
        BuildTaskRecord.Target target = activeTargets.get(key);
        if (target != null) {
            if (sacred.contains(key) || deniedPlace.contains(key)) {
                return COST_INF;
            }
            if (!MovementHelper.placeableWithinBorder(worldBorder, x, z)) {
                return COST_INF;
            }
            if (target.block() instanceof net.minecraft.world.level.block.AirBlock) {
                if (!hasThrowaway || !current.isAir()) return COST_INF;
                // 目标应为空气却被问能否在此放置(脚手架):恒计"放错块"有限成本,迟早还要挖掉。
                return placeBlockCost * NavSettings.get().placeIncorrectBlockPenaltyMultiplier;
            }
            if (target.matches(current)) {
                return COST_INF;
            }
            if (containsAvailableState(target.desiredState())
                    && MovementHelper.isReplaceable(x, y, z, current, loadedTest)) {
                return 0.0;
            }
            return hasThrowaway
                    ? placeBlockCost * 1.5 * NavSettings.get().placeIncorrectBlockPenaltyMultiplier
                    : COST_INF;
        }
        return super.costOfPlacingAt(x, y, z, current);
    }

    private boolean containsAvailableState(BlockState desired) {
        for (BlockState state : availableStates) {
            if (BuildValidity.sameBlockState(state, desired)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        // 如果使用这套成本：先检查保护与破坏许可，再区分成品格、未完成目标格和普通地形。
        long key = BlockPos.asLong(x, y, z);
        if (sacred.contains(key)) {
            return COST_INF;
        }
        if (BlockHelper.shouldAvoidBreaking(view, new BlockPos(x, y, z))) {
            return COST_INF;
        }
        if (!allowBreak && !allowBreakAnyway.contains(current.getBlock())) {
            return COST_INF;
        }
        BuildTaskRecord.Target target = activeTargets.get(key);
        if (target != null) {
            if (target.matches(current)) {
                // 成品的拆除成本设为普通情况的八倍，表达“优先绕路，实在无路再考虑拆开”；不等于绝对保护。
                return 8.0;
            }
            return replaceExisting ? 1.0 : COST_INF;
        }
        return super.breakCostMultiplierAt(x, y, z, current);
    }
}
