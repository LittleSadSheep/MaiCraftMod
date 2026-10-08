// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.goal.GoalTestAbility.ScriptedGoalTask;
import org.maiwithu.maicraft.kernel.goal.GoalTestAbility.TestInput;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TaskInput;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目标推进器：下达后逐步推进到完成、失败传播、提问挂起与应答后继续、记住地点、等条件、
 * 按顺序运行几个任务、存盘与重启后恢复为暂停、提前收尾。
 */
class GoalRunnerTest {

    private static final GoalTestTick TICK0 = new GoalTestTick(100);

    /** 替身的世界记忆：记下要记的地点。 */
    static final class TestMemory implements RemembersPlaces {
        final List<String> remembered = new ArrayList<>();

        @Override public void remember(String name, WorldPosition position) {
            remembered.add(name + "@" + position.x() + "," + position.y() + "," + position.z());
        }
    }

    /** 等待条件的替身：按给定的成立与否回答。 */
    private static WaitCondition condition(boolean satisfied) {
        return new WaitCondition() {
            @Override public boolean satisfied(TickContext context) {
                return satisfied;
            }

            @Override public String describe() {
                return satisfied ? "条件已成立" : "条件还没成立";
            }
        };
    }

    private static GoalRunner launch(Goal goal, GoalTestAbility ability, GoalRunStore store, RemembersPlaces memory) {
        TaskFactories factories = new TaskFactories();
        AbilityRegistry registry = new AbilityRegistry(factories);
        registry.register(ability);
        return GoalRunner.launch(goal, registry, store, memory);
    }

    /** 下达后逐刻推进到结束，返回目标的结果；刻数用完还没结束就让断言失败。 */
    private static TaskResult drive(GoalRunner runner, int maxTicks) {
        return resultOfDrive(runner, maxTicks, true);
    }

    /** 从当前处境接着推进，不重新 start；恢复的目标用这个。 */
    private static TaskResult resultOfDrive(GoalRunner runner) {
        return resultOfDrive(runner, 10, false);
    }

    private static TaskResult resultOfDrive(GoalRunner runner, int maxTicks, boolean start) {
        long tick = start ? 100 : 200;
        if (start) {
            runner.start(new GoalTestTick(tick++));
        }
        TickResult tickResult = TickResult.RUNNING;
        for (int i = 0; i < maxTicks && tickResult instanceof TickResult.Running; i++) {
            tickResult = runner.tick(new GoalTestTick(tick++));
        }
        return assertInstanceOf(TickResult.Finished.class, tickResult).result();
    }

