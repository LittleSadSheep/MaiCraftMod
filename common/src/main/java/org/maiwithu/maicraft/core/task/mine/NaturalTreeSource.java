package org.maiwithu.maicraft.core.task.mine;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Task-local evidence for intact natural trunks, retained while this task harvests them. */
final class NaturalTreeSource {
    private final Set<BlockPos> verified = new HashSet<>();
    final Set<BlockPos> rejected = new HashSet<>();
    private final Map<BlockPos, BlockPos> awaitingChunk = new HashMap<>();
    private int remaining;
    boolean budgetDeferred;
    boolean unloadedEvidence;

    void beginQuery() { remaining = 8; budgetDeferred = false; unloadedEvidence = false; }

    boolean accepts(BlockPos seed, BlockGetter world, Predicate<BlockPos> loaded) {
        if (verified.contains(seed)) return true;
        if (rejected.contains(seed)) return false;
        BlockPos missing = awaitingChunk.get(seed);
        if (missing != null && !loaded.test(missing)) { unloadedEvidence = true; return false; }
        if (remaining-- <= 0) { budgetDeferred = true; return false; }
        Set<BlockPos> trunk = new HashSet<>();
        BlockPos[] incomplete = {null};
        Predicate<BlockPos> available = pos -> {
            if (loaded.test(pos)) return true;
            incomplete[0] = pos.immutable();
            return false;
        };
        boolean natural = inspect(seed, world, available, trunk);
        if (incomplete[0] != null) {
            unloadedEvidence = true;
            awaitingChunk.put(seed.immutable(), incomplete[0]);
            for (BlockPos log : trunk) awaitingChunk.put(log, incomplete[0]);
            return false;
        }
        awaitingChunk.remove(seed);
        for (BlockPos log : trunk) awaitingChunk.remove(log);
        (natural ? verified : rejected).addAll(trunk);
        (natural ? verified : rejected).add(seed.immutable());
        return natural;
    }

    private static boolean inspect(BlockPos seed, BlockGetter world, Predicate<BlockPos> loaded, Set<BlockPos> trunk) {
        if (!loaded.test(seed)) return false;
        BlockState first = world.getBlockState(seed);
        if (!upright(first, first.getBlock())) return false;
        BlockPos bottom = seed;
        for (int n = 0; n < 32; n++) {
            if (!loaded.test(bottom.below())) return false;
            if (!upright(world.getBlockState(bottom.below()), first.getBlock())) break;
            bottom = bottom.below();
            if (n == 31) return false;
        }
        for (int n = 0; n < 32; n++) {
            BlockPos log = bottom.above(n);
            if (!loaded.test(log)) return false;
            if (!upright(world.getBlockState(log), first.getBlock())) break;
            trunk.add(log.immutable());
            if (n == 31) return false;
        }
        if (trunk.size() < 3 || !world.getBlockState(bottom.below()).is(BlockTags.DIRT)) return false;
        for (BlockPos log : trunk) {
            for (Direction side : Direction.values()) {
                BlockPos neighbor = log.relative(side);
                if (trunk.contains(neighbor)) continue;
                if (!loaded.test(neighbor)) return false;
                BlockState attached = world.getBlockState(neighbor);
                // Cables, connectors, ladders and attached builds are not vegetation, even
                // when collision-free or next to natural leaves. No machine/mod ID list.
                if (attached.hasBlockEntity() || !(attached.isAir() || attached.is(BlockTags.LOGS)
                        || attached.getBlock() instanceof LeavesBlock
                        || attached.is(BlockTags.REPLACEABLE_BY_TREES)
                        || neighbor.getY() <= bottom.getY() && attached.is(BlockTags.DIRT)
                        || !attached.getFluidState().isEmpty())) return false;
            }
        }
        return MiningBatch.hasNaturalCrown(trunk, world, loaded);
    }

    private static boolean upright(BlockState state, Block type) {
        return state.is(type) && state.is(BlockTags.LOGS) && state.hasProperty(RotatedPillarBlock.AXIS)
                && state.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y;
    }
}
