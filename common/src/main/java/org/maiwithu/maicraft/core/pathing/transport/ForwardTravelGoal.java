package org.maiwithu.maicraft.core.pathing.transport;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;

/** 中间路段只要求向原终点取得水平进展，允许整片前方区域；真实地形决定在哪个安全位置停下。 */
final class ForwardTravelGoal implements NavGoal {
    static final int RANGE = 56;
    final BlockPos origin;
    final NavGoal destination;
    final boolean surface;
    final int sampleStep;
    private final double dx, dz, distance;

    ForwardTravelGoal(BlockPos origin, NavGoal destination, boolean surface, int sampleStep) {
        this.origin = origin.immutable(); this.destination = destination; this.surface = surface; this.sampleStep = sampleStep;
        double x = destination.center().getX() - origin.getX(), z = destination.center().getZ() - origin.getZ();
        distance = Math.hypot(x, z); dx = x / distance; dz = z / distance;
    }
    @Override public boolean isAt(BlockPos feet) {
        double x = feet.getX() - origin.getX(), z = feet.getZ() - origin.getZ();
        // 不要求命中代表点；任意有足够前进量且在本地飞行范围内的真实落点都可结束这一段。
        return origin.distSqr(feet) <= RANGE * RANGE && x * dx + z * dz >= 12
                && Math.hypot(destination.center().getX() - feet.getX(), destination.center().getZ() - feet.getZ()) < distance - 6;
    }
    @Override public double heuristic(BlockPos from) {
        return Math.max(0, 12 - (from.getX() - origin.getX()) * dx - (from.getZ() - origin.getZ()) * dz) * COST_HEURISTIC;
    }
    @Override public BlockPos center() { return origin.offset((int) Math.round(dx * 32), 0, (int) Math.round(dz * 32)); }
    double remaining(BlockPos feet) { return destination.progressHeuristic(feet); }
    double score(BlockPos feet) { return NavGoal.pointBound(feet, origin) + remaining(feet); }
    @Override public SemanticFingerprint semanticFingerprint() {
        return new SemanticFingerprint("forward_travel", List.of(origin.asLong(), destination.semanticFingerprint(), surface, sampleStep));
    }
}
