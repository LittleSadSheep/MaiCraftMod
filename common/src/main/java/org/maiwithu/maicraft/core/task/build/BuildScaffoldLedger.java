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

/** Confirmed temporary effects retained across material batches until the authored air is restored. */
final class BuildScaffoldLedger {
    private final Map<BlockPos, BlockState> placed = new LinkedHashMap<>();

    void confirmed(BlockPos pos, BlockState state) {
        if (!state.isAir() && state.getFluidState().isEmpty()) placed.put(pos.immutable(), state);
    }
    void cleared(BlockPos pos) { placed.remove(pos); }
    boolean contains(BlockPos pos) { return placed.containsKey(pos); }
    boolean owns(BlockPos pos, BlockState current) { return current.equals(placed.get(pos)); }
    Map<BlockPos, BlockState> snapshot() { return Map.copyOf(placed); }

    /** Only explicit plan air can gain this exception; inherited protection always wins. */
    boolean permits(BuildTaskRecord.Target target, BlockState current, boolean protectedByOwner) {
        return !protectedByOwner && target != null && target.desiredState().isAir()
                && current.getFluidState().isEmpty() && (current.isAir() || owns(target.pos(), current));
    }

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
