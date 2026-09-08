package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.utils.pathing.PathBase;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Client-thread lowering of flat graph runs; all special movements retain their original owner. */
public final class GroundPathSmoothing extends PathBase {
    private final IPath source;
    private final List<IMovement> movements;
    private final List<BetterBlockPos> positions;

    private GroundPathSmoothing(IPath source, List<IMovement> movements) {
        this.source = source; this.movements = List.copyOf(movements);
        var positions = new ArrayList<BetterBlockPos>(); positions.add(source.getSrc());
        movements.forEach(m -> positions.add(m.getDest())); this.positions = List.copyOf(positions);
        sanityCheck();
    }

    public static IPath apply(IBaritone baritone, IPath path) {
        var ctx = baritone.getPlayerContext(); var player = ctx.player();
        if (player == null || !player.onGround() || player.isInWater() || player.isPassenger()) return path;
        var corridor = new GroundCorridor(ctx.world(), pos -> ctx.world().hasChunkAt(pos)
                && ctx.world().getWorldBorder().isWithinBounds(pos), player.getBbWidth(), player.getBbHeight(),
                EmbeddedBaritonePolicy.snapshot().forbiddenBodyCells(), EmbeddedBaritoneRuntime.physicalObstacles());
        return smooth(baritone, path, corridor, ctx.playerFeet(), player.position());
    }

    static IPath smooth(IBaritone baritone, IPath path, GroundCorridor corridor, BlockPos anchor) {
        return smooth(baritone, path, corridor, anchor, null);
    }

    static IPath smooth(IBaritone baritone, IPath path, GroundCorridor corridor, BlockPos anchor, Vec3 actualStart) {
        var result = new ArrayList<IMovement>(); var original = path.movements();
        int preserveThrough = path.positions().indexOf(anchor);
        // Leave the final approach before a step/jump intact so its established launch and
        // interaction timing keeps both original adjacent primitives.
        for (int start = 0; start < original.size();) {
            if (corridor.exhausted()) { result.addAll(original.subList(start, original.size())); break; }
            IMovement first = original.get(start); int limit = start;
            while (limit < original.size() && flat(original.get(limit))
                    && first.getSrc().distSqr(original.get(limit).getDest()) <= GroundCorridor.MAX_LENGTH * GroundCorridor.MAX_LENGTH) limit++;
            if (limit < original.size() && !flat(original.get(limit))) limit--;
            int end = start;
            Vec3 a = null, b = null;
            if (start >= preserveThrough && limit > start + 1) {
                a = actualStart != null && first.getSrc().equals(anchor) ? actualStart : corridor.stance(first.getSrc());
                // At most eight probes within the 128-block window, including the whole run first.
                for (int candidate = limit - 1; candidate > start; candidate = start + (candidate - start) / 2) {
                    b = corridor.stance(original.get(candidate).getDest());
                    if (corridor.clear(a, b)) { end = candidate; break; }
                }
            }
            if (end > start) {
                double cost = 0;
                for (int i = start; i <= end; i++) cost += original.get(i).getCost();
                result.add(new MovementGroundStraight(baritone, first.getSrc(), original.get(end).getDest(), a, b, cost));
            } else result.add(first);
            start = end + 1;
        }
        return result.size() == original.size() ? path : new GroundPathSmoothing(path, result);
    }

    private static boolean flat(IMovement movement) {
        return (movement instanceof MovementTraverse || movement instanceof MovementDiagonal)
                && movement.getSrc().getY() == movement.getDest().getY();
    }

    @Override public List<IMovement> movements() { return movements; }
    @Override public List<BetterBlockPos> positions() { return positions; }
    @Override public Goal getGoal() { return source.getGoal(); }
    @Override public int getNumNodesConsidered() { return source.getNumNodesConsidered(); }
}