    @Test
    void goalAdvancesToCompletionAndIsStoredAsFinished() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.next(new StepDecision.Run(new TestInput("开箱子")));
        InMemoryGoalRunStore store = new InMemoryGoalRunStore();
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability, store, new TestMemory());

        TaskResult result = drive(runner, 10);

        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals("做完了", result.summary(), "任务的完成结果就是目标的完成结果");
        assertEquals(List.of("开箱子"), ability.startedInputs().stream().map(input -> ((TestInput) input).label()).toList());
        assertEquals(GoalRunState.FINISHED, runner.run().state());
        assertTrue(store.unfinished().isEmpty(), "结束的运行不在还没结束的名单里");
    }

    @Test
    void alreadySatisfiedGoalFinishesWithoutActing() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.next(new StepDecision.Finish(TaskResult.done("开始时已满足")));
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability,
                new InMemoryGoalRunStore(), new TestMemory());

        TaskResult result = drive(runner, 10);

        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals("开始时已满足", result.summary());
        assertTrue(ability.startedInputs().isEmpty(), "已经满足就不动手");
    }

    @Test
    void stepFailurePropagatesAsGoalFailure() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.ending("修水泵", TaskResult.failed("缺扳手",
                Problem.of(Problem.Kind.NEED_ITEM, "没有扳手，附近也没有箱子可翻")));
        ability.next(new StepDecision.Run(new TestInput("修水泵")));
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability,
                new InMemoryGoalRunStore(), new TestMemory());

        TaskResult result = drive(runner, 10);

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind(), "失败原因原样传播，不被吞掉或改写");
        assertEquals("缺扳手", result.summary());
        assertEquals(GoalRunState.FINISHED, runner.run().state());
    }

    @Test
    void questionSuspendsGoalAndAnswerResumesIt() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.next(new StepDecision.Ask(new Question(Question.Reason.CHOOSE_ONE,
                "两个箱子都叫零件箱，动哪个？",
                List.of(new Question.Option("a", "门口的"), new Question.Option("b", "里面的")))));
        ability.next(new StepDecision.Run(new TestInput("翻零件箱")));
        InMemoryGoalRunStore store = new InMemoryGoalRunStore();
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability, store, new TestMemory());
        runner.start(TICK0);

        assertEquals(TickResult.RUNNING, runner.tick(TICK0));
        assertEquals(GoalRunState.AWAITING_ANSWER, runner.run().state(), "提问后目标挂起");
        assertNotNull(runner.run().question());
        // 挂起期间每刻都只是等，不再决定、不推进任务。
        assertEquals(TickResult.RUNNING, runner.tick(new GoalTestTick(101)));
        assertTrue(ability.startedInputs().isEmpty());

        runner.answer("b");
        assertEquals(GoalRunState.RUNNING, runner.run().state(), "回答后继续推进");
        assertEquals(List.of("b"), runner.run().answers());

        TaskResult result = resultOfDrive(runner);
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(List.of("b"), ability.answersSeen, "能力重新决定时能看到 LLM 的回答");
    }

    @Test
    void notReadyKeepsDecidingNextTick() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.next(StepDecision.NOT_READY);
        ability.next(new StepDecision.Finish(TaskResult.done("扫完了，没有威胁")));
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability,
                new InMemoryGoalRunStore(), new TestMemory());

        TaskResult result = drive(runner, 10);

        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals("扫完了，没有威胁", result.summary(), "信息不全只推迟决定，不把没扫完当成没有");
    }

    @Test
    void recheckAfterSuccessLooksAtTheStepAgain() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.next(new StepDecision.Run(new TestInput("补一块板"), true));
        ability.next(new StepDecision.Finish(TaskResult.done("墙补好了")));
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability,
                new InMemoryGoalRunStore(), new TestMemory());

        TaskResult result = drive(runner, 10);

        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals("墙补好了", result.summary(), "任务做成后回来重新看这一步，由能力定是否满足");
        assertEquals(1, ability.startedInputs().size(), "重新看时没再动手");
    }

    @Test
    void rememberHandsThePlaceToMemory() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.next(new StepDecision.Remember("家门口", WorldPosition.here(12, 64, -8)));
        ability.next(new StepDecision.Finish(TaskResult.done("记好了")));
        TestMemory memory = new TestMemory();
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability,
                new InMemoryGoalRunStore(), memory);

        TaskResult result = drive(runner, 10);

        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(List.of("家门口@12,64,-8"), memory.remembered);
    }

    @Test
    void waitHoldsUntilConditionIsSatisfiedAfterNotBefore() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.next(new StepDecision.Wait(condition(false), 105));
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability,
                new InMemoryGoalRunStore(), new TestMemory());
        runner.start(TICK0);
        // 没到检查时间，条件也没成立：一直等。
        assertEquals(TickResult.RUNNING, runner.tick(new GoalTestTick(103)));
        assertTrue(runner.describe().contains("等"), "面板要能看出目标在等：" + runner.describe());
        assertTrue(ability.startedInputs().isEmpty());

        // 条件成立且过了检查时间：重新决定并完成。
        GoalTestAbility waitingAbility = new GoalTestAbility("maicraft:test");
        waitingAbility.next(new StepDecision.Wait(condition(true), 105));
        waitingAbility.next(new StepDecision.Finish(TaskResult.done("天黑了，开干")));
        GoalRunner late = launch(Goal.of("maicraft:test", null, null), waitingAbility,
                new InMemoryGoalRunStore(), new TestMemory());
        TaskResult result = resultOfDrive(late);
        assertEquals("天黑了，开干", result.summary());
    }

    @Test
    void runInOrderRunsTasksOneByOne() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        List<TaskInput> generated = new ArrayList<>();
        ability.next(new StepDecision.RunInOrder(List.of(
                () -> {
                    generated.add(new TestInput("第一步"));
                    return new TestInput("第一步");
                },
                () -> {
                    generated.add(new TestInput("第二步"));
                    return new TestInput("第二步");
                })));
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability,
                new InMemoryGoalRunStore(), new TestMemory());

        TaskResult result = drive(runner, 10);

        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(List.of("第一步", "第二步"),
                generated.stream().map(input -> ((TestInput) input).label()).toList(),
                "后面的输入到轮到时才生成");
        assertEquals(List.of("第一步", "第二步"),
                ability.startedInputs().stream().map(input -> ((TestInput) input).label()).toList());
    }

    @Test
    void restartRestoresUnfinishedRunAsPaused() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.next(new StepDecision.Ask(new Question(Question.Reason.NEED_APPROVAL,
                "要拆玩家盖的墙才能过去，可以吗？", List.of(new Question.Option("y", "可以拆")))));
        ability.next(new StepDecision.Ask(new Question(Question.Reason.CHOOSE_ONE,
                "走哪条路？", List.of(new Question.Option("a", "北边")))));
        ability.next(new StepDecision.Finish(TaskResult.done("过去了")));
        InMemoryGoalRunStore store = new InMemoryGoalRunStore();
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability, store, new TestMemory());
        runner.start(TICK0);
        runner.tick(TICK0);
        runner.answer("y");
        // 再问一次，让目标停挂在半路，模拟重启前中断。
        runner.tick(new GoalTestTick(101));
        assertEquals(GoalRunState.AWAITING_ANSWER, runner.run().state());

        // 重启：从存储读回还没结束的记录，恢复为暂停。
        List<GoalRun> unfinished = store.unfinished();
        assertEquals(1, unfinished.size());
        GoalRun restored = unfinished.get(0);
        TaskFactories factories = new TaskFactories();
        AbilityRegistry registry = new AbilityRegistry(factories);
        registry.register(ability);
        GoalRunner revived = GoalRunner.resume(restored, registry, store, new TestMemory());
        assertEquals(GoalRunState.PAUSED, revived.run().state(), "重启后恢复为暂停");
        assertEquals(TickResult.RUNNING, revived.tick(TICK0), "暂停中的目标不推进");
        assertEquals(List.of("y"), revived.run().answers(), "回答跟着记录一起回来了");

        revived.unpause();
        revived.answer("a");
        TaskResult result = resultOfDrive(revived);
        assertEquals(TaskResult.Status.DONE, result.status());
        assertTrue(store.unfinished().isEmpty(), "走完的运行收尾存盘");
    }

    @Test
    void resumingFinishedRunIsRejected() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.next(new StepDecision.Finish(TaskResult.done("好了")));
        InMemoryGoalRunStore store = new InMemoryGoalRunStore();
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability, store, new TestMemory());
        drive(runner, 10);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> GoalRunner.resume(runner.run(), new AbilityRegistry(new TaskFactories()), store, new TestMemory()));
        assertTrue(error.getMessage().contains("已经结束"));
    }

    @Test
    void earlyCloseCancelsGoalAndSettlesTheChild() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.next(new StepDecision.Run(new TestInput("慢慢挖")));
        InMemoryGoalRunStore store = new InMemoryGoalRunStore();
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability, store, new TestMemory());
        runner.start(TICK0);
        runner.tick(TICK0);
        assertEquals(1, ability.startedInputs().size());

        TaskResult result = runner.close(CloseReason.REPLACED);

        assertEquals(TaskResult.Status.CANCELLED, result.status());
        assertEquals(GoalRunState.FINISHED, runner.run().state());
        assertTrue(store.unfinished().isEmpty(), "取消后也要存盘，不让记录悬着");
    }

    @Test
    void unknownAbilityEndsWithInternalError() {
        // 能力没登记：决定那一步直接抛异常，目标以程序错误收场，不连累调用方。
        GoalRunner runner = GoalRunner.launch(Goal.of("maicraft:ghost", null, null),
                new AbilityRegistry(new TaskFactories()), new InMemoryGoalRunStore(), new TestMemory());

        TickResult tickResult = runner.tick(TICK0);

        TaskResult result = assertInstanceOf(TickResult.Finished.class, tickResult).result();
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.INTERNAL_ERROR, result.problem().kind());
    }

    @Test
    void interruptibilityFollowsTheRunningChild() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.next(new StepDecision.Run(new TestInput("等确认")));
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability,
                new InMemoryGoalRunStore(), new TestMemory());
        runner.start(TICK0);
        assertEquals(Interruptibility.BETWEEN_ACTIONS, runner.interruptibility(TICK0),
                "还没开始跑任务，不急的事可以插进来");
        runner.tick(TICK0);
        assertEquals(Interruptibility.WORKING, runner.interruptibility(TICK0),
                "任务运行中能不能打断要问子任务");
    }

    @Test
    void goalRunStateOnlyMovesForward() {
        GoalRun run = new GoalRun(1, Goal.of("maicraft:test", null, null));
        Question question = new Question(Question.Reason.CHOOSE_ONE, "选一个",
                List.of(new Question.Option("a", "甲")));
        run.start(10);
        run.ask(question);
        run.pause();
        assertEquals(question, run.question(), "等回答时暂停，问题还挂着");
        run.unpause();
        run.answer("a");
        assertThrows(IllegalStateException.class, () -> run.answer("b"), "不在等回答时不能回答");
        run.pause();
        assertThrows(IllegalStateException.class, () -> run.ask(question), "暂停中不能提问");
        run.unpause();
        run.finish(TaskResult.done("好了"), 20);
        assertThrows(IllegalStateException.class, () -> run.finish(TaskResult.done("又好了"), 21),
                "结束只发生一次");
        assertThrows(IllegalStateException.class, () -> run.answer("late"), "结束后不能再回答");
        assertEquals(TaskResult.Status.DONE, run.result().status());
        assertEquals(20, run.finishedTick());
        assertNull(run.question());
    }
}
