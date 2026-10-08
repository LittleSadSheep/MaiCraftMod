// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.FakePlaces;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.FakeSeen;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.FakeWorld;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 目的地解析：坐标、记得的地点、观察编号、方向走多远各解析成什么样的导航要求。 */
class DestinationResolverTest {

    private final FakeWorld world = new FakeWorld();
    private final FakePlaces places = new FakePlaces();
    private final FakeSeen seen = new FakeSeen();
    private final DestinationResolver resolver = new DestinationResolver(world, places, seen);

    @Test
    void fullPositionBecomesExactOrNear() {
        var ready = ready(resolver.resolve(new Target.Position(10, 64, -30, null), 0));
        assertInstanceOf(NavGoal.Exact.class, ready.navGoal(), "容差为 0 时要站进那一格");

        var withRadius = ready(resolver.resolve(new Target.Position(10, 64, -30, null), 2));
        NavGoal.Near near = assertInstanceOf(NavGoal.Near.class, withRadius.navGoal());
        assertEquals(2, near.radius);
    }

    @Test
    void positionWithoutHeightWalksTheColumn() {
        // 高度纪律：没给 y 就不猜高度，先走到那一柱列，到了再找能站的格子。
        var ready = ready(resolver.resolve(new Target.Position(10, null, -30, null), 2));
        NavGoal.Column column = assertInstanceOf(NavGoal.Column.class, ready.navGoal());
        assertEquals(10, column.x);
        assertEquals(-30, column.z);
        assertEquals(2, column.radius);
    }

    @Test
    void rememberedLandmarkResolvesToItsPlace() {
        places.put("家", new WorldPosition(120, 70, -80, null));
        var ready = ready(resolver.resolve(new Target.Landmark("家"), 2));
        assertTrue(ready.navGoal().isAt(new BlockPos(120, 70, -80)));
    }

    @Test
    void unknownLandmarkAsksInsteadOfSubstituting() {
        // 名字记不得就提问，不拿脚边顶替，也不猜成别的地方。
        var unclear = assertInstanceOf(DestinationResolver.Resolution.Unclear.class,
                resolver.resolve(new Target.Landmark("粮仓"), 2));
        assertEquals(Question.Reason.UNCLEAR_TARGET, TravelFakes.reasonOf(unclear.question()));
    }

    @Test
    void seenIdPointsAtWhereItWasSeen() {
        seen.put("f3", new WorldPosition(40, 65, 22, null));
        var ready = ready(resolver.resolve(new Target.Seen("f3"), 2));
        assertTrue(ready.navGoal().isAt(new BlockPos(40, 65, 22)));
    }

    @Test
    void staleSeenIdEndsWithTargetGone() {
        var deadEnd = assertInstanceOf(DestinationResolver.Resolution.DeadEnd.class,
                resolver.resolve(new Target.Seen("f9"), 2));
        assertEquals(Problem.Kind.TARGET_GONE, TravelFakes.kindOf(deadEnd.problem()));
    }

    @Test
    void otherDimensionIsUnsupported() {
        var deadEnd = assertInstanceOf(DestinationResolver.Resolution.DeadEnd.class,
                resolver.resolve(new Target.Position(0, 64, 0, "minecraft:the_nether"), 2));
        assertEquals(Problem.Kind.UNSUPPORTED, TravelFakes.kindOf(deadEnd.problem()));
    }

    @Test
    void compassDirectionLandsAtTheRightColumn() {
        // 角色在原点朝北：往北 100 格落在 (0, -100)，往东 30 格落在 (30, 0)。
        var north = ready(resolver.resolve(new Target.Direction(Target.Toward.NORTH, 100), 2));
        NavGoal.Column northColumn = assertInstanceOf(NavGoal.Column.class, north.navGoal());
        assertEquals(0, northColumn.x);
        assertEquals(-100, northColumn.z);

        world.facing = Target.Toward.NORTH;
        var forward = ready(resolver.resolve(new Target.Direction(Target.Toward.FORWARD, 20), 2));
        NavGoal.Column forwardColumn = assertInstanceOf(NavGoal.Column.class, forward.navGoal());
        assertEquals(0, forwardColumn.x);
        assertEquals(-20, forwardColumn.z, "朝北时前方就是北");

        world.facing = Target.Toward.EAST;
        var left = ready(resolver.resolve(new Target.Direction(Target.Toward.LEFT, 10), 2));
        NavGoal.Column leftColumn = assertInstanceOf(NavGoal.Column.class, left.navGoal());
        assertEquals(0, leftColumn.x, "朝东时左手边是北");
        assertEquals(-10, leftColumn.z);
    }

    @Test
    void hereMeansAlreadyThere() {
        var already = assertInstanceOf(DestinationResolver.Resolution.AlreadyThere.class,
                resolver.resolve(new Target.Here(), 2));
        assertEquals(WorldPosition.here(0, 64, 0), already.spot());
    }

    @Test
    void negativeRadiusIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TravelDestination.confirmed(WorldPosition.here(0, 64, 0), -1));
    }

    private TravelDestination ready(DestinationResolver.Resolution resolution) {
        return assertInstanceOf(DestinationResolver.Resolution.Ready.class, resolution).destination();
    }
}
