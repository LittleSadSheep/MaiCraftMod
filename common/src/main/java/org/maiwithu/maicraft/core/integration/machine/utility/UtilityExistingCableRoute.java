// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.utility;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Read-only retry recognition for one isolated, already constructed basic energy-cable path. */
public final class UtilityExistingCableRoute {
    public static final int MAX_CABLES = 128;
    public interface WorldView {
        boolean loaded(BlockPos position);
        /** True only for the supported Mekanism basic universal cable. */
        boolean cable(BlockPos position);
        /** Other block entities or conduits which could attach an unrelated network. */
        boolean device(BlockPos position);
    }
    public static final class RejectedRouteException extends IllegalArgumentException {
        public RejectedRouteException(String code) { super(code); }
    }
    private enum Cell { CABLE, DEVICE, OTHER }
    private UtilityExistingCableRoute() {}

    /** Null means no complete simple path exists; an unsafe existing network is never bypassed silently. */
    public static UtilityConnectionPlanner.Route find(BlockPos source, List<Direction> eligibleSourceFaces,
            BlockPos target, Direction exactTargetFace, WorldView world) {
        if (source == null || target == null || source.equals(target) || exactTargetFace == null
                || eligibleSourceFaces == null || eligibleSourceFaces.stream().anyMatch(java.util.Objects::isNull) || world == null)
            throw rejected("endpoints_required");
        requireLoaded(world, source); requireLoaded(world, target);
        Map<BlockPos, Cell> observations = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        UtilityConnectionPlanner.Route found = null;
        for (Direction face : eligibleSourceFaces.stream().distinct().sorted().toList()) {
            BlockPos start = source.relative(face);
            if (start.equals(target) || visited.contains(start) || observe(world, start, observations) != Cell.CABLE) continue;
            var graph = component(source, start, target, target.relative(exactTargetFace), world, observations, visited);
            int edges = graph.values().stream().mapToInt(List::size).sum() / 2;
            if (edges >= graph.size()) throw rejected("loop");
            boolean complete = graph.containsKey(target.relative(exactTargetFace));
            for (var entry : graph.entrySet()) {
                int degree = entry.getValue().size() + (entry.getKey().equals(start) ? 1 : 0)
                        + (entry.getKey().equals(target.relative(exactTargetFace)) ? 1 : 0);
                if (degree > 2) throw rejected("branch");
            }
            if (!complete) continue;
            if (found != null) throw rejected("multiple_routes");
            List<BlockPos> path = new ArrayList<>(); path.add(source);
            BlockPos previous = source, at = start;
            while (true) {
                path.add(at);
                if (at.equals(target.relative(exactTargetFace))) break;
                BlockPos next = null;
                for (BlockPos neighbor : graph.get(at)) if (!neighbor.equals(previous)) { next = neighbor; break; }
                if (next == null) throw rejected("incomplete_path");
                previous = at; at = next;
            }
            path.add(target); found = new UtilityConnectionPlanner.Route(face, path);
        }
        return found;
    }

    private static Map<BlockPos, List<BlockPos>> component(BlockPos source, BlockPos start, BlockPos target, BlockPos end,
            WorldView world, Map<BlockPos, Cell> observations, Set<BlockPos> visited) {
        Map<BlockPos, List<BlockPos>> graph = new LinkedHashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(); enqueue(start, queue, visited);
        while (!queue.isEmpty()) {
            BlockPos at = queue.removeFirst(); List<BlockPos> neighbors = new ArrayList<>();
            for (Direction direction : Direction.values()) {
                BlockPos next = at.relative(direction);
                if (next.equals(source) || next.equals(target)) {
                    if (next.equals(source) && !at.equals(start) || next.equals(target) && !at.equals(end))
                        throw rejected("unexpected_endpoint_face");
                    continue;
                }
                Cell cell = observe(world, next, observations);
                if (cell == Cell.DEVICE) throw rejected("foreign_device");
                if (cell == Cell.CABLE) {
                    neighbors.add(next);
                    if (!visited.contains(next)) enqueue(next, queue, visited);
                }
            }
            graph.put(at, List.copyOf(neighbors));
        }
        return graph;
    }
    private static void enqueue(BlockPos at, ArrayDeque<BlockPos> queue, Set<BlockPos> visited) {
        if (visited.size() >= MAX_CABLES) throw rejected("network_too_large");
        visited.add(at.immutable()); queue.add(at.immutable());
    }
    private static Cell observe(WorldView world, BlockPos at, Map<BlockPos, Cell> observations) {
        Cell prior = observations.get(at); if (prior != null) return prior;
        requireLoaded(world, at);
        Cell cell = world.cable(at) ? Cell.CABLE : world.device(at) ? Cell.DEVICE : Cell.OTHER;
        observations.put(at.immutable(), cell); return cell;
    }
    private static void requireLoaded(WorldView world, BlockPos at) {
        if (!world.loaded(at)) throw rejected("unloaded");
    }
    private static RejectedRouteException rejected(String suffix) {
        return new RejectedRouteException("utility_existing_cable_" + suffix);
    }
}
