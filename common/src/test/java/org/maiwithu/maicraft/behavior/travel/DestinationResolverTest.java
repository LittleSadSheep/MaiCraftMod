// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 目的地解析：坐标、记得的地点、观察编号、方向走多远各解析成什么样的导航要求。 */
class DestinationResolverTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

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
    void positionWithoutHeightLandsOnTheSurfaceWhenLoaded() {
        // 实机（全量测试 10-12）：只给 x/z 去"出坑回地面"，同一列的坑底就算到了。那一列加载着就落到地表。
        world.surface = 147;
        var ready = ready(resolver.resolve(new Target.Position(10, null, -30, null), 0));
        assertTrue(ready.navGoal().isAt(new BlockPos(10, 147, -30)), "站到地表那一格");
        assertFalse(ready.navGoal().isAt(new BlockPos(10, 143, -30)), "同一列的坑底不算到");
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
    void unrememberedLandmarkGoesToTheSignReadingIt() {
        // 没记过"仓库"，但抬头看得见一块写着"仓库"的路牌：朝那块牌子去。
        seen.sign("b4", WorldPosition.here(12, 64, -5), 13, "→ 仓库");
        var ready = ready(resolver.resolve(new Target.Landmark("仓库"), 2));
        assertTrue(ready.navGoal().isAt(new BlockPos(12, 64, -5)));
    }

    @Test
    void twoSignsFarApartAskWhichOne() {
        // 两块隔得老远的"仓库"：不替玩家挑，提问里列出两块的编号，让它用 seen 指一块。
        seen.sign("b4", WorldPosition.here(12, 64, -5), 13, "仓库");
        seen.sign("b9", WorldPosition.here(-40, 64, 0), 40, "仓库");
        var unclear = assertInstanceOf(DestinationResolver.Resolution.Unclear.class,
                resolver.resolve(new Target.Landmark("仓库"), 2));
        assertEquals(Question.Reason.UNCLEAR_TARGET, TravelFakes.reasonOf(unclear.question()));
        assertTrue(unclear.question().text().contains("b4") && unclear.question().text().contains("b9"),
                unclear.question().text());
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
    void moddedDimensionPositionEndsWithUnsupported() {
        // 模组维度没有原版门：如实说不支持，不开始一段走不到的行程。
        var deadEnd = assertInstanceOf(DestinationResolver.Resolution.DeadEnd.class,
                resolver.resolve(new Target.Position(0, 64, 0, "somemod:custom_dim"), 2));
        assertEquals(Problem.Kind.UNSUPPORTED, TravelFakes.kindOf(deadEnd.problem()));
    }

    @Test
    void dimensionTargetResolvesToACrossDimensionJourney() {
        // "就是去那个维度"：主世界出发去下界，路由到下界传送门。
        world.spot = new WorldPosition(0, 64, 0, "minecraft:overworld");
        var cross = assertInstanceOf(DestinationResolver.Resolution.CrossDimension.class,
                resolver.resolve(new Target.Dimension("minecraft:the_nether"), 2));
        assertEquals("minecraft:the_nether", cross.targetDimension());
        assertNull(cross.destination(), "不带坐标就是只去维度");
    }

    @Test
    void dimensionTargetAlreadyThereFinishesAtOnce() {
        // 已经在下界再去下界：没有跨维度段，按已满足结束。
        world.spot = new WorldPosition(0, 64, 0, "minecraft:the_nether");
        var already = assertInstanceOf(DestinationResolver.Resolution.AlreadyThere.class,
                resolver.resolve(new Target.Dimension("minecraft:the_nether"), 2));
        assertEquals("minecraft:the_nether", already.spot().dimension());
        world.spot = new WorldPosition(0, 64, 0, null);
    }

    @Test
    void positionInAnotherDimensionCarriesItsDestination() {
        world.spot = new WorldPosition(0, 64, 0, "minecraft:overworld");
        // "那个维度的这个坐标"：跨完接着走到坐标，坐标按目标维度的坐标系写。
        var cross = assertInstanceOf(DestinationResolver.Resolution.CrossDimension.class,
                resolver.resolve(new Target.Position(100, 40, -30, "minecraft:the_nether"), 2));
        assertEquals("minecraft:the_nether", cross.targetDimension());
        assertEquals(100, cross.destination().position().x());
        assertEquals(40, cross.destination().position().y());
    }

    @Test
    void rememberedPlaceInAnotherDimensionCrossesToIt() {
        // 记过的地点带维度（记的时候人在下界）：照着那个维度走。
        world.spot = new WorldPosition(0, 64, 0, "minecraft:overworld");
        places.put("堡垒", new WorldPosition(120, 70, -80, "minecraft:the_nether"));
        var cross = assertInstanceOf(DestinationResolver.Resolution.CrossDimension.class,
                resolver.resolve(new Target.Landmark("堡垒"), 2));
        assertEquals("minecraft:the_nether", cross.targetDimension());
        assertNotNull(cross.destination());
    }

    @Test
    void dimensionPairsWithoutPortalsEndWithReasons() {
        // 下界与末地之间没有原版门：建议经主世界中转；模组维度如实说不支持。
        world.spot = new WorldPosition(0, 64, 0, "minecraft:the_nether");
        var hub = assertInstanceOf(DestinationResolver.Resolution.DeadEnd.class,
                resolver.resolve(new Target.Dimension("minecraft:the_end"), 2));
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, TravelFakes.kindOf(hub.problem()));
        assertTrue(hub.problem().suggestion().contains("主世界"));

        var modded = assertInstanceOf(DestinationResolver.Resolution.DeadEnd.class,
                resolver.resolve(new Target.Dimension("somemod:custom"), 2));
        assertEquals(Problem.Kind.UNSUPPORTED, TravelFakes.kindOf(modded.problem()));
        world.spot = new WorldPosition(0, 64, 0, null);
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
