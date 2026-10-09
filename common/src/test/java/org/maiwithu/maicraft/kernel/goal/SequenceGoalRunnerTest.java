// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.goal.GoalTestAbility.SharedTasks;
import org.maiwithu.maicraft.kernel.goal.GoalTestAbility.TestInput;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TaskInput;
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sequence 多步目标：每一步当作一个完整的目标推进逐步跑；某步失败按 onFailure 决定停下还是继续；
 * 中断恢复时接着用它自己的步骤记录；LLM 的回答转交给正在跑的子目标；各步骤发生的事并进整件事的结果。
 */
class SequenceGoalRunnerTest {

    private static final GoalTestTick TICK0 = new GoalTestTick(100);

    private static Goal stepOf(String ability) {
        return Goal.of(ability, null, null);
    }

    /** 带自己的 onFailure 的一步：这一步失败后整件事停下还是继续，由这一步自己说。 */
    private static Goal stepOf(String ability, Goal.OnFailure onFailure) {
        return new Goal(ability, null, null, null, null, List.of(), onFailure);
    }

    private static Goal sequenceOf(Goal... steps) {
        return new Goal("maicraft:sequence", null, null, null, null, List.of(steps), null);
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
                List.of(stepOf("maicraft:dig", Goal.OnFailure.CONTINUE), stepOf("maicraft:build")), null);
        GoalRunner runner = launch(sequence, store, first, second);

        TaskResult result = drive(runner, 20);

