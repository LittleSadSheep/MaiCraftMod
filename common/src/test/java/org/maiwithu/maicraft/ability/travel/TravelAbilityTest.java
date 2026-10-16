// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.travel;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.explore.ColumnSample;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.travel.DestinationResolver;
import org.maiwithu.maicraft.behavior.travel.DimensionRoute;
import org.maiwithu.maicraft.behavior.travel.DimensionTravelServices;
import org.maiwithu.maicraft.behavior.travel.PearlCrossing;
import org.maiwithu.maicraft.behavior.travel.ReadsPlacedBlocks;
import org.maiwithu.maicraft.behavior.travel.TravelProgressListener;
import org.maiwithu.maicraft.behavior.travel.TravelDestination;
import org.maiwithu.maicraft.behavior.travel.TravelProgress;
import org.maiwithu.maicraft.behavior.travel.TravelTask;
import org.maiwithu.maicraft.behavior.travel.TravelWorldView;
import org.maiwithu.maicraft.behavior.travel.spi.OfferSink;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.behavior.travel.spi.TravelMode;
import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelRequest;
import org.maiwithu.maicraft.behavior.travel.spi.TravelVia;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TaskInput;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 出行能力：做决定时解析目的地（说不清就问一次），再把目的地、时限与许可凑成一次出行任务；能力本身不走路。 */
class TravelAbilityTest {

    private final StubWalks walks = new StubWalks();
    private final TravelAbility ability = new TravelAbility(walks,
            new DestinationResolver(new StubWorld(), name -> Optional.empty(), id -> Optional.empty()),
            List::of, progress -> {}, null);

