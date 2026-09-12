// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Partition the authored route without shortcuts; a shared edge retains AE2 boundary-node evidence. */
final class ProductionConnectionPath {
    static final int MAX_POINTS = 128;
    static final int MAX_OFFSET_SQUARED = 16;
    record Segment(int start, int end) {
        List<BlockPos> points(List<BlockPos> path) { return path.subList(start, end + 1); }
    }
    private ProductionConnectionPath() {}

    static List<Segment> split(List<BlockPos> path, String medium) {
        return splitServiceable(path, medium, points -> near(points.getFirst(), points.getLast()));
    }

    /** Keep room for a nearby standing cell while checking every target's own native read radius. */
    static List<Segment> split(List<BlockPos> path, String medium, ToDoubleFunction<BlockPos> radius) {
        var observedRadii = new java.util.HashMap<BlockPos, Double>();
        return splitServiceable(path, medium, points -> {
            try {
                return ProductionObservationRange.goalRadius(points,
                        pos -> observedRadii.computeIfAbsent(pos, key -> radius.applyAsDouble(key))) >= 2;
            } catch (IllegalArgumentException unavailable) { return false; }
        });
    }

    private static List<Segment> splitServiceable(List<BlockPos> path, String medium, Predicate<List<BlockPos>> serviceable) {
        if (path == null || path.size() < 2) throw new IllegalArgumentException("connection_path_requires_two_endpoints");
        var seen = new HashSet<BlockPos>();
        for (int i = 0; i < path.size(); i++) {
            BlockPos position = path.get(i);
            if (position == null || Math.abs((long) position.getX()) > 30_000_000 || Math.abs((long) position.getZ()) > 30_000_000
                    || Math.abs((long) position.getY()) > 2048) throw new IllegalArgumentException("connection_path_outside_native_coordinate_bounds");
            if (!seen.add(position)) throw new IllegalArgumentException("connection_path_repeats_position");
            if (i > 0 && !step(path.get(i - 1), position, medium.equals("kinetic"))) {
                throw new IllegalArgumentException("connection_path_has_gap_or_unsupported_step");
            }
        }
        List<Segment> segments = new ArrayList<>();
        int start = 0;
        while (start < path.size() - 1) {
            int end = start + 1;
            if (!serviceable.test(path.subList(start, end + 1)))
                throw new IllegalArgumentException("connection_segment_exceeds_native_observation_range");
            while (end + 1 < path.size() && end + 1 - start < MAX_POINTS
                    && serviceable.test(path.subList(start, end + 2))) end++;
            segments.add(new Segment(start, end));
            if (end == path.size() - 1) break;
            // A nonfinal segment must advance while retaining a complete shared edge.
            if (end - start < 2) throw new IllegalArgumentException("connection_segment_cannot_preserve_boundary_evidence");
            start = end - 1;
        }
        return List.copyOf(segments);
    }

    static String face(BlockPos from, BlockPos to) {
        for (Direction side : Direction.values()) if (from.relative(side).equals(to)) return side.getSerializedName();
        return null;
    }

    static boolean near(BlockPos first, BlockPos position) {
        long x = (long) first.getX() - position.getX(), y = (long) first.getY() - position.getY(), z = (long) first.getZ() - position.getZ();
        if (Math.abs(x) > 4 || Math.abs(y) > 4 || Math.abs(z) > 4) return false;
        return x * x + y * y + z * z <= MAX_OFFSET_SQUARED;
    }

    private static boolean step(BlockPos from, BlockPos to, boolean kinetic) {
        long x = Math.abs((long) from.getX() - to.getX()), y = Math.abs((long) from.getY() - to.getY()), z = Math.abs((long) from.getZ() - to.getZ());
        return x + y + z == 1 || kinetic && Math.max(x, Math.max(y, z)) <= 1 && x + y + z == 2;
    }
}