        assertEquals(TaskResult.Status.PARTIAL, result.status());
        assertEquals(List.of("第 1 步（maicraft:dig）：羊都跑光了"), result.remaining(), "没做成的步骤写进 remaining");
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
        GoalRunner revived = GoalRunner.restore(restoredSequence, registry, store, new GoalRunnerTest.TestMemory());
        revived.resumeGoal();
        revived.answer("w");
        // 第一步的问题由子目标接着回答，第二步照常跑。
        TaskResult result = drive(revived, 20);
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(List.of("w"), first.answersSeen, "回答转交给了正在跑的子目标");
        assertEquals(List.of("挖坑", "搭房"), labels(first.startedInputs()));
        assertTrue(store.unfinished().isEmpty());
    }
    @Test
    void eachStepDecidesForItselfWhetherAFailureStopsTheSequence() {
        // 第一步允许失败、第二步不允许：第一步没做成照样往下走，第二步做成，整件事部分完成。
        SharedTasks tasks = new SharedTasks();
        GoalTestAbility first = new GoalTestAbility("maicraft:dig", tasks);
        first.ending("挖坑", TaskResult.failed("下面是基岩", Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "挖不动")));
        first.next(new StepDecision.Run(new TestInput("挖坑")));
        GoalTestAbility second = new GoalTestAbility("maicraft:build", tasks);
        second.next(new StepDecision.Run(new TestInput("搭房")));
        Goal sequence = sequenceOf(stepOf("maicraft:dig", Goal.OnFailure.CONTINUE),
                stepOf("maicraft:build", Goal.OnFailure.STOP));
        GoalRunner runner = launch(sequence, new InMemoryGoalRunStore(), first, second);

        TaskResult result = drive(runner, 20);

        assertEquals(TaskResult.Status.PARTIAL, result.status());
        assertEquals(List.of("挖坑", "搭房"), labels(first.startedInputs()));
    }

    @Test
    void whatEachStepDidEndsUpInTheSequenceResult() {
        SharedTasks tasks = new SharedTasks();
        GoalTestAbility first = new GoalTestAbility("maicraft:dig", tasks);
        first.ending("挖坑", TaskResult.builder(TaskResult.Status.DONE, "挖好了")
                .change(Change.of(Change.Kind.BLOCK_BROKEN, "minecraft:dirt", 4)).build());
        first.next(new StepDecision.Run(new TestInput("挖坑")));
        GoalTestAbility second = new GoalTestAbility("maicraft:build", tasks);
        second.ending("搭房", TaskResult.failed("木板不够", Problem.of(Problem.Kind.NEED_ITEM, "还缺 6 块木板")));
        second.next(new StepDecision.Run(new TestInput("搭房")));
        GoalRunner runner = launch(sequenceOf(stepOf("maicraft:dig"), stepOf("maicraft:build")),
                new InMemoryGoalRunStore(), first, second);

        TaskResult result = drive(runner, 20);

        assertEquals(TaskResult.Status.PARTIAL, result.status(), "第一步做成了，整件事算部分完成");
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
        assertEquals("minecraft:dirt", result.changes().get(0).what(), "挖坑的变化留在整件事的结果里");
    }

    @Test
    void restoredStepKeepsWaitingForItsAnswerEvenIfTickedFirst() {
        // 重启后先恢复推进、过了几刻 LLM 才回答：那一步的问题照样挂着，回答到了接着走，不另起一条记录。
        SharedTasks tasks = new SharedTasks();
        GoalTestAbility first = new GoalTestAbility("maicraft:dig", tasks);
        first.next(new StepDecision.Ask(new Question(Question.Reason.CHOOSE_ONE, "往哪边挖？",
                List.of(new Question.Option("w", "西边")))));
        first.next(new StepDecision.Run(new TestInput("挖坑")));
        InMemoryGoalRunStore store = new InMemoryGoalRunStore();
        GoalRunner runner = launch(sequenceOf(stepOf("maicraft:dig")), store, first);
        runner.start(TICK0);
        runner.tick(TICK0);
        runner.tick(new GoalTestTick(101));
        long stepRunId = store.unfinished().stream()
                .filter(run -> run.parentRunId() != GoalRun.NO_PARENT).findFirst().orElseThrow().id();

        GoalRunner revived = restoreSequence(store, first);
        revived.resumeGoal();
        revived.start(new GoalTestTick(200));
        for (int tick = 201; tick < 205; tick++) {
            assertEquals(TickResult.RUNNING, revived.tick(new GoalTestTick(tick)));
        }
        assertEquals("往哪边挖？", revived.pendingQuestion().text(), "问题还挂着");
        revived.answer("w");
        assertEquals(stepRunId, store.unfinished().stream()
                .filter(run -> run.parentRunId() != GoalRun.NO_PARENT).findFirst().orElseThrow().id(),
                "沿用重启前的那条步骤记录");

        TaskResult result = resultOf(revived, 10);
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(List.of("w"), first.answersSeen);
        assertTrue(store.unfinished().isEmpty());
    }

    @Test
    void answerToAStepNotYetPulledUpIsSavedToThatStep() {
        SharedTasks tasks = new SharedTasks();
        GoalTestAbility first = new GoalTestAbility("maicraft:dig", tasks);
        first.next(new StepDecision.Ask(new Question(Question.Reason.CHOOSE_ONE, "往哪边挖？",
                List.of(new Question.Option("w", "西边")))));
        List<Long> saved = new ArrayList<>();
        InMemoryGoalRunStore inner = new InMemoryGoalRunStore();
        GoalRunStore store = new GoalRunStore() {
            @Override public long nextId() {
                return inner.nextId();
            }

            @Override public void save(GoalRun run) {
                saved.add(run.id());
                inner.save(run);
            }

            @Override public List<GoalRun> unfinished() {
                return inner.unfinished();
            }
        };
        GoalRunner runner = launch(sequenceOf(stepOf("maicraft:dig")), store, first);
        runner.start(TICK0);
        runner.tick(TICK0);
        runner.tick(new GoalTestTick(101));
        GoalRun step = store.unfinished().stream()
                .filter(run -> run.parentRunId() != GoalRun.NO_PARENT).findFirst().orElseThrow();
        GoalRunner revived = restoreSequence(store, first);
        saved.clear();

        revived.answer("w");

        assertEquals(List.of(step.id()), saved, "回答存进那一步自己的记录，不是存 sequence 的");
    }

    private static GoalRunner restoreSequence(GoalRunStore store, GoalTestAbility... abilities) {
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories());
        for (GoalTestAbility ability : abilities) {
            registry.register(ability);
        }
        GoalRun parent = store.unfinished().stream()
                .filter(run -> run.parentRunId() == GoalRun.NO_PARENT).findFirst().orElseThrow();
        return GoalRunner.restore(parent, registry, store, new GoalRunnerTest.TestMemory());
    }

    private static TaskResult resultOf(GoalRunner runner, int maxTicks) {
        TickResult tickResult = TickResult.RUNNING;
        for (int tick = 300; tick < 300 + maxTicks && tickResult instanceof TickResult.Running; tick++) {
            tickResult = runner.tick(new GoalTestTick(tick));
        }
        return assertInstanceOf(TickResult.Finished.class, tickResult).result();
    }

    @Test
    void leavingTheWorldMidSequenceParksTheStepAndPicksItUpAfterRestore() {
        // 先挖坑再搭房，搭房搭到一半退出游戏：搭房的任务停手，整件事和这一步都存成暂停；
        // 回来解除暂停后从搭房接着做，挖好的坑不再挖一遍。
        SharedTasks tasks = new SharedTasks();
        GoalTestAbility dig = new GoalTestAbility("maicraft:dig", tasks);
        dig.next(new StepDecision.Run(new TestInput("挖坑")));
        GoalTestAbility build = new GoalTestAbility("maicraft:build", tasks);
        build.next(new StepDecision.Run(new TestInput("搭房")));
        build.next(new StepDecision.Run(new TestInput("接着搭房")));
        tasks.keepRunning("搭房");
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories());
        registry.register(dig);
        registry.register(build);
        InMemoryGoalRunStore store = new InMemoryGoalRunStore();
        GoalRunner runner = GoalRunner.launch(sequenceOf(stepOf("maicraft:dig"), stepOf("maicraft:build")),
                registry, store, new GoalRunnerTest.TestMemory());
        runner.start(TICK0);
        for (long tick = 101; tick < 106; tick++) runner.tick(new GoalTestTick(tick));
        assertEquals(List.of("挖坑", "搭房"), labels(tasks.started));

        runner.leaveWorld();

        assertEquals(CloseReason.PLAYER_GONE, tasks.created.get("搭房").closedWith);
        List<GoalRun> parked = store.unfinished();
        assertEquals(2, parked.size(), "整件事和正在跑的那一步都留在存盘里");
        assertTrue(parked.stream().allMatch(run -> run.state() == GoalRunState.PAUSED));

        GoalRunner restored = GoalRunner.restore(runner.run(), registry, store, new GoalRunnerTest.TestMemory());
        restored.resumeGoal();
        TaskResult result = drive(restored, 10);

        assertEquals(List.of("挖坑", "搭房", "接着搭房"), labels(tasks.started));
        // 重启前挖好的坑照样算做成：整件事是完成，不是"做成一半"。
        assertEquals(TaskResult.Status.DONE, result.status(), result::summary);
        assertEquals("按顺序做完了全部 2 步", result.summary());
    }
}
