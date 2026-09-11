// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;

/** Incremental existing-footing graph; turning and one-block steps need no hypothetical floor. */
final class BuildFootingSearch {
    record Route(Vec3 feet, double distance, double lowestY, List<Vec3> points) {}
    private record Node(Vec3 feet, double distance, double lowestY, Node previous) {}
    private static final int MAX_NODES = 2048;
    private final Vec3 origin;
    private final int minY, maxY;
    private final BuildSupportWalking walking;
    private final Map<BlockPos, Node> best = new HashMap<>();
    private final Map<BlockPos, Node> reached = new HashMap<>();
    private final PriorityQueue<Node> open;
    private boolean started, complete;

    BuildFootingSearch(LocalPlayer player, List<BuildTaskRecord.Target> pending, LongSet forbidden) {
        origin = player.position();
        minY = (int) Math.floor(Math.min(origin.y, pending.stream().mapToInt(t -> t.pos().getY()).min().orElse((int) origin.y))) - 2;
        maxY = (int) Math.ceil(Math.max(origin.y, pending.stream().mapToInt(t -> t.pos().getY() + 1).max().orElse((int) origin.y)));
        var view = new BuildSupportWorld(player.level(), player.level()::isLoaded, Map.of());
        walking = new BuildSupportWalking(view, player.level()::isLoaded, player.getBbWidth(),
                Math.max(player.getBbHeight(), player.getDimensions(Pose.STANDING).height()), forbidden,
                EmbeddedBaritoneRuntime.physicalObstacles());
        open = new PriorityQueue<>(Comparator.<Node>comparingDouble(n -> Math.max(0, origin.y - n.lowestY()))
                .thenComparingDouble(Node::distance).thenComparing(n -> BlockPos.containing(n.feet())));
    }

    boolean advance() {
        if (complete) return true;
        try {
            if (!started) {
                started = true; Vec3 start = walking.stance(BlockPos.containing(origin));
                if (start == null || !walking.edge(origin, start)) { complete = true; return true; }
                add(new Node(start, origin.distanceTo(start), Math.min(origin.y, start.y), null));
                return false;
            }
            Node node = open.poll();
            if (node == null || reached.size() >= MAX_NODES) { complete = true; return true; }
            BlockPos cell = BlockPos.containing(node.feet());
            if (best.get(cell) != node || reached.containsKey(cell)) return false;
            reached.put(cell, node);
            for (Direction direction : Direction.Plane.HORIZONTAL) for (int dy : new int[]{0, 1, -1}) {
                BlockPos next = cell.relative(direction).offset(0, dy, 0);
                if (!within(next) || reached.containsKey(next)) continue;
                Vec3 feet = walking.stance(next);
                if (feet == null || !within(BlockPos.containing(feet)) || !walking.edge(node.feet(), feet)) continue;
                add(new Node(feet, node.distance() + node.feet().distanceTo(feet), Math.min(node.lowestY(), feet.y), node));
            }
        } catch (RuntimeException | LinkageError unavailable) { complete = true; }
        return complete;
    }

    private void add(Node node) {
        BlockPos cell = BlockPos.containing(node.feet()); Node old = best.get(cell);
        if (old != null && (old.lowestY() > node.lowestY()
                || old.lowestY() == node.lowestY() && old.distance() <= node.distance())) return;
        best.put(cell, node); open.add(node);
    }
    private boolean within(BlockPos pos) {
        return Math.abs(pos.getX() + .5 - origin.x) <= 24 && Math.abs(pos.getZ() + .5 - origin.z) <= 24
                && pos.getY() >= minY && pos.getY() <= maxY;
    }
    Route route(BlockPos cell) {
        // An admitted edge already proves its destination reachable. The expansion budget
        // only limits where we look next; it must not erase verified lower fallback routes.
        Node node = best.get(cell); if (node == null) return null;
        var points = new ArrayList<Vec3>();
        for (Node step = node; step != null; step = step.previous()) points.add(step.feet());
        java.util.Collections.reverse(points);
        return new Route(node.feet(), node.distance(), node.lowestY(), List.copyOf(points));
    }
}
