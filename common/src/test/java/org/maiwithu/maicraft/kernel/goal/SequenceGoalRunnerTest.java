// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.goal.GoalTestAbility.SharedTasks;
import org.maiwithu.maicraft.kernel.goal.GoalTestAbility.TestInput;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TaskInput;
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sequence 多步目标：每一步当作一个完整的目标推进逐步跑；某步失败按 onFailure 决定停下还是继续；
 * 中断恢复时接着用它自己的步骤记录；LLM 的回答转交给正在跑的子目标。
 */
class SequenceGoalRunnerTest {

    private static final GoalTestTick TICK0 = new GoalTestTick(100);

    private static Goal stepOf(String ability) {
        return Goal.of(ability, null, null);
    }

    private static GoalRunner launch(Goal goal, GoalRunStore store, GoalTestAbility... abilities) {
        TaskFactories factories = new TaskFactories();
        AbilityRegistry registry = new AbilityRegistry(factories);
        for (GoalTestAbility ability : abilities) {
            registry.register(ability);
        }
        return GoalRunner.launch(goal, registry, store, new GoalRunnerTest.TestMemory());
    }

    private static List<String> labels(List<TaskInput> started) {
        return started.stream().map(input -> ((TestInput) input).label()).toList();
    }

    private static TaskResult drive(GoalRunner runner, int maxTicks) {
        long tick = 100;
        runner.start(new GoalTestTick(tick++));
        TickResult tickResult = TickResult.RUNNING;
        for (int i = 0; i < maxTicks && tickResult instanceof TickResult.Running; i++) {
            tickResult = runner.tick(new GoalTestTick(tick++));
        }
        return assertInstanceOf(TickResult.Finished.class, tickResult).result();
    }

    @Test
    void sequenceRunsStepsInOrderAndFinishesDone() {
        SharedTasks tasks = new SharedTasks();
        GoalTestAbility first = new GoalTestAbility("maicraft:dig", tasks);
        first.next(new StepDecision.Run(new TestInput("挖坑")));
        GoalTestAbility second = new GoalTestAbility("maicraft:build", tasks);
        second.next(new StepDecision.Run(new TestInput("搭房")));
        InMemoryGoalRunStore store = new InMemoryGoalRunStore();
        Goal sequence = new Goal("maicraft:sequence", "先挖坑再搭房", null, null, null,
                List.of(stepOf("maicraft:dig"), stepOf("maicraft:build")), Goal.OnFailure.STOP);
        GoalRunner runner = launch(sequence, store, first, second);

        TaskResult result = drive(runner, 20);

        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals("按顺序做完了全部 2 步", result.summary());
        assertEquals(List.of("挖坑", "搭房"), labels(first.startedInputs()), "每步各做各的，先后不乱");
        assertTrue(store.unfinished().isEmpty());
    }

    @Test
    void stepFailureWithStopFailsTheWholeSequence() {
        SharedTasks tasks = new SharedTasks();
        GoalTestAbility first = new GoalTestAbility("maicraft:dig", tasks);
        first.ending("挖坑", TaskResult.failed("下面是基岩", Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "这里挖不动")));
        first.next(new StepDecision.Run(new TestInput("挖坑")));
        GoalTestAbility second = new GoalTestAbility("maicraft:build", tasks);
        InMemoryGoalRunStore store = new InMemoryGoalRunStore();
        Goal sequence = new Goal("maicraft:sequence", null, null, null, null,
                List.of(stepOf("maicraft:dig"), stepOf("maicraft:build")), Goal.OnFailure.STOP);
        GoalRunner runner = launch(sequence, store, first, second);

        TaskResult result = drive(runner, 20);

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, result.problem().kind());
        assertEquals(List.of("挖坑"), labels(first.startedInputs()), "停下就不做后面的步骤");
    }

    @Test
    void stepFailureWithContinueEndsPartial() {
        SharedTasks tasks = new SharedTasks();
        GoalTestAbility first = new GoalTestAbility("maicraft:dig", tasks);
        first.ending("剪羊毛", TaskResult.failed("羊都跑光了",
                Problem.of(Problem.Kind.NEED_ITEM, "附近没有羊，剪不到羊毛")));
        first.next(new StepDecision.Run(new TestInput("剪羊毛")));
        GoalTestAbility second = new GoalTestAbility("maicraft:build", tasks);
        second.next(new StepDecision.Run(new TestInput("搭房")));
        InMemoryGoalRunStore store = new InMemoryGoalRunStore();
        Goal sequence = new Goal("maicraft:sequence", null, null, null, null,
                List.of(stepOf("maicraft:dig"), stepOf("maicraft:build")), Goal.OnFailure.CONTINUE);
        GoalRunner runner = launch(sequence, store, first, second);

        TaskResult result = drive(runner, 20);

        assertEquals(TaskResult.Status.PARTIAL, result.status());
        assertEquals(List.of("羊都跑光了"), result.remaining(), "没做成的步骤写进 remaining");
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind(), "第一个失败写进问题");
        assertEquals(List.of("剪羊毛", "搭房"), labels(first.startedInputs()), "继续做后面的步骤");
    }

    @Test
    void restartResumesTheStepFromItsOwnRun() {
        SharedTasks tasks = new SharedTasks();
        GoalTestAbility first = new GoalTestAbility("maicraft:dig", tasks);
        first.next(new StepDecision.Ask(new Question(Question.Reason.CHOOSE_ONE, "往哪边挖？",
                List.of(new Question.Option("w", "西边")))));
        first.next(new StepDecision.Run(new TestInput("挖坑")));
        GoalTestAbility second = new GoalTestAbility("maicraft:build", tasks);
        second.next(new StepDecision.Run(new TestInput("搭房")));
        InMemoryGoalRunStore store = new InMemoryGoalRunStore();
        Goal sequence = new Goal("maicraft:sequence", null, null, null, null,
                List.of(stepOf("maicraft:dig"), stepOf("maicraft:build")), Goal.OnFailure.STOP);
        GoalRunner runner = launch(sequence, store, first, second);
        runner.start(TICK0);
        runner.tick(TICK0);
        runner.tick(new GoalTestTick(101));
        // 第一步挂起提问时"重启"：还没结束的记录是两条（sequence 自己和它的第一步）。
        assertEquals(2, store.unfinished().size());

        TaskFactories factories = new TaskFactories();
        AbilityRegistry registry = new AbilityRegistry(factories);
        registry.register(first);
        registry.register(second);
        GoalRun restoredSequence = store.unfinished().stream()
                .filter(run -> run.parentRunId() == -1).findFirst().orElseThrow();
        GoalRunner revived = GoalRunner.resume(restoredSequence, registry, store, new GoalRunnerTest.TestMemory());
        revived.unpause();
        revived.answer("w");
        // 第一步的问题由子目标接着回答，第二步照常跑。
        TaskResult result = drive(revived, 20);
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(List.of("w"), first.answersSeen, "回答转交给了正在跑的子目标");
        assertEquals(List.of("挖坑", "搭房"), labels(first.startedInputs()));
        assertTrue(store.unfinished().isEmpty());
    }
}
