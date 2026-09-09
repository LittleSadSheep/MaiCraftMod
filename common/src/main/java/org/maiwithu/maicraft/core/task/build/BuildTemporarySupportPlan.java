// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;

/** Find a short chain of removable click supports, ordered from real terrain toward the target. */
final class BuildTemporarySupportPlan {
    private static final Direction[] DIRECTIONS = {Direction.DOWN, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, Direction.UP};
    private static final int MAX_CELLS = 4096;
    private static final int MAX_CHAIN = 32;

    private BuildTemporarySupportPlan() {}

    static List<BlockPos> find(BlockGetter world, Predicate<BlockPos> loaded, BlockPos target,
                              Predicate<BlockPos> mayPlace) {
        var parents = new HashMap<BlockPos, BlockPos>();
        var search = new ArrayDeque<BlockPos>();
        for (Direction direction : DIRECTIONS) {
            BlockPos start = target.relative(direction);
            if (available(world, loaded, target, start, mayPlace)) {
                parents.put(start, null); search.add(start);
            }
        }
        while (!search.isEmpty() && parents.size() <= MAX_CELLS) {
            BlockPos next = search.removeFirst();
            for (Direction direction : DIRECTIONS) {
                BlockPos neighbor = next.relative(direction);
                if (neighbor.equals(target) || !loaded.test(neighbor)) continue;
                var state = world.getBlockState(neighbor);
                if (!state.isAir() && !state.canBeReplaced()
                        && !state.getShape(world, neighbor).isEmpty()) {
                    return chain(next, parents);
                }
                if (!parents.containsKey(neighbor)
                        && available(world, loaded, target, neighbor, mayPlace)) {
                    parents.put(neighbor, next); search.addLast(neighbor);
                }
            }
        }
        return List.of();
    }

    private static boolean available(BlockGetter world, Predicate<BlockPos> loaded, BlockPos target,
                                     BlockPos candidate, Predicate<BlockPos> mayPlace) {
        // This is a bounded local recovery, never an unbounded terrain-fill search.
        return !candidate.equals(target) && Math.abs(candidate.getX() - target.getX()) <= 4
                && Math.abs(candidate.getZ() - target.getZ()) <= 4
                && candidate.getY() >= target.getY() - 24 && candidate.getY() <= target.getY() + 2
                && loaded.test(candidate) && mayPlace.test(candidate)
                && world.getBlockState(candidate).isAir();
    }

    private static List<BlockPos> chain(BlockPos anchor, Map<BlockPos, BlockPos> parents) {
        List<BlockPos> ordered = new ArrayList<>();
        for (BlockPos pos = anchor; pos != null; pos = parents.get(pos)) {
            if (ordered.size() >= MAX_CHAIN) return List.of();
            ordered.add(pos);
        }
        return List.copyOf(ordered);
    }

    /** Preserve the untried tail when deferring, instead of restarting from the first blocked cell. */
    static <T> List<T> defer(List<T> queue, int current, Predicate<T> pending) {
        List<T> result = new ArrayList<>();
        for (int offset = 1; offset <= queue.size(); offset++) {
            T candidate = queue.get((current + offset) % queue.size());
            if (pending.test(candidate)) result.add(candidate);
        }
        return result;
    }
}
