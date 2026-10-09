// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.travel;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.travel.DestinationResolver;
import org.maiwithu.maicraft.behavior.travel.ReadsPlacedBlocks;
import org.maiwithu.maicraft.behavior.travel.TravelProgressListener;
import org.maiwithu.maicraft.behavior.travel.TravelDestination;
import org.maiwithu.maicraft.behavior.travel.TravelProgress;
import org.maiwithu.maicraft.behavior.travel.TravelTask;
import org.maiwithu.maicraft.behavior.travel.TravelWorldView;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
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
            List::of, progress -> {});

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
        ParseResult result = new TravelAbility(new StubWalks(), null, List::of, progress -> {})
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

    /** 能力决定阶段不会开始走到；真走了说明薄皮越界。 */
    private static final class StubWalks implements WalkTo {
        @Override public WalkRun start(GoalCompiler.Compiled target, TerrainPermit permit) {
            throw new AssertionError("出行能力不应自己开始走到");
        }
    }

    /** 现场替身：角色站在原点。 */
    private record StubWorld() implements TravelWorldView {
        @Override public WorldPosition currentSpot() {
            return new WorldPosition(0, 64, 0, null);
        }

        @Override public Target.Toward currentFacing() {
            return Target.Toward.NORTH;
        }
    }
}
