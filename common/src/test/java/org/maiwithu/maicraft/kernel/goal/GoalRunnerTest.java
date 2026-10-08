// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.goal.GoalTestAbility.ScriptedGoalTask;
import org.maiwithu.maicraft.kernel.goal.GoalTestAbility.TestInput;
import org.maiwithu.maicraft.kernel.result.Change;
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
 * 按顺序运行几个任务、存盘与重启后恢复为暂停、提前收尾；每个任务已经发生的事都并进目标的结果，
 * 失败后能回来换办法，钩子在每个任务开始前都被问到，出错与启动失败都如实收场，暂停与恢复。
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
    void decideAgainAfterSuccessLooksAtTheStepAgain() {
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
        GoalRunner revived = GoalRunner.restore(restored, registry, store, new TestMemory());
        assertEquals(GoalRunState.PAUSED, revived.run().state(), "重启后恢复为暂停");
        assertEquals(TickResult.RUNNING, revived.tick(TICK0), "暂停中的目标不推进");
        assertEquals(List.of("y"), revived.run().answers(), "回答跟着记录一起回来了");

        revived.resumeGoal();
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
                () -> GoalRunner.restore(runner.run(), new AbilityRegistry(new TaskFactories()), store, new TestMemory()));
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
        run.resume();
        run.answer("a");
        assertThrows(IllegalStateException.class, () -> run.answer("b"), "不在等回答时不能回答");
        run.pause();
        assertThrows(IllegalStateException.class, () -> run.ask(question), "暂停中不能提问");
        run.resume();
        run.finish(TaskResult.done("好了"), 20);
        assertThrows(IllegalStateException.class, () -> run.finish(TaskResult.done("又好了"), 21),
                "结束只发生一次");
        assertThrows(IllegalStateException.class, () -> run.answer("late"), "结束后不能再回答");
        assertEquals(TaskResult.Status.DONE, run.result().status());
        assertEquals(20, run.finishedTick());
        assertNull(run.question());
    }
    private static TaskResult doneWith(String summary, String item) {
        return TaskResult.builder(TaskResult.Status.DONE, summary)
                .change(Change.of(Change.Kind.ITEM_GAINED, item, 1)).build();
    }

    private static AbilityRegistry registryOf(GoalTestAbility ability) {
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories());
        registry.register(ability);
        return registry;
    }

    @Test
    void factsOfEveryTaskEndUpInTheGoalResult() {
        // 先拿木头再做木板：最后由做木板给出结论，拿到木头这件事也要留在目标的结果里。
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.ending("拿木头", doneWith("拿了木头", "minecraft:oak_log"));
        ability.ending("做木板", doneWith("做了木板", "minecraft:oak_planks"));
        ability.next(new StepDecision.RunInOrder(List.of(
                () -> new TestInput("拿木头"), () -> new TestInput("做木板"))));
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability,
                new InMemoryGoalRunStore(), new TestMemory());

        TaskResult result = drive(runner, 10);

        assertEquals(List.of("minecraft:oak_log", "minecraft:oak_planks"),
                result.changes().stream().map(Change::what).toList());
    }

    @Test
    void finishAfterTasksKeepsWhatTheTasksDid() {
        // 白天叫它睡觉：先备好床，能力看了结果再以部分完成收场，备床的变化要在结果里。
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.ending("备床", doneWith("放好了床", "minecraft:red_bed"));
        ability.next(new StepDecision.Run(new TestInput("备床"), true));
        ability.next(new StepDecision.Finish(TaskResult.builder(TaskResult.Status.PARTIAL, "现在睡不了")
                .remaining("入睡").problem(Problem.of(Problem.Kind.WRONG_TIME, "白天睡不了")).build()));
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability,
                new InMemoryGoalRunStore(), new TestMemory());

        TaskResult result = drive(runner, 10);

        assertEquals(TaskResult.Status.PARTIAL, result.status());
        assertEquals("minecraft:red_bed", result.changes().get(0).what());
        assertEquals(1, ability.taskResultsSeen.size(), "做决定时看得到刚结束的任务");
    }

    @Test
    void failedTaskWithDecideAgainLetsTheAbilityTryAnotherWay() {
        // 路被挡住：回来再决定，能力看到失败后换一条路，第二条路走通了，第一条路的经过也留着。
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.ending("走大路", TaskResult.builder(TaskResult.Status.FAILED, "大路被挡")
                .problem(Problem.of(Problem.Kind.UNREACHABLE, "大路被墙挡住"))
                .change(Change.of(Change.Kind.MOVED, "大路口", 1)).build());
        ability.next(new StepDecision.Run(new TestInput("走大路"), true));
        ability.next(new StepDecision.Run(new TestInput("走小路")));
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability,
                new InMemoryGoalRunStore(), new TestMemory());

        TaskResult result = drive(runner, 10);

        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(TaskResult.Status.FAILED, ability.taskResultsSeen.get(0).status(), "换办法前看到了失败");
        assertEquals("大路口", result.changes().get(0).what());
    }

    @Test
    void closeKeepsFactsOfTasksThatAlreadyFinished() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.ending("拿木头", doneWith("拿了木头", "minecraft:oak_log"));
        ability.shared().keepRunning("做木板");
        ability.next(new StepDecision.RunInOrder(List.of(
                () -> new TestInput("拿木头"), () -> new TestInput("做木板"))));
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability,
                new InMemoryGoalRunStore(), new TestMemory());
        runner.start(TICK0);
        runner.tick(new GoalTestTick(101));
        runner.tick(new GoalTestTick(102));

        TaskResult result = runner.close(CloseReason.CANCELLED);

        assertEquals(TaskResult.Status.CANCELLED, result.status());
        assertEquals(List.of("minecraft:oak_log"), result.changes().stream().map(Change::what).toList());
        assertEquals(1, ability.shared().created.get("做木板").closes, "还在做的任务按取消收尾");
    }

    @Test
    void beforeTaskHookRunsBeforeEveryTaskStarts() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        List<String> timeline = new ArrayList<>();
        ability.hooks = new AbilityHooks() {
            @Override public void beforeTask(StepContext step, TaskInput input) {
                timeline.add("看:" + input.describe() + "（已启动 " + ability.startedInputs().size() + " 个）");
            }
        };
        ability.next(new StepDecision.RunInOrder(List.of(
                () -> new TestInput("一"), () -> new TestInput("二"), () -> new TestInput("三"))));
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability,
                new InMemoryGoalRunStore(), new TestMemory());

        drive(runner, 10);

        assertEquals(List.of("看:一（已启动 0 个）", "看:二（已启动 1 个）", "看:三（已启动 2 个）"), timeline,
                "排在后面的任务也在启动前被问到");
    }

    @Test
    void hookErrorClosesTheRunningTaskBeforeEnding() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.shared().keepRunning("一直挖");
        ability.hooks = new AbilityHooks() {
            @Override public Question duringTask(StepContext step, TaskInput input) {
                throw new IllegalStateException("钩子出错");
            }
        };
        ability.next(new StepDecision.Run(new TestInput("一直挖")));
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability,
                new InMemoryGoalRunStore(), new TestMemory());

        TaskResult result = drive(runner, 5);

        assertEquals(Problem.Kind.INTERNAL_ERROR, result.problem().kind());
        assertEquals(1, ability.shared().created.get("一直挖").closes, "出错前先收尾正在跑的任务，不留按住的键");
    }

    @Test
    void taskThatFailsToStartEndsTheGoalInsteadOfRetryingForever() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.shared().failOnStart("坏任务", new IllegalStateException("启动出错"));
        for (int i = 0; i < 5; i++) {
            ability.next(new StepDecision.Run(new TestInput("坏任务")));
        }
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability,
                new InMemoryGoalRunStore(), new TestMemory());

        TaskResult result = drive(runner, 5);

        assertEquals(Problem.Kind.INTERNAL_ERROR, result.problem().kind());
        assertEquals(1, ability.startedInputs().size(), "启动失败当场结束，不每刻重来");
    }

    @Test
    void pausedGoalStopsTheTaskAndResumesWhereItWas() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.shared().keepRunning("砍树");
        ability.next(new StepDecision.Run(new TestInput("砍树")));
        InMemoryGoalRunStore store = new InMemoryGoalRunStore();
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability, store, new TestMemory());
        runner.start(TICK0);
        runner.tick(new GoalTestTick(101));

        runner.pauseGoal();

        assertEquals(GoalRunState.PAUSED, store.unfinished().get(0).state(), "暂停后立即存盘");
        assertEquals(1, ability.shared().created.get("砍树").pauses, "暂停时任务松手");
        assertEquals(TickResult.RUNNING, runner.tick(new GoalTestTick(102)));
        assertEquals(Interruptibility.BETWEEN_ACTIONS, runner.interruptibility(TICK0), "暂停中谁都可以插进来");
        runner.resumeGoal();
        assertEquals(GoalRunState.RUNNING, runner.run().state());
        assertEquals(1, ability.startedInputs().size(), "恢复后接着原来的任务，不重新开始");
    }

    @Test
    void pausedGoalCanBeAnsweredBeforeResuming() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.next(new StepDecision.Ask(new Question(Question.Reason.CHOOSE_ONE, "走哪条路？",
                List.of(new Question.Option("a", "北边")))));
        ability.next(new StepDecision.Finish(TaskResult.done("过去了")));
        InMemoryGoalRunStore store = new InMemoryGoalRunStore();
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability, store, new TestMemory());
        runner.start(TICK0);
        runner.tick(new GoalTestTick(101));
        GoalRunner revived = GoalRunner.restore(store.unfinished().get(0), registryOf(ability), store, new TestMemory());
        assertEquals("走哪条路？", revived.pendingQuestion().text());

        revived.answer("a");

        assertEquals(GoalRunState.PAUSED, revived.run().state(), "先收下回答，仍保持暂停");
        assertNull(revived.pendingQuestion());
        revived.resumeGoal();
        assertEquals(TaskResult.Status.DONE, resultOfDrive(revived).status());
        assertEquals(List.of("a"), ability.answersSeen);
    }

    @Test
    void waitingForAnAnswerLeavesRoomForUnhurriedNeeds() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        ability.shared().keepRunning("挖矿");
        ability.hooks = new AbilityHooks() {
            @Override public Question duringTask(StepContext step, TaskInput input) {
                return step.answers().isEmpty() ? new Question(Question.Reason.NEED_APPROVAL, "要拆墙吗？",
                        List.of(new Question.Option("y", "拆"))) : null;
            }
        };
        ability.next(new StepDecision.Run(new TestInput("挖矿")));
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability,
                new InMemoryGoalRunStore(), new TestMemory());
        runner.start(TICK0);
        runner.tick(new GoalTestTick(101));
        runner.tick(new GoalTestTick(102));

        assertEquals(GoalRunState.AWAITING_ANSWER, runner.run().state());
        assertEquals(Interruptibility.BETWEEN_ACTIONS, runner.interruptibility(TICK0),
                "任务停手等回答时，吃口东西这类不急的事可以插进来");
    }

    @Test
    void stepRecordsAreNotRestoredOnTheirOwn() {
        GoalRun step = new GoalRun(7, Goal.of("maicraft:test", null, null), 3, 0);
        assertThrows(IllegalArgumentException.class, () -> GoalRunner.restore(step,
                new AbilityRegistry(new TaskFactories()), new InMemoryGoalRunStore(), new TestMemory()));
    }

    @Test
    void cancelledBeforeFirstTickStaysCancelledWhenStarted() {
        GoalTestAbility ability = new GoalTestAbility("maicraft:test");
        GoalRunner runner = launch(Goal.of("maicraft:test", null, null), ability,
                new InMemoryGoalRunStore(), new TestMemory());
        runner.close(CloseReason.CANCELLED);

        runner.start(TICK0);

        assertEquals(TaskResult.Status.CANCELLED,
                assertInstanceOf(TickResult.Finished.class, runner.tick(TICK0)).result().status());
    }
}
