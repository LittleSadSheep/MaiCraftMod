package org.maiwithu.maicraft.core.task.mine;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.Comparator;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LeavesBlock;
import org.maiwithu.maicraft.core.pathing.moves.MovementHelper;

/** A bounded subset of the existing target list, never a new terrain search or a loot receipt. */
record MiningBatch(Set<BlockPos> targets, boolean followTrunk) {
    static MiningBatch connected(BlockPos seed, Set<BlockPos> sameMaterial,
                                 boolean uprightLogs, boolean naturalLeaves) {
        Set<BlockPos> selected = new HashSet<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        if (sameMaterial.contains(seed)) { selected.add(seed.immutable()); pending.add(seed); }
        while (!pending.isEmpty()) {
            BlockPos current = pending.removeFirst();
            for (Direction direction : Direction.values()) {
                // A natural trunk can be followed vertically, but a touching wall/roof or a
                // neighbouring tree must not become extra work merely to postpone pickup.
                if (uprightLogs && direction.getAxis() != Direction.Axis.Y) continue;
                BlockPos next = current.relative(direction);
                if (sameMaterial.contains(next) && selected.add(next)) pending.addLast(next);
            }
        }
        return new MiningBatch(Set.copyOf(selected), uprightLogs && naturalLeaves);
    }

    static boolean shouldCollect(int carried, int requested, int ownedLoose,
                                 long waitingTicks, boolean riskyDrop) {
        // Loose output is only a reason to stop producing more. It never increases the
        // reported gathered count, which remains the synchronized inventory delta.
        return carried >= requested || (long) carried + ownedLoose >= requested
                || waitingTicks >= 20L * 60L || riskyDrop;
    }

    static boolean hasNaturalCrown(Set<BlockPos> trunk, BlockGetter world, Predicate<BlockPos> loaded) {
        if (trunk.isEmpty()) return false;
        BlockPos top = trunk.stream().max(Comparator.comparingInt(BlockPos::getY)).orElseThrow();
        for (BlockPos nearby : BlockPos.betweenClosed(top.offset(-2, -1, -2), top.offset(2, 2, 2))) {
            if (!loaded.test(nearby)) continue;
            var state = world.getBlockState(nearby);
            if (state.getBlock() instanceof LeavesBlock && !state.getValue(LeavesBlock.PERSISTENT)) return true;
        }
        return false;
    }

    static boolean safeDropLanding(BlockPos position, BlockGetter world, Predicate<BlockPos> loaded) {
        for (int depth = 0; depth < 4; depth++) {
            BlockPos below = position.below(depth);
            if (!loaded.test(below)) return false;
            var state = world.getBlockState(below);
            if (MovementHelper.avoidWalkingInto(state)) return false;
            if (!state.getCollisionShape(world, below).isEmpty()) return true;
        }
        return false;
    }
}
