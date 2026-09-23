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
import java.util.Objects;

/** 对一条已建成且独立的基础能源线缆路径执行只读重试识别。 */
public final class UtilityExistingCableRoute {
    public static final int MAX_CABLES = 128;
    public interface WorldView {
        boolean loaded(BlockPos position);
        /** 仅对受支持的 Mekanism 基础通用线缆返回 true。 */
        boolean cable(BlockPos position);
        /** 可能连接到无关网络的其他方块实体或导管。 */
        boolean device(BlockPos position);
    }
    public static final class RejectedRouteException extends IllegalArgumentException {
        public RejectedRouteException(String code) { super(code); }
    }
    private enum Cell { CABLE, DEVICE, OTHER }
    private UtilityExistingCableRoute() {}

    /** null 表示不存在完整简单路径；绝不会静默绕过不安全的现有网络。 */
    public static UtilityConnectionPlanner.Route find(BlockPos source, List<Direction> eligibleSourceFaces,
            BlockPos target, Direction exactTargetFace, WorldView world) {
        if (source == null || target == null || source.equals(target) || exactTargetFace == null
                || eligibleSourceFaces == null || eligibleSourceFaces.stream().anyMatch(Objects::isNull) || world == null)
            throw rejected("endpoints_required");
        requireLoaded(world, source); requireLoaded(world, target);
        Map<BlockPos, Cell> observations = new HashMap<>();
        BlockPos end = target.relative(exactTargetFace);
        // 新机器不得触发对城市中现有能源端口及其使用设备的探索。
        if (end.equals(source) || observe(world, end, observations) != Cell.CABLE) return null;
        var graph = component(source, target, end, world, observations, new HashSet<>());
        int edges = graph.values().stream().mapToInt(List::size).sum() / 2;
        if (edges >= graph.size()) throw rejected("loop");
        List<BlockPos> sourceContacts = graph.keySet().stream().filter(at -> at.distManhattan(source) == 1).toList();
        if (sourceContacts.size() > 1) throw rejected("unexpected_endpoint_face");
        for (var entry : graph.entrySet()) {
            int degree = entry.getValue().size() + (entry.getKey().distManhattan(source) == 1 ? 1 : 0)
                    + (entry.getKey().equals(end) ? 1 : 0);
            if (degree > 2) throw rejected("branch");
        }
        if (sourceContacts.isEmpty()) return null;
        BlockPos start = sourceContacts.getFirst();
        Direction face = eligibleSourceFaces.stream().filter(candidate -> source.relative(candidate).equals(start)).findFirst().orElse(null);
        if (face == null) throw rejected("unexpected_endpoint_face");
        List<BlockPos> path = new ArrayList<>(); path.add(source);
        BlockPos previous = source, at = start;
        while (true) {
            path.add(at);
            if (at.equals(end)) break;
            BlockPos next = null;
            for (BlockPos neighbor : graph.get(at)) if (!neighbor.equals(previous)) { next = neighbor; break; }
            if (next == null) throw rejected("incomplete_path");
            previous = at; at = next;
        }
        path.add(target); return new UtilityConnectionPlanner.Route(face, path);
    }

    private static Map<BlockPos, List<BlockPos>> component(BlockPos source, BlockPos target, BlockPos end,
            WorldView world, Map<BlockPos, Cell> observations, Set<BlockPos> visited) {
        Map<BlockPos, List<BlockPos>> graph = new LinkedHashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(); enqueue(end, queue, visited);
        while (!queue.isEmpty()) {
            BlockPos at = queue.removeFirst(); List<BlockPos> neighbors = new ArrayList<>();
            for (Direction direction : Direction.values()) {
                BlockPos next = at.relative(direction);
                if (next.equals(source) || next.equals(target)) {
                    if (next.equals(target) && !at.equals(end))
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