    /** 推进一刻用的决定上下文：只有目标。 */
    private static StepContext step(Goal goal) {
        return new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                throw new IllegalStateException("决定阶段不应碰到每刻上下文");
            }
        };
    }

    /** 按能力自己的参数规格解析参数，得到与 MCP 入口一致的取值。 */
    private static ParamValues params(String json) {
        ParseResult result = new TravelAbility(new StubWalks(), null, List::of, progress -> {}, null)
                .spec().paramSpecs().parse(JsonParser.parseString(json).getAsJsonObject());
        assertTrue(result.ok(), "参数应能解析：" + result.errors());
        return result.params();
    }

    private Goal goal(Target target, String paramsJson) {
        return new Goal("maicraft:travel", null, target, params(paramsJson),
                Permissions.DEFAULT, List.of(), null);
    }

    @Test
    void specIsTravelWithToleranceAndTimeLimitParams() {
        assertEquals("maicraft:travel", ability.spec().id());
        assertEquals(2.0, params("{}").number("radius"), "到达容差默认 2 格");
        assertFalse(params("{}").has("max_seconds"), "不设时限时不给 max_seconds 取值");
    }

    @Test
    void decideRunsTravelTaskWithDefaults() {
        StepDecision decision = ability.decide(step(goal(
                new Target.Position(12, null, -2, null), "{}")));

        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decision);
        TravelInput input = assertInstanceOf(TravelInput.class, run.input());
        // 没给 y：只走到那一柱列，到了再找能站的格子。
        assertFalse(input.destination().heightConfirmed());
        assertEquals(2.0, input.destination().radius());
        assertEquals(0, input.maxSeconds(), "没给时限就不设时限");
        // 默认许可 natural：可以挖开天然方块、垫临时方块开路。
        assertEquals(TerrainPermit.NATURAL, input.permit());
    }

    @Test
    void decideCarriesToleranceTimeLimitAndPermission() {
        Goal request = goal(new Target.Position(5, 70, 5, null), "{\"radius\":0,\"max_seconds\":90}");
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, ability.decide(step(request)));
        TravelInput input = assertInstanceOf(TravelInput.class, run.input());
        assertEquals(0.0, input.destination().radius(), "给 0 表示必须站进那一格");
        assertEquals(90, input.maxSeconds());
    }

    @Test
    void tightRadiusAroundUnstandableTargetWidensToNearestStandableRing() {
        // 床边 1 格：四周不是墙就是床，一格能站的都没有；放宽到最近有能站的那一圈（2 格），不然走路找不到终点。
        StubWorld cramped = new StubWorld() {
            @Override public boolean standable(int x, int y, int z) {
                int dx = x - 5, dy = y - 70, dz = z - 5;
                return y == 70 && dx * dx + dy * dy + dz * dz >= 4;
            }
        };
        TravelAbility roomy = new TravelAbility(walks,
                new DestinationResolver(cramped, name -> Optional.empty(), id -> Optional.empty()),
                List::of, progress -> {}, null, cramped, List.of());
        Goal request = goal(new Target.Position(5, 70, 5, null), "{\"radius\":1}");
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, roomy.decide(step(request)));
        assertEquals(2.0, assertInstanceOf(TravelInput.class, run.input()).destination().radius());
    }

    @Test
    void noTerrainPermissionMeansWalkOnly() {
        Goal request = new Goal("maicraft:travel", null, new Target.Position(0, 64, 0, null),
                params("{}"), Permissions.DEFAULT.mergedWith(Permissions.BlockChanges.NONE,
                        null, null, null, null, null),
                List.of(), null);
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, ability.decide(step(request)));
        assertEquals(TerrainPermit.WALK_ONLY,
                assertInstanceOf(TravelInput.class, run.input()).permit(),
                "不许动地形就只走不改");
    }

    @Test
    void missingTargetEndsWithoutGuessing() {
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null, "{}"))));
        assertEquals(TaskResult.Status.FAILED, finish.result().status());
        assertEquals(Problem.Kind.UNSUPPORTED, finish.result().problem().kind());
    }

    @Test
    void factoryCreatesTravelTaskCarryingCollaborators() {
        TaskFactories factories = new TaskFactories();
        ability.registerTasks(factories);
        TravelInput input = new TravelInput(
                TravelDestination.confirmed(new WorldPosition(1, 2, 3, null), 2.0), 0, TerrainPermit.NATURAL);

        assertTrue(factories.supports(TravelInput.class));
        Task task = factories.create(input);
        assertInstanceOf(TravelTask.class, task);
        assertNotNull(task.describe());
        assertFalse(task.describe().isBlank());
    }

    @Test
    void unknownLandmarkIsAskedOnceThenEnds() {
        Goal request = goal(new Target.Landmark("粮仓"), "{}");
        // 没记过的地点：问一次，不拿脚边顶替。
        StepDecision.Ask ask = assertInstanceOf(StepDecision.Ask.class, ability.decide(step(request)));
        assertNotNull(ask.question());
        // 问过以后不再问同一个问题：选"这次不去"就取消，别的回答按说不清目的地结束。
        StepDecision.Finish gaveUp = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(answered(request, "give_up")));
        assertEquals(TaskResult.Status.CANCELLED, gaveUp.result().status());
        StepDecision.Finish unclear = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(answered(request, "tell_where")));
        assertEquals(Problem.Kind.NOT_FOUND, unclear.result().problem().kind());
    }

    @Test
    void hereEndsWithoutRunningATask() {
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(new Target.Here(), "{}"))));
        assertEquals(TaskResult.Status.DONE, finish.result().status());
    }

    @Test
    void hereMeansBackToOpenGround() {
        // 出行里 here 不带高度，就是脚下这一带头顶见天的地面：已在露天直接完成，在地下交给回露天任务。
        int[] top = {63};
        StubWorld world = new StubWorld() {
            @Override public ColumnSample column(int x, int z) {
                return new ColumnSample(true, "minecraft:plains", top[0], false, true, "minecraft:stone");
            }
        };
        TravelMode elevator = new TravelMode() {
            @Override public TravelVia via() {
                return new TravelVia("elevator", "坐 Create 电梯换楼层");
            }

            @Override public void offer(TravelRequest request, OfferSink sink) {}

            @Override public TravelLeg start(TravelRequest request, TravelOffer offer) {
                throw new AssertionError("决定阶段不开跑");
            }
        };
        TravelAbility surface = new TravelAbility(walks,
                new DestinationResolver(world, name -> Optional.empty(), id -> Optional.empty()),
                List::of, progress -> {}, null, world, List.of(elevator));

        StepDecision.Finish open = assertInstanceOf(StepDecision.Finish.class,
                surface.decide(step(goal(new Target.Here(), "{}"))));
        assertEquals(TaskResult.Status.DONE, open.result().status());
        assertTrue(open.result().summary().contains("露天"), open.result().summary());

        // 头顶还压着十几格石头：交给回露天任务，时限照带。
        top[0] = 80;
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                surface.decide(step(goal(new Target.Here(), "{\"max_seconds\":60}"))));
        assertEquals(60, assertInstanceOf(OpenSkyInput.class, run.input()).maxSeconds());

        // 回露天只走路和挖楼梯：指定坐电梯是参数自相矛盾。
        ParseResult viaElevator = surface.spec().paramSpecs().parse(JsonParser.parseString("{\"via\":\"elevator\"}")
                .getAsJsonObject());
        Goal wrong = new Goal("maicraft:travel", null, new Target.Here(), viaElevator.params(),
                Permissions.DEFAULT, List.of(), null);
        StepDecision.Finish clash = assertInstanceOf(StepDecision.Finish.class, surface.decide(step(wrong)));
        assertEquals(Problem.Kind.INVALID_PARAMETER, clash.result().problem().kind());
    }

    private static StepContext answered(Goal goal, String answer) {
        return new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                throw new IllegalStateException("决定阶段不应碰到每刻上下文");
            }

            @Override public List<String> answers() {
                return List.of(answer);
            }
        };
    }

    @Test
    void viaAppearsOnlyWithTravelModesAndRoutesToJourney() {
        // 装了出行方式才有 via；不给或指定别的方式交给行程，指定只走路照旧是出行任务。
        TravelMode elevator = new TravelMode() {
            @Override public TravelVia via() {
                return new TravelVia("elevator", "坐 Create 电梯换楼层");
            }

            @Override public void offer(TravelRequest request, OfferSink sink) {
                sink.decline(Problem.Kind.UNSUPPORTED, "没有电梯");
            }

            @Override public TravelLeg start(TravelRequest request, TravelOffer offer) {
                throw new AssertionError("决定阶段不开跑");
            }
        };
        TravelAbility withModes = new TravelAbility(walks,
                new DestinationResolver(new StubWorld(), name -> Optional.empty(), id -> Optional.empty()),
                List::of, progress -> {}, null, new StubWorld(), List.of(elevator));
        assertFalse(ability.spec().paramSpecs().parse(JsonParser.parseString("{\"via\":\"walk\"}")
                .getAsJsonObject()).ok(), "没有出行方式时参数表里没有 via");
        ParseResult parsed = withModes.spec().paramSpecs().parse(JsonParser.parseString("{\"via\":\"elevator\"}")
                .getAsJsonObject());
        assertTrue(parsed.ok(), "装了电梯就能指定 elevator：" + parsed.errors());

        Goal far = new Goal("maicraft:travel", null, new Target.Position(5, 120, 5, null), parsed.params(),
                Permissions.DEFAULT, List.of(), null);
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, withModes.decide(step(far)));
        JourneyInput journey = assertInstanceOf(JourneyInput.class, run.input());
        assertEquals("elevator", journey.journey().via());

        ParseResult walkOnly = withModes.spec().paramSpecs().parse(JsonParser.parseString("{\"via\":\"walk\"}")
                .getAsJsonObject());
        Goal walk = new Goal("maicraft:travel", null, new Target.Position(5, 120, 5, null), walkOnly.params(),
                Permissions.DEFAULT, List.of(), null);
        assertInstanceOf(TravelInput.class, assertInstanceOf(StepDecision.Run.class, withModes.decide(step(walk))).input());
    }

    @Test
    void modeOptionsPickTheModeAndAllowFloorOnlyTrips() {
        // 给了电梯的 floor 就是坐电梯：不给 target 只换楼层；via 给了别的就是参数对不上。
        TravelMode elevator = new TravelMode() {
            @Override public TravelVia via() {
                return new TravelVia("elevator", "坐 Create 电梯换楼层");
            }

            @Override public List<ParamSpec> options() {
                return List.of(ParamSpec.of("floor", ParamType.TEXT).doc("去哪一层").build());
            }

            @Override public void offer(TravelRequest request, OfferSink sink) {}

            @Override public TravelLeg start(TravelRequest request, TravelOffer offer) {
                throw new AssertionError("决定阶段不开跑");
            }
        };
        TravelAbility withModes = new TravelAbility(walks,
                new DestinationResolver(new StubWorld(), name -> Optional.empty(), id -> Optional.empty()),
                List::of, progress -> {}, null, new StubWorld(), List.of(elevator));
        ParseResult floorOnly = withModes.spec().paramSpecs().parse(JsonParser.parseString("{\"floor\":\"top\"}")
                .getAsJsonObject());
        assertTrue(floorOnly.ok(), floorOnly.errors().toString());
        Goal trip = new Goal("maicraft:travel", null, null, floorOnly.params(), Permissions.DEFAULT, List.of(), null);
        JourneyInput input = assertInstanceOf(JourneyInput.class,
                assertInstanceOf(StepDecision.Run.class, withModes.decide(step(trip))).input());
        assertEquals("elevator", input.journey().via());
        assertEquals(Map.of("floor", "top"), input.journey().options());

        ParseResult clash = withModes.spec().paramSpecs().parse(JsonParser.parseString(
                "{\"via\":\"walk\",\"floor\":\"top\"}").getAsJsonObject());
        Goal wrong = new Goal("maicraft:travel", null, new Target.Position(5, 120, 5, null), clash.params(),
                Permissions.DEFAULT, List.of(), null);
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, withModes.decide(step(wrong)));
        assertEquals(Problem.Kind.INVALID_PARAMETER, finish.result().problem().kind());
    }

    @Test
    void endGatewayPlansOnlyInTheEndAndGoesWithoutTarget() {
        // 折跃门原版自带、参数表里总有 via；主世界里没指定方式照旧直接走路，末地里交给行程（隔着虚空只能过门），
        // 只给 via=end_gateway 不给 target 也能下：过最近的一扇门。
        PearlCrossing gateway = new PearlCrossing(new DimensionTravelServices(null, null, null, null, null, null,
                null, null, null, null));
        TravelAbility overworld = standingIn(DimensionRoute.OVERWORLD, gateway);
        Goal far = new Goal("maicraft:travel", null, new Target.Position(5, 120, 5, null), params("{}"),
                Permissions.DEFAULT, List.of(), null);
        assertInstanceOf(TravelInput.class,
                assertInstanceOf(StepDecision.Run.class, overworld.decide(step(far))).input());

        TravelAbility end = standingIn(DimensionRoute.END, gateway);
        assertInstanceOf(JourneyInput.class, assertInstanceOf(StepDecision.Run.class, end.decide(step(far))).input());

        ParseResult alone = end.spec().paramSpecs().parse(JsonParser.parseString("{\"via\":\"end_gateway\"}")
                .getAsJsonObject());
        assertTrue(alone.ok(), alone.errors().toString());
        Goal hop = new Goal("maicraft:travel", null, null, alone.params(), Permissions.DEFAULT, List.of(), null);
        JourneyInput input = assertInstanceOf(JourneyInput.class,
                assertInstanceOf(StepDecision.Run.class, end.decide(step(hop))).input());
        assertEquals("end_gateway", input.journey().via());
        assertEquals(null, input.journey().destination(), "不给 target 就是过最近的门，到了对面就算到");
    }

    /** 站在某个维度原点、只登记了这几种出行方式的出行能力。 */
    private TravelAbility standingIn(String dimension, TravelMode... modes) {
        StubWorld world = new StubWorld() {
            @Override public WorldPosition currentSpot() {
                return new WorldPosition(0, 64, 0, dimension);
            }
        };
        return new TravelAbility(walks, new DestinationResolver(world, name -> Optional.empty(), id -> Optional.empty()),
                List::of, progress -> {}, null, world, List.of(modes));
    }

    /** 能力决定阶段不会开始走到；真走了说明薄皮越界。 */
    private static final class StubWalks implements WalkTo {
        @Override public WalkRun start(GoalCompiler.Compiled target, TerrainPermit permit) {
            throw new AssertionError("出行能力不应自己开始走到");
        }
    }

    /** 现场替身：角色站在原点。 */
    private static class StubWorld implements TravelWorldView {
        @Override public WorldPosition currentSpot() {
            return new WorldPosition(0, 64, 0, null);
        }

        @Override public Target.Toward currentFacing() {
            return Target.Toward.NORTH;
        }
    }
}
