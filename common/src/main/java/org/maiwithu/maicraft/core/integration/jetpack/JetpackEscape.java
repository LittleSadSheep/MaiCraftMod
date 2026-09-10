// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * 需要提前结束飞行时，比较正下方降落、继续原路和沿原路退回三种已知出口，选择估计耗时最少且当前通畅的一条。
 */
final class JetpackEscape {
    static JetpackRoute.Plan choose(JetpackRoute.Space space, Vec3 position, JetpackRoute.Plan route,
                                   int next, JetpackNativeAdapter.Snapshot power) {
        List<List<Vec3>> candidates = new ArrayList<>();
        Vec3 below = space.landingBelow(position.add(0, 0.1, 0));
        if (below != null) candidates.add(List.of(position, below));
        var forward = new ArrayList<Vec3>(); forward.add(position);
        forward.addAll(route.points().subList(Math.min(next, route.points().size()-1), route.points().size()));
        candidates.add(forward);
        var backward = new ArrayList<Vec3>(); backward.add(position);
        for (int i = Math.min(next-1, route.points().size()-1); i >= 0; i--) backward.add(route.points().get(i));
        candidates.add(backward);
        JetpackRoute.Plan best = null;
        for (List<Vec3> points : candidates) {
            if (points.size() < 2) continue;
            Vec3 end = points.getLast(), floor = space.landingBelow(end.add(0, 0.1, 0));
            if (floor == null || Math.abs(floor.y - end.y) > 0.1) continue;
            double ticks = 60; boolean clear = true;
            for (int i = 1; i < points.size(); i++) {
                if (!space.clear(points.get(i-1), points.get(i))) { clear = false; break; }
                ticks += JetpackRoute.edgeTicks(points.get(i-1), points.get(i), power);
            }
            if (clear && Double.isFinite(ticks) && (best == null || ticks < best.requiredTicks())) {
                best = new JetpackRoute.Plan(points, List.of(floor), (int) Math.ceil(ticks));
            }
        }
        return best;
    }
}
