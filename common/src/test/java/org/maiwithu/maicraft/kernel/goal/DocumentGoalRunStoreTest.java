// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Attempt;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 存在文档库里的目标运行：整条记录原样读回、编号跨重启只增不减、结束的拿掉、读不回的如实丢弃。 */
class DocumentGoalRunStoreTest {

    private static final String KEY = "c".repeat(64);

    @TempDir
    Path temp;

    /** 带几种参数的替身能力：读回时要按它的参数规格重新整理参数。 */
    private static final class ParamAbility implements AbilityModule {
        private final String id;

        ParamAbility(String id) {
            this.id = id;
        }

        @Override public AbilitySpec spec() {
            return new AbilitySpec(id, "带参数的替身", AbilityDoc.forAbility("test"),
                    ParamSpecs.of(
                            ParamSpec.of("count", ParamType.INTEGER).doc("挖几格").build(),
                            ParamSpec.of("radius", ParamType.NUMBER).doc("范围").build(),
                            ParamSpec.of("message", ParamType.TEXT).doc("收尾时说的话").build(),
                            ParamSpec.of("items", ParamType.TEXT_LIST).doc("要带的东西").build()),
                    Set.of(), ExecutionMode.CONTROLS_PLAYER, Set.of(), List.of(), Listing.LISTED);
        }

        @Override public StepDecision decide(StepContext step) {
            return StepDecision.NOT_READY;
        }
    }

