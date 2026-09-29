// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;

/** 原锚点堵住后，沿现有地板的连续潜行位置找另一处能站立的落脚点；不改方块、不假定已经走到。 */
final class BuildEdgeRecoverySearch {
    private record Node(int x, int z, Node previous) {}
    private final LocalPlayer player;
    private final BuildSupportWorld world;
    private final Vec3 origin;
    private final LongSet forbidden;
    private final Predicate<BlockPos> permitted;
    private final PhysicalObstacleSnapshot physical;
    private final ArrayDeque<Node> frontier = new ArrayDeque<>();
    private final Set<Integer> visited = new HashSet<>();
    private List<Vec3> route = List.of();
    private boolean complete, found;
    private String reason = "searching_existing_supported_exit";

    BuildEdgeRecoverySearch(LocalPlayer player, BuildSupportWorld world, LongSet forbidden,
            Predicate<BlockPos> permitted, PhysicalObstacleSnapshot physical) {
        this.player = player; this.world = world; this.forbidden = forbidden;
        this.permitted = permitted; this.physical = physical; origin = player.position();
        frontier.add(new Node(0, 0, null)); visited.add(key(0, 0));
    }
    boolean advance() {
        long until = System.nanoTime() + 2_000_000;
        try {
            for (int work = 0; work < 16 && !complete && System.nanoTime() < until; work++) {
                Node node = frontier.pollFirst();
                if (node == null) { complete = true; reason = "no_existing_supported_exit_within_four_blocks"; break; }
                Vec3 at = point(node);
                if (safe(at, at, Pose.STANDING) && BuildFootprintSupport.complete(world, player.level()::isLoaded,
                        player.getBbWidth(), at, at, at)) {
                    var path = new ArrayList<Vec3>();
                    for (Node step = node; step != null; step = step.previous()) path.add(point(step));
                    Collections.reverse(path); route = List.copyOf(path); found = complete = true; reason = "alternate_supported_exit_proved"; break;
                }
                // 四分之一格的短边保留柱边的真实接触，不能把正在潜行的身体先取整到悬空格心。
                for (int[] side : new int[][]{{0, -1}, {0, 1}, {-1, 0}, {1, 0}}) {
                    int x = node.x() + side[0], z = node.z() + side[1];
                    if (Math.abs(x) > 16 || Math.abs(z) > 16 || visited.contains(key(x, z))) continue;
                    Node next = new Node(x, z, node);
                    if (safe(at, point(next), Pose.CROUCHING) && visited.add(key(x, z))) frontier.addLast(next);
                }
            }
        } catch (RuntimeException | LinkageError unavailable) { complete = true; found = false; reason = "recovery_geometry_unavailable"; }
        return complete;
    }
    private boolean safe(Vec3 from, Vec3 to, Pose pose) {
        // 规划与实际微移共用扫掠判定，危险液体、保护区、缺失支撑及物理结构都不能成为逃离捷径。
        return BuildEdgeMotion.safeSweep(world, player.level()::isLoaded, player.getBbWidth(),
                player.getDimensions(pose).height(), forbidden, permitted, physical, from, to);
    }
    private Vec3 point(Node node) { return origin.add(node.x() * .25, 0, node.z() * .25); }
    private static int key(int x, int z) { return (x + 16) * 33 + z + 16; }
    boolean found() { return found; }
    List<Vec3> route() { return route; }
    int visited() { return visited.size(); }
    String reason() { return reason; }
}
