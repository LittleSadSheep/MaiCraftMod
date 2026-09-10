// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.Comparator;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Local continuity is a preference within the active region/layer, never a block-state exemption. */
final class BuildPlacementPreference {
    private BuildPlacementPreference() {}

    static Comparator<BuildTaskRecord.Target> targets(Vec3 feet, BlockPos previous, Map<Long, BuildTaskRecord.Target> all) {
        return Comparator.<BuildTaskRecord.Target>comparingInt(t -> adjacent(previous, t.pos()) ? 0 : 1)
                .thenComparingInt(t -> BuildLayerFrontier.boundaryPriority(t, all))
                .thenComparingDouble(t -> horizontalDistance(feet, t.pos()))
                .thenComparing(BuildOrder.BUILD_ORDER);
    }
    private static boolean adjacent(BlockPos previous, BlockPos next) {
        return previous != null && previous.getY() == next.getY() && previous.distManhattan(next) == 1;
    }
    private static double horizontalDistance(Vec3 from, BlockPos to) {
        double dx = from.x - to.getX() - .5, dz = from.z - to.getZ() - .5;
        return dx * dx + dz * dz;
    }
    static Comparator<BuildPlacementGeometry.Gesture> gestures(Vec3 feet, BuildTaskRecord.Target target) {
        return Comparator.<BuildPlacementGeometry.Gesture>comparingDouble(g -> Math.max(0, feet.y - g.stance().getY()))
                .thenComparingDouble(g -> Math.max(0, target.pos().getY() + 1 - g.stance().getY()))
                .thenComparingDouble(g -> g.stance().distToCenterSqr(feet));
    }
}