    private static AbilityRegistry registry(String... ids) {
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories());
        for (String id : ids) registry.register(new ParamAbility(id));
        return registry;
    }

    private DocumentGoalRunStore store(AbilityRegistry registry) {
        return new DocumentGoalRunStore(new DocumentStore(temp.resolve("state.sqlite")), KEY, registry);
    }

    private static ParamValues params(AbilityRegistry registry, String json) {
        return registry.find("maicraft:dig").orElseThrow().spec().paramSpecs()
                .parse(JsonParser.parseString(json).getAsJsonObject()).params();
    }

    @Test
    void anUnfinishedSequenceComesBackWithEverythingItCarried() {
        AbilityRegistry registry = registry("maicraft:dig", "maicraft:sequence");
        DocumentGoalRunStore store = store(registry);
        Permissions careful = new Permissions(Permissions.BlockChanges.TEMPORARY, Permissions.Fight.SELF_DEFENSE,
                true, Permissions.AnimalKilling.NONE, Permissions.SurvivalNeeds.ON, Set.of("家", "麦田"));
        // 挖矿洞的三步：走到没给高度的坐标、朝北挖 20 格、在看到的箱子旁收尾；中间一步失败了接着做。
        Goal first = new Goal("maicraft:dig", "先到矿洞口", new Target.Position(40, null, -12, "minecraft:overworld"),
                params(registry, "{\"count\": 3, \"radius\": 2.5, \"message\": \"到了\", \"items\": [\"a\", \"b\"]}"),
                careful, List.of(), Goal.OnFailure.STOP);
        Goal second = new Goal("maicraft:dig", null, new Target.Direction(Target.Toward.NORTH, 20),
                ParamValues.EMPTY, null, List.of(), Goal.OnFailure.CONTINUE);
        Goal third = Goal.of("maicraft:dig", new Target.Seen("b5"), ParamValues.EMPTY);
        Goal sequence = new Goal("maicraft:sequence", "挖矿洞", null, ParamValues.EMPTY, careful,
                List.of(first, second, third), null);
        Question question = new Question(Question.Reason.CHOOSE_ONE, "动哪个箱子？",
                List.of(new Question.Option("b5", "门口那个"), new Question.Option("b6", "屋里那个")));
        // 第一步已经做完：走到时垫了一块泥土，结论随记录存着。
        TaskResult firstDone = TaskResult.builder(TaskResult.Status.DONE, "到了矿洞口")
                .change(new Change(Change.Kind.BLOCK_PLACED, "minecraft:dirt", 1, "路上垫的"))
                .attempt(new Attempt("绕开水坑", "绕过去了"))
                .build();
        GoalRun saved = GoalRun.fromSaved(store.nextId(), sequence, GoalRun.NO_PARENT, -1,
                GoalRunState.AWAITING_ANSWER, 1, question, List.of("b6"), List.of(firstDone), 120);
        store.save(saved);

        // 换一个存储实例读：等于重启后重新打开同一个库。
        GoalRun back = store(registry).unfinished().get(0);

        assertEquals(saved.id(), back.id());
        assertEquals(GoalRunState.AWAITING_ANSWER, back.state());
        assertEquals(1, back.stepIndex());
        assertEquals(question, back.question());
        assertEquals(List.of("b6"), back.answers());
        assertEquals(120, back.startedTick());
        assertEquals(firstDone, back.stepResults().get(0), "前面做完的步骤的结论和变化原样读回");
        Goal goal = back.goal();
        assertEquals("挖矿洞", goal.purpose());
        assertEquals(careful, goal.permissions());
        assertEquals(new Target.Position(40, null, -12, "minecraft:overworld"), goal.steps().get(0).target());
        assertEquals(new Target.Direction(Target.Toward.NORTH, 20), goal.steps().get(1).target());
        assertEquals(Goal.OnFailure.CONTINUE, goal.steps().get(1).onFailure());
        assertEquals(new Target.Seen("b5"), goal.steps().get(2).target());
        ParamValues kept = goal.steps().get(0).params();
        assertEquals(3, kept.integer("count"));
        assertEquals(2.5, kept.number("radius"));
        assertEquals("到了", kept.text("message"));
        assertEquals(List.of("a", "b"), kept.list("items"));
    }

    @Test
    void idsKeepGrowingAcrossRestartsAndFinishedRunsLeaveTheSave() {
        AbilityRegistry registry = registry("maicraft:dig");
        DocumentGoalRunStore store = store(registry);
        GoalRun done = new GoalRun(store.nextId(), Goal.of("maicraft:dig", new Target.Here(), ParamValues.EMPTY));
        GoalRun step = new GoalRun(store.nextId(), Goal.of("maicraft:dig", null, ParamValues.EMPTY), done.id(), 0);
        store.save(done);
        store.save(step);
        done.finish(TaskResult.done("挖完了"), 200);
        store.save(done);

        DocumentGoalRunStore reopened = store(registry);

        assertEquals(3, reopened.nextId(), "重启后编号接着往上数，不和以前的撞");
        List<GoalRun> unfinished = reopened.unfinished();
        assertEquals(1, unfinished.size());
        assertEquals(done.id(), unfinished.get(0).parentRunId());
        assertEquals(0, unfinished.get(0).stepOfParent());
        assertNull(unfinished.get(0).goal().target());
    }

    @Test
    void goalsWhoseAbilityIsGoneAreDroppedInsteadOfHalfRestored() {
        DocumentGoalRunStore before = store(registry("maicraft:dig", "maicraft:fly"));
        before.save(new GoalRun(before.nextId(), Goal.of("maicraft:fly", null, ParamValues.EMPTY)));
        before.save(new GoalRun(before.nextId(), Goal.of("maicraft:dig", null, ParamValues.EMPTY)));

        // 新版本里 fly 能力没了：它的目标读不回，不拿半份目标去推进，也从存盘里拿掉。
        DocumentGoalRunStore after = store(registry("maicraft:dig"));

        assertEquals(List.of(2L), after.unfinished().stream().map(GoalRun::id).toList());
        assertEquals(List.of(2L), store(registry("maicraft:dig", "maicraft:fly")).unfinished()
                .stream().map(GoalRun::id).toList());
        assertTrue(after.unfinished().stream().allMatch(GoalRun::unfinished));
    }
}
