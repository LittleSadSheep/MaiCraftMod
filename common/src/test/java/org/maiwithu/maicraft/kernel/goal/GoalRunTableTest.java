// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.param.Params;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
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
    private final RecordingHandover handover = new RecordingHandover();
    private final GoalRunTable table;
    private long tick = 100;

    GoalRunTableTest() {
        registry.register(ability);
        // 与启动一侧同样的占位：没接上世界记忆时记地点如实报程序错误。
        table = new GoalRunTable(registry, store, (name, position) -> {
            throw new IllegalStateException("没有当期的世界记忆，记不住地点 " + name);
        }, loop, handover);
    }

    /** 控制权交接的替身：记下请求了几次，控制权此刻在谁手上可以拨。 */
    private static final class RecordingHandover implements PlayerControlHandover {
        int requests;
        boolean automationOwns = true;
        boolean humanTookOver;

        @Override public boolean automationOwnsControls() {
            return automationOwns;
        }

        @Override public void requestControl() {
            requests++;
        }

        @Override public boolean humanTookOver() {
            return humanTookOver;
        }
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
    void becomingTheMainTaskAsksForControlOncePerNewGoal() {
        table.launch(GOAL, "key-1").runner();
        assertEquals(1, handover.requests);

        // 同一请求键的重复下达不是新目标：人类抢回控制权后，它不会把控制权立刻抢回来。
        table.launch(GOAL, "key-1");
        assertEquals(1, handover.requests);

        table.launch(GOAL, "key-2").runner();
        assertEquals(2, handover.requests);
    }

    @Test
    void resumingAPausedGoalAsksForControl() {
        GoalRunner runner = table.launch(GOAL, null).runner();
        table.pause(runner.run().id());
        table.resume(runner.run().id());

        // 下达请求了一次，恢复时再请求一次：恢复也是一次明确的下达；
        // 人按 F8 收回了角色时请求不生效，由输入层把关。
        assertEquals(2, handover.requests);
    }

    @Test
    void waitingForControlIsReportedAsItIs() {
        handover.automationOwns = false;
        GoalRunner runner = table.launch(GOAL, null).runner();
        long id = runner.run().id();

        // 还没拿到控制权时不呈现成"正在推进"，如实说在等交接。
        assertTrue(table.doing(id).orElseThrow().contains("等待控制权交接"));

        handover.automationOwns = true;
        assertFalse(table.doing(id).orElseThrow().contains("等待控制权交接"));

        // 暂停的目标在等恢复，不是在等控制权。
        table.pause(id);
        assertFalse(table.doing(id).orElseThrow().contains("等待控制权交接"));
    }

    @Test
    void goalWhosePermissionsTurnSurvivalNeedsOffSaysSo() {
        // survival_needs=off 的目标（寻死这类）对控制循环声明关掉生存需求；默认许可不关。
        Permissions off = Permissions.DEFAULT.mergedWith(null, null, null, null,
                Permissions.SurvivalNeeds.OFF, null);
        Goal suicide = new Goal(ABILITY, null, null, Params.EMPTY, off, List.of(), Goal.OnFailure.STOP);

        assertTrue(table.launch(suicide, null).runner().survivalNeedsOff());
        assertFalse(table.launch(GOAL, null).runner().survivalNeedsOff());
    }

    @Test
    void playerWhoPressedF8IsReportedAsHoldingTheCharacter() {
        // 人按 F8 收回了角色：如实说等人交回，不提示重新下达去抢。
        handover.automationOwns = false;
        handover.humanTookOver = true;
        long id = table.launch(GOAL, null).runner().run().id();

        String doing = table.doing(id).orElseThrow();
        assertTrue(doing.contains("F8"), doing);
        assertFalse(doing.contains("重新下达"), doing);
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
        store.save(new GoalRun(store.nextId(), GOAL, interrupted.id(), 0));

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

    @Test
    void rememberingFollowsTheWorldItIsInAndStopsAfterLeaving() {
        // 进了世界，记地点落到这个世界的记忆；离开之后同一种决定按程序错误收场，不悄悄丢掉。
        GoalRunnerTest.TestMemory attached = new GoalRunnerTest.TestMemory();
        table.enterWorld(attached, store);
        ability.next(new StepDecision.Remember("家门口", WorldPosition.here(12, 64, -8)));
        ability.next(new StepDecision.Finish(TaskResult.done("记好了")));
        table.runAside(GOAL, new GoalTestTick(tick++));
        assertEquals(List.of("家门口@12,64,-8"), attached.remembered);

        table.leaveWorld();
        ability.next(new StepDecision.Remember("家门口", WorldPosition.here(12, 64, -8)));
        ability.next(new StepDecision.Finish(TaskResult.done("记好了")));
        // 推进器把程序出错按如实失败收场：这次没记上，也不连累控制循环。
        GoalRun run = table.runAside(GOAL, new GoalTestTick(tick++));
        assertEquals(GoalRunState.FINISHED, run.state());
        assertEquals(TaskResult.Status.FAILED, run.result().status());
    }

    @Test
    void leavingTheWorldParksTheMainGoalAndComingBackRestoresItPaused() {
        // 角色正在干活时退出到标题：任务停手，目标不结束、存成暂停，控制循环空下来，不跟进下一个世界。
        table.enterWorld(new GoalRunnerTest.TestMemory(), store);
        ability.shared().keepRunning("挖矿");
        ability.next(new StepDecision.Run(new GoalTestAbility.TestInput("挖矿")));
        long id = table.launch(GOAL, null).runner().run().id();
        runTicks(2);

        table.leaveWorld();

        assertEquals(CloseReason.PLAYER_GONE, ability.shared().created.get("挖矿").closedWith, "手上的任务停手松键");
        assertNull(loop.currentTask());
        assertTrue(table.recent().isEmpty());
        GoalRun parked = store.unfinished().get(0);
        assertEquals(id, parked.id());
        assertEquals(GoalRunState.PAUSED, parked.state());

        // 再进同一个世界：目标恢复为暂停，等 LLM 解除暂停才重新成为主任务。
        table.enterWorld(new GoalRunnerTest.TestMemory(), store);
        assertEquals(GoalRunState.PAUSED, table.find(id).orElseThrow().state());
        assertNull(loop.currentTask());
        table.resume(id);
        assertEquals(id, ((GoalRunner) loop.currentTask()).run().id());
    }
}
