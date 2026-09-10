// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;

/** Geometric work regions: vertical bodies remain separate across shared floors and roof plates. */
final class BuildRegions {
    private record Column(BlockPos base, List<BlockPos> run) {
        int bottom() { return run.getFirst().getY(); }
        int top() { return run.getLast().getY(); }
        boolean overlaps(Column other) { return Math.min(top(), other.top()) > Math.max(bottom(), other.bottom()); }
    }
    private final Map<Long, Integer> assignment = new HashMap<>();
    private final Map<Integer, List<BlockPos>> cells = new LinkedHashMap<>();
    private final Map<Integer, BlockPos[]> bounds = new HashMap<>();

    BuildRegions(Map<Long, BuildTaskRecord.Target> targets) {
        var columns = columns(targets);
        // A flat collection has no distinct vertical bodies to pin; retain flexible layer-local work.
        if (columns.isEmpty()) {
            cells.put(1, new ArrayList<>());
            for (var target : targets.values()) {
                assignment.put(target.pos().asLong(), 1); cells.get(1).add(target.pos());
            }
            return;
        }
        var groups = new ArrayList<List<Column>>();
        var seen = new HashSet<Long>();
        for (var column : columns.values()) {
            if (!seen.add(column.base().asLong())) continue;
            var group = new ArrayList<Column>();
            var open = new ArrayDeque<Column>(); open.add(column);
            while (!open.isEmpty()) {
                var next = open.removeFirst(); group.add(next);
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    var neighbor = columns.get(next.base().relative(direction).asLong());
                    if (neighbor != null && next.overlaps(neighbor) && seen.add(neighbor.base().asLong())) open.add(neighbor);
                }
            }
            group.sort(Comparator.comparing(Column::base)); groups.add(group);
        }
        groups.sort(Comparator.<List<Column>>comparingInt(List::size).reversed()
                .thenComparing(group -> group.getFirst().base()));
        var wave = new ArrayDeque<BlockPos>();
        for (var group : groups) {
            int id = cells.size() + 1; cells.put(id, new ArrayList<>());
            for (var column : group) for (BlockPos pos : column.run()) assign(pos, id, wave);
        }
        // Plates inherit their nearest body's region; they never merge two already separate bodies.
        expand(targets, wave);
        var ordered = targets.values().stream().filter(t -> !BuildCellRules.isAirTarget(t))
                .sorted(BuildOrder.BUILD_ORDER).toList();
        for (var target : ordered) if (!assignment.containsKey(target.pos().asLong())) {
            int id = cells.size() + 1; cells.put(id, new ArrayList<>());
            assign(target.pos(), id, wave); expand(targets, wave);
        }
        if (cells.isEmpty()) cells.put(1, new ArrayList<>());
        cells.forEach((id, positions) -> {
            if (positions.isEmpty()) return;
            int minX = Integer.MAX_VALUE, minY = minX, minZ = minX;
            int maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
            for (BlockPos pos : positions) {
                minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
            }
            bounds.put(id, new BlockPos[]{new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ)});
        });
        for (var target : targets.values()) if (!assignment.containsKey(target.pos().asLong()))
            assignment.put(target.pos().asLong(), nearest(target.pos()));
    }

    int region(BuildTaskRecord.Target target) {
        return assignment.getOrDefault(BuildPlacementGeometry.primaryOf(target).asLong(),
                assignment.getOrDefault(target.pos().asLong(), 1));
    }
    int count() { return cells.size(); }

    /** Finish the selected body before allowing another body's lower cells to change the layer gate. */
    int choose(List<BuildTaskRecord.Target> pending, int active) {
        var counts = new HashMap<Integer, Integer>();
        for (var target : pending) counts.merge(region(target), 1, Integer::sum);
        if (counts.containsKey(active)) return active;
        return counts.keySet().stream().min(Comparator.<Integer>comparingInt(id -> cells.get(id).size()).reversed()
                .thenComparingInt(Integer::intValue)).orElse(0);
    }

    private void assign(BlockPos pos, int id, ArrayDeque<BlockPos> wave) {
        if (assignment.putIfAbsent(pos.asLong(), id) != null) return;
        cells.get(id).add(pos); wave.addLast(pos);
    }

    private void expand(Map<Long, BuildTaskRecord.Target> targets, ArrayDeque<BlockPos> wave) {
        while (!wave.isEmpty()) {
            BlockPos pos = wave.removeFirst(); int id = assignment.get(pos.asLong());
            for (Direction direction : Direction.values()) {
                var neighbor = targets.get(pos.relative(direction).asLong());
                if (neighbor != null && !BuildCellRules.isAirTarget(neighbor)) assign(neighbor.pos(), id, wave);
            }
        }
    }

    private int nearest(BlockPos pos) {
        int best = 1; double distance = Double.POSITIVE_INFINITY;
        for (int id : cells.keySet()) {
            var box = bounds.get(id); if (box == null) continue;
            double dx = Math.max(0, Math.max((double) box[0].getX() - pos.getX(), (double) pos.getX() - box[1].getX()));
            double dy = Math.max(0, Math.max((double) box[0].getY() - pos.getY(), (double) pos.getY() - box[1].getY()));
            double dz = Math.max(0, Math.max((double) box[0].getZ() - pos.getZ(), (double) pos.getZ() - box[1].getZ()));
            double next = dx * dx + dy * dy + dz * dz;
            if (next < distance) { distance = next; best = id; }
        }
        return best;
    }

    private static Map<Long, Column> columns(Map<Long, BuildTaskRecord.Target> targets) {
        var stacks = new HashMap<Long, List<BlockPos>>();
        for (var target : targets.values()) if (vertical(target)) {
            BlockPos pos = target.pos();
            stacks.computeIfAbsent(BlockPos.asLong(pos.getX(), 0, pos.getZ()), ignored -> new ArrayList<>()).add(pos);
        }
        var out = new java.util.TreeMap<Long, Column>();
        stacks.forEach((key, stack) -> {
            stack.sort(Comparator.comparingInt(BlockPos::getY));
            int start = 0, bestStart = 0, bestSize = 0;
            for (int i = 0; i < stack.size(); i++) {
                if (i > 0 && stack.get(i).getY() != stack.get(i - 1).getY() + 1) start = i;
                if (i - start + 1 > bestSize) { bestStart = start; bestSize = i - start + 1; }
            }
            if (bestSize >= 2) out.put(key, new Column(BlockPos.of(key), List.copyOf(stack.subList(bestStart, bestStart + bestSize))));
        });
        // Seed bodies at their shared base band. Skylights and roof ornaments inherit the body below.
        int base = out.values().stream().mapToInt(Column::bottom).min().orElse(0);
        out.values().removeIf(column -> (long) column.bottom() > (long) base + 1);
        return out;
    }

    private static boolean vertical(BuildTaskRecord.Target target) {
        if (BuildCellRules.isAirTarget(target)) return false;
        try {
            var shape = target.desiredState().getCollisionShape(EmptyBlockGetter.INSTANCE, target.pos());
            return !shape.isEmpty() && shape.bounds().getYsize() >= .9;
        } catch (RuntimeException | LinkageError unavailable) { return false; }
    }
}
