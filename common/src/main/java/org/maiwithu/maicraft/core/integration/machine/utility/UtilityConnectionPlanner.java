// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.utility;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Finite loaded-world routing; unknown or occupied cells are never silently cleared. */
public final class UtilityConnectionPlanner {
    public static final int MAX_PATH = 128, MAX_VISITED = 8192, MAX_SPAN = 96;
    public record Route(Direction sourceFace, List<BlockPos> path) {
        public Route { path = path.stream().map(BlockPos::immutable).toList(); }
        public List<BlockPos> cables() { return path.subList(1, path.size() - 1); }
    }
    private record Step(BlockPos at, int cost, int remaining) {}
    private UtilityConnectionPlanner() {}

    public static Route plan(BlockPos source, List<Direction> exportFaces, BlockPos target,
            Direction inputFace, Predicate<BlockPos> emptyLoadedCell) {
        if (source.distManhattan(target) > MAX_SPAN) throw new IllegalArgumentException("utility_route_requires_nearer_outlet");
        BlockPos end = target.relative(inputFace);
        if (end.equals(source) || !emptyLoadedCell.test(end))
            throw new IllegalArgumentException("utility_input_approach_occupied_or_unloaded");
        List<Direction> faces = exportFaces.stream().distinct().sorted(Comparator
                .comparingInt((Direction face) -> source.relative(face).distManhattan(end))
                .thenComparingInt(Enum::ordinal)).toList();
        int remainingBudget = MAX_VISITED;
        for (Direction face : faces) {
            BlockPos start = source.relative(face);
            if (start.equals(target) || !emptyLoadedCell.test(start)
                    || touchesOtherFace(start, source, start, target, end)
                    || touchesOtherFace(end, source, start, target, end)) continue;
            PriorityQueue<Step> queue = new PriorityQueue<>(Comparator.comparingInt((Step step) -> step.cost + step.remaining)
                    .thenComparingInt(Step::remaining).thenComparingLong(step -> step.at.asLong()));
            Map<BlockPos, BlockPos> previous = new HashMap<>();
            Map<BlockPos, Integer> distance = new HashMap<>();
            queue.add(new Step(start, 0, start.distManhattan(end))); previous.put(start, null); distance.put(start, 0);
            while (!queue.isEmpty() && remainingBudget-- > 0) {
                Step step = queue.remove(); BlockPos at = step.at();
                if (distance.get(at) != step.cost) continue;
                if (at.equals(end)) {
                    ArrayList<BlockPos> route = new ArrayList<>();
                    for (BlockPos cursor = end; cursor != null; cursor = previous.get(cursor)) route.add(cursor);
                    java.util.Collections.reverse(route);
                    if (route.size() + 2 > MAX_PATH) break;
                    route.addFirst(source); route.add(target);
                    return new Route(face, route);
                }
                for (Direction direction : Direction.values()) {
                    BlockPos next = at.relative(direction);
                    int cost = step.cost + 1;
                    if (next.equals(source) || next.equals(target) || cost >= distance.getOrDefault(next, Integer.MAX_VALUE)
                            || touchesOtherFace(next, source, start, target, end)
                            || !within(next, source, target) || !emptyLoadedCell.test(next)) continue;
                    previous.put(next, at); distance.put(next, cost);
                    queue.add(new Step(next, cost, next.distManhattan(end)));
                }
            }
        }
        throw new IllegalArgumentException("utility_no_loaded_preserving_cable_route");
    }
    private static boolean touchesOtherFace(BlockPos cell, BlockPos source, BlockPos start, BlockPos target, BlockPos end) {
        return cell.distManhattan(source) == 1 && !cell.equals(start) || cell.distManhattan(target) == 1 && !cell.equals(end);
    }
    private static boolean within(BlockPos at, BlockPos source, BlockPos target) {
        return at.getX() >= Math.min(source.getX(), target.getX()) - 8 && at.getX() <= Math.max(source.getX(), target.getX()) + 8
                && at.getY() >= Math.min(source.getY(), target.getY()) - 8 && at.getY() <= Math.max(source.getY(), target.getY()) + 8
                && at.getZ() >= Math.min(source.getZ(), target.getZ()) - 8 && at.getZ() <= Math.max(source.getZ(), target.getZ()) + 8;
    }
}
