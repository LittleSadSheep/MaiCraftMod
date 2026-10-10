// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.remember;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.ReadsCharacterPosition;
import org.maiwithu.maicraft.behavior.travel.ReadsSeenTargets;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 记地点能力：目标对象落成带维度的位置、同名覆盖、忘掉没记过的名字如实失败。 */
class RememberAbilityTest {

    /** 能决定阶段用的上下文：只有目标。 */
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

    /** 本刻上下文替身：刻号由测试推进。 */
    private static final class TestTick implements TickContext {
        private long gameTick = 1000;

        @Override public long gameTick() {
            return gameTick;
        }

        @Override public PlayerContext player() {
            throw new IllegalStateException("记地点测试不应碰到角色对象");
        }

        void advance() {
            gameTick++;
        }
    }

    @TempDir
    Path temp;

    private WorldMemory memory;
    private RememberAbility ability;

    @BeforeEach
    void world() {
        memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "a".repeat(64));
        // 角色站在主世界 (12, 64, -8)；观察编号 e7 指着 (0, 64, -12)。
        ability = new RememberAbility(memory,
                () -> new WorldPosition(12, 64, -8, "minecraft:overworld"),
                id -> "e7".equals(id)
                        ? Optional.of(new WorldPosition(0, 64, -12, "minecraft:overworld"))
                        : Optional.empty());
    }

    private ParamValues params(String json) {
        ParseResult result = ability.spec().paramSpecs()
                .parse(JsonParser.parseString(json).getAsJsonObject());
        assertTrue(result.ok(), "参数应能解析：" + result.errors());
        return result.params();
    }

    private Goal goal(Target target, String parametersJson) {
        return new Goal("maicraft:remember", null, target, params(parametersJson), null, List.of(), null);
    }

    /** 推进决定开出的任务到结束，让记忆真的写进去。 */
    private TaskResult runToFinish(StepDecision.Run run) {
        RememberPlaceTask task = new RememberPlaceTask(
                (RememberPlaceInput) run.input(), memory);
        TestTick tick = new TestTick();
        task.start(tick);
        TickResult result = task.tick(tick);
        return assertInstanceOf(TickResult.Finished.class, result).result();
    }

    @Test
    void specIsMemoryOnlyAndNeedsAName() {
        assertEquals("maicraft:remember", ability.spec().id());
        assertEquals(ExecutionMode.MEMORY_ONLY, ability.spec().mode());
        assertFalse(ability.spec().paramSpecs().parse(new JsonObject()).ok(), "缺 name 应报错");
    }

    @Test
    void hereIsRecordedWithDimension() {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(null, "{\"name\":\"家\"}"))));
        RememberPlaceInput input = assertInstanceOf(RememberPlaceInput.class, run.input());
        assertEquals(new WorldPosition(12, 64, -8, "minecraft:overworld"), input.position());

        TaskResult result = runToFinish(run);
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(Optional.of(input.position()), memory.place("家"));
    }

    @Test
    void recordingTheSameSpotAgainFinishesWithoutWriting() {
        memory.remember("家", new WorldPosition(12, 64, -8, "minecraft:overworld"));

        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null, "{\"name\":\" 家 \"}"))));
        assertEquals(TaskResult.Status.DONE, finish.result().status());
        assertTrue(finish.result().summary().contains("本来就这样记着"));
    }

    @Test
    void overwritingReportsWhereItWasBefore() {
        memory.remember("家", new WorldPosition(0, 64, 0, "minecraft:overworld"));

        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(new Target.Position(12, 64, -8, null),
                        "{\"name\":\"家\"}"))));
        RememberPlaceInput input = assertInstanceOf(RememberPlaceInput.class, run.input());
        assertEquals(new WorldPosition(0, 64, 0, "minecraft:overworld"), input.previous());

        runToFinish(run);
        assertEquals(Optional.of(new WorldPosition(12, 64, -8, "minecraft:overworld")),
                memory.place("家"));
    }

    @Test
    void coordinateWithoutHeightIsRefused() {
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(new Target.Position(12, null, -8, null),
                        "{\"name\":\"矿洞\"}"))));
        assertEquals(TaskResult.Status.FAILED, finish.result().status());
        assertEquals(Problem.Kind.INVALID_PARAMETER, finish.result().problem().kind());
        assertTrue(memory.place("矿洞").isEmpty(), "没记上就不该有这条");
    }

    @Test
    void seenTargetUsesTheLastSeenPosition() {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(new Target.Seen("e7"), "{\"name\":\"僵尸出现的地儿\"}"))));
        assertEquals(new WorldPosition(0, 64, -12, "minecraft:overworld"),
                assertInstanceOf(RememberPlaceInput.class, run.input()).position());
    }

    @Test
    void aGoneObservationIsReportedAsSuch() {
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(new Target.Seen("e9"), "{\"name\":\"没了的\"}"))));
        assertEquals(Problem.Kind.TARGET_GONE, finish.result().problem().kind());
    }

    @Test
    void landmarkRenamesAnAlreadyRememberedPlace() {
        memory.remember("家", new WorldPosition(12, 64, -8, "minecraft:overworld"));

        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(new Target.Landmark("家"), "{\"name\":\"门口\"}"))));
        runToFinish(run);
        assertEquals(memory.place("家"), memory.place("门口"));
    }

    @Test
    void forgettingRemovesOnlyTheNamedPlace() {
        memory.remember("家", new WorldPosition(12, 64, -8, "minecraft:overworld"));
        memory.remember("矿洞", new WorldPosition(0, 64, -12, "minecraft:overworld"));

        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                ability.decide(step(goal(null, "{\"name\":\"家\",\"operation\":\"forget\"}"))));
        runToFinish(run);
        assertTrue(memory.place("家").isEmpty());
        assertEquals(Optional.of(new WorldPosition(0, 64, -12, "minecraft:overworld")),
                memory.place("矿洞"), "只忘名字对得上的那一个");
    }

    @Test
    void forgettingAnUnknownNameFailsWithAllRememberedNames() {
        memory.remember("矿洞", new WorldPosition(0, 64, -12, "minecraft:overworld"));

        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                ability.decide(step(goal(null, "{\"name\":\"家\",\"operation\":\"forget\"}"))));
        assertEquals(TaskResult.Status.FAILED, finish.result().status());
        assertEquals(Problem.Kind.NOT_FOUND, finish.result().problem().kind());
        assertTrue(finish.result().problem().message().contains("矿洞"),
                "失败要说清记得的地点都有什么：" + finish.result().problem().message());
    }
}
