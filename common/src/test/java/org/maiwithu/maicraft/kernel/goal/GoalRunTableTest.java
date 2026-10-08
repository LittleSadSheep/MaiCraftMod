// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.param.Params;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 目标运行表：下达与替换、请求键只下达一次、暂停与解除、取消、回答要选给出的选项、重启后恢复。 */
class GoalRunTableTest {

    private static final String ABILITY = "maicraft:test";
    private static final Goal GOAL = Goal.of(ABILITY, null, Params.EMPTY);
    private static final Question QUESTION = new Question(Question.Reason.CHOOSE_ONE, "动哪个箱子？",
            List.of(new Question.Option("b5", "门口那个"), new Question.Option("b6", "屋里那个")));

    private final GoalTestAbility ability = new GoalTestAbility(ABILITY);
    private final AbilityRegistry registry = new AbilityRegistry(new TaskFactories());
    private final InMemoryGoalRunStore store = new InMemoryGoalRunStore();
    private final ControlLoop loop = new ControlLoop(List.of());
    private final GoalRunTable table;
    private long tick = 100;

    GoalRunTableTest() {
        registry.register(ability);
        table = new GoalRunTable(registry, store, new GoalRunnerTest.TestMemory(), loop);
    }

    private void runTicks(int count) {
        for (int i = 0; i < count; i++) {
            loop.tick(new GoalTestTick(tick++));
        }
    }

    @Test
    void aNewGoalReplacesTheMainTask() {
        GoalRunner first = table.launch(GOAL, null).runner();
        GoalRunner second = table.launch(GOAL, null).runner();

        assertSame(second, loop.currentTask());
        assertFalse(first.run().unfinished());
        assertEquals(TaskResult.Status.CANCELLED, first.run().result().status());
    }

    @Test
    void theSameRequestKeyLaunchesOnlyOnce() {
        GoalRunTable.Launch first = table.launch(GOAL, "key-1");
        GoalRunTable.Launch again = table.launch(GOAL, "key-1");

        assertTrue(again.repeated());
        assertSame(first.runner(), again.runner());
        assertEquals(1, table.recent().size());
    }

    @Test
    void pausedGoalsStandStillUntilResumed() {
        GoalRunner runner = table.launch(GOAL, null).runner();
        long id = runner.run().id();
        table.pause(id);
        ability.next(new StepDecision.Finish(TaskResult.done("做完了")));

        runTicks(3);
        assertEquals(GoalRunState.PAUSED, runner.run().state());

        table.resume(id);
        runTicks(3);
        assertEquals(GoalRunState.FINISHED, runner.run().state());
    }

    @Test
    void cancellingEndsTheGoalAndTheLoopLetsGoOfIt() {
        long id = table.launch(GOAL, null).runner().run().id();

        table.cancel(id);
        runTicks(1);

        assertEquals(TaskResult.Status.CANCELLED, table.find(id).orElseThrow().result().status());
        assertNull(loop.currentTask());
        assertThrows(GoalRunTable.WrongGoalRunState.class, () -> table.cancel(id));
    }

    @Test
    void answersMustBeOneOfTheOfferedOptions() {
        GoalRunner runner = table.launch(GOAL, null).runner();
        long id = runner.run().id();
        ability.next(new StepDecision.Ask(QUESTION));
        runTicks(2);

        assertSame(QUESTION, table.pendingQuestion(id).orElseThrow());
        assertThrows(GoalRunTable.WrongGoalRunState.class, () -> table.answer(id, "b9"));
        table.answer(id, "b6");
        assertEquals(GoalRunState.RUNNING, runner.run().state());
        assertEquals(List.of("b6"), runner.run().answers());
    }

    @Test
    void unknownIdsAreReportedAsSuch() {
        assertThrows(GoalRunTable.UnknownGoalRun.class, () -> table.pause(42));
        assertTrue(table.find(42).isEmpty());
    }

    @Test
    void restoringBringsBackUnfinishedGoalsPausedAndSkipsSequenceSteps() {
        GoalRun interrupted = new GoalRun(store.nextId(), GOAL);
        store.save(interrupted);
        store.save(new GoalRun(store.nextId(), GOAL, interrupted.id()));

        table.restore();

        assertEquals(1, table.recent().size());
        assertEquals(GoalRunState.PAUSED, table.find(interrupted.id()).orElseThrow().state());
        assertNull(loop.currentTask());
        table.resume(interrupted.id());
        assertEquals(interrupted.id(), ((GoalRunner) loop.currentTask()).run().id());
    }

    @Test
    void onlyTheMostRecentFinishedGoalsAreKept() {
        long firstId = table.launch(GOAL, null).runner().run().id();
        for (int i = 0; i < GoalRunTable.KEPT_FINISHED + 5; i++) {
            table.launch(GOAL, null);
        }

        assertTrue(table.find(firstId).isEmpty());
        assertEquals(GoalRunTable.KEPT_FINISHED + 1, table.recent().size());
    }
}
