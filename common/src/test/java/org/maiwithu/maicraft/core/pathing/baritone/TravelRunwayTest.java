package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementTraverse;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;

public final class TravelRunwayTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        IPlayerContext context = (IPlayerContext) Proxy.newProxyInstance(IPlayerContext.class.getClassLoader(),
                new Class<?>[]{IPlayerContext.class}, (p, m, a) -> null);
        IBaritone baritone = (IBaritone) Proxy.newProxyInstance(IBaritone.class.getClassLoader(),
                new Class<?>[]{IBaritone.class}, (p, m, a) -> context);
        var src = new BetterBlockPos(0, 0, 0);
        var steps = new ArrayList<IMovement>();
        for (int x = 0; x < 8; x++) steps.add(new MovementTraverse(baritone, src.east(x), src.east(x + 1)));
        var runway = TravelRunway.capture(steps, 0, new Vec3(3.5, 0, .5));
        check(runway.covering(4).size() == 7 && runway.covering(5.1).isEmpty(),
                "remaining physical distance, not a movement's full original length, bounds a hop");
        steps.set(3, new MovementAscend(baritone, src.east(3), src.east(4).above()));
        check(TravelRunway.capture(steps, 0, new Vec3(.5, 0, .5)).covering(4).isEmpty(),
                "an upcoming ascent must stay with the upstream movement");
        steps.set(3, new MovementTraverse(baritone, src.east(3), src.east(3).south()));
        check(TravelRunway.capture(steps, 0, new Vec3(.5, 0, .5)).covering(4).isEmpty(),
                "a turn cannot become a straight jump runway");
        Vec3 start = new Vec3(.5, 0, .5), target = new Vec3(8.5, 0, 4.5);
        var straight = new MovementGroundStraight(baritone, src, new BetterBlockPos(8, 0, 4), start, target, 40);
        var direct = TravelRunway.capture(List.of(straight), 0, start);
        check(direct.covering(5).equals(List.of(straight)) && direct.point(5).distanceTo(start.lerp(target, 5 / start.distanceTo(target))) < 1e-6,
                "smoothed arbitrary bearings use the same runway contract");
        var world = new GroundPathSmoothingTest.Scene();
        var corridor = new GroundCorridor(world, p -> true, .6, 1.8, LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY);
        check(!corridor.hasContinuousCeiling(start, target, 2), "open sky is not a low ceiling");
        for (int x = -1; x <= 10; x++) for (int z = -1; z <= 6; z++)
            world.blocks.put(new BlockPos(x, 2, z), Blocks.OAK_LEAVES.defaultBlockState());
        check(corridor.hasContinuousCeiling(start, target, 2), "continuous leaves permit a head-hit short hop at arbitrary bearings");
        world.blocks.put(new BlockPos(4, 2, 2), Blocks.AIR.defaultBlockState());
        check(!corridor.hasContinuousCeiling(start, target, 2), "a hole in the canopy cannot authorize a shortened landing distance");
        check(!new GroundCorridor(world, p -> p.getX() < 3, .6, 1.8, LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY)
                .hasContinuousCeiling(start, target, 2), "unknown ceiling cells cannot authorize a jump");
        System.out.println("TravelRunwayTest: passed");
    }

    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
