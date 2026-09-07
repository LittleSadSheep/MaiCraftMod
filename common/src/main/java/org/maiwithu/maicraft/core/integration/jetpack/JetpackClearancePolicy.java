// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import net.minecraft.world.phys.Vec3;

/** Prefer breathing room without making the optional margin a condition for a narrow passage. */
final class JetpackClearancePolicy {
    private static final double SIDE_MARGIN = 0.5, TOP_MARGIN = 0.35;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private JetpackClearancePolicy() {}

    /** Body and native UP reserve are required. Five extra sweeps add a finite cost of at most 4.5.
     * The caller may cache this number by position during a search; this helper retains no world view.
     */
    static double clearancePenalty(JetpackRoute.Space space, Vec3 point, JetpackNativeAdapter.Snapshot power) {
        if (!JetpackRoute.flightClear(space, point, point, power)) return Double.POSITIVE_INFINITY;
        double penalty = 0;
        for (int[] side : SIDES) {
            Vec3 offset = point.add(side[0] * SIDE_MARGIN, 0, side[1] * SIDE_MARGIN);
            if (!JetpackRoute.flightClear(space, point, offset, power)) penalty += 1;
        }
        if (!JetpackRoute.flightClear(space, point, point.add(0, TOP_MARGIN, 0), power)) penalty += 0.5;
        return penalty;
    }

    /** Validate the full body/UP corridor, then score its midpoint with the same fixed probe budget.
     * This is a local preference, separate from the edge's distance or traversal time.
     */
    static double edgePenalty(JetpackRoute.Space space, Vec3 from, Vec3 to,
                              JetpackNativeAdapter.Snapshot power) {
        return JetpackRoute.flightClear(space, from, to, power)
                ? clearancePenalty(space, from.lerp(to, 0.5), power) : Double.POSITIVE_INFINITY;
    }
}
