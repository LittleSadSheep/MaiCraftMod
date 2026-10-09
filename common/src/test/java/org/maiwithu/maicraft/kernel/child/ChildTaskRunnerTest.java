// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.child;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.Standing;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 子任务运行器：正常结算、失败传播、暂停与恢复、卡住判定、继承时限、重复结算。 */
class ChildTaskRunnerTest {

    private static final ChildTestTick TICK = new ChildTestTick(0);

    /** 永远在做、从不报告进展的动作替身：给磨蹭的子任务用。 */
    static final class IdleAction implements Action {
        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.running();
        }

        @Override public String describe() {
            return "磨蹭中";
        }
    }

    /** 磨蹭不前进的分阶段子任务：动作永远在做、没有进展，由它自己的进度跟踪判卡住。 */
    static final class StallingChild extends PhasedTask<StallingChild.Phase> {
        enum Phase { WORK }

        StallingChild(ProgressTracker tracker) {
            super("磨蹭", Phase.WORK, tracker);
        }

        @Override protected Action enter(Phase phase) {
            // 一直推不动的动作：不报告进展，卡住判定完全交给进度跟踪。
            return new IdleAction();
        }

        @Override protected Next<Phase> tick(Phase phase, TickContext context) {
            return runActionThen(context, () -> Next.stay());
        }
    }

    private static TickResult runUntilFinished(ChildTaskRunner runner, int maxTicks) {
        TickResult tickResult = TickResult.RUNNING;
        for (int tick = 0; tick < maxTicks && tickResult instanceof TickResult.Running; tick++) {
            tickResult = runner.tick(TICK);
        }
        return tickResult;
    }

    private static TaskResult resultOf(TickResult tickResult) {
        return assertInstanceOf(TickResult.Finished.class, tickResult).result();
    }

    @Test
    void runsChildToCompletionAndSettles() {
        ScriptedChild child = new ScriptedChild(ScriptedChild.running(), ScriptedChild.running(),
                ScriptedChild.finishedDone("箱子关上了"));
        ChildTaskRunner runner = new ChildTaskRunner(new ProgressTracker(1, 100));
        runner.begin(child, TICK);

        TaskResult result = resultOf(runUntilFinished(runner, 10));

        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals("箱子关上了", result.summary());
        assertTrue(runner.finished());
        assertSame(result, runner.result(), "结算结果要能从运行器查到");
        assertEquals(1, child.closes, "子任务自己走到结局后也要按任务接口的约定补上收尾调用");
        assertEquals(CloseReason.FINISHED, child.closedWith);
        assertEquals(Interruptibility.BETWEEN_ACTIONS, runner.interruptibility(TICK),
                "子任务结束后手上没有活，不急的事可以插进来");
        assertTrue(runner.describe().contains("箱子关上了"));
    }

    @Test
    void childFailurePropagatesToParent() {
        ScriptedChild child = new ScriptedChild(TickResult.finished(
                TaskResult.failed("附近没有羊毛", Problem.of(Problem.Kind.NEED_ITEM, "附近没有羊，身上也没有羊毛"))));
        ChildTaskRunner runner = new ChildTaskRunner(new ProgressTracker(1, 100));
        runner.begin(child, TICK);

        TaskResult result = resultOf(runner.tick(TICK));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind(), "失败原因要原样向上传播，不能被吞掉或改写");
        assertEquals(1, child.closes);
    }

    @Test
    void pauseSuspendsChildAndLaterTickResumes() {
        ScriptedChild child = new ScriptedChild(ScriptedChild.running(), ScriptedChild.running(),
                ScriptedChild.finishedDone("躺下了"));
        child.interruptibility = Interruptibility.UNSAFE_TO_STOP;
        ChildTaskRunner runner = new ChildTaskRunner(new ProgressTracker(1, 100));
        runner.begin(child, TICK);

        assertEquals(TickResult.RUNNING, runner.tick(TICK));
        assertEquals(Interruptibility.UNSAFE_TO_STOP, runner.interruptibility(TICK),
                "能不能打断要问子任务，不能由运行器替它决定");

        runner.pause();
        assertEquals(1, child.pauses, "父任务被打断时暂停要转发给子任务");

        // 轮回来后接着推进，不再重新 start，脚本从上次的位置继续走完。
        assertEquals(TickResult.RUNNING, runner.tick(TICK));
        TaskResult result = resultOf(runner.tick(TICK));
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(1, child.starts, "恢复不能重新开始子任务");
        assertEquals(1, child.closes);
    }

    @Test
    void stuckChildIsJudgedByItsOwnProgressTracker() {
        // 磨蹭的子任务自己带进度跟踪：3 刻没有真实进展就算卡住，运行器只传播它的失败结果。
        ChildTaskRunner runner = new ChildTaskRunner(new ProgressTracker(1, 1000));
        runner.begin(new StallingChild(new ProgressTracker(3, 1000)), TICK);

        TaskResult result = resultOf(runUntilFinished(runner, 10));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.STUCK, result.problem().kind());
        assertTrue(result.problem().message().contains("没有新进展"),
                "卡住的问题要按进度跟踪给出的说法写：" + result.problem().message());
    }

    @Test
    void inheritedBudgetTimeoutStopsRunawayChild() {
        // 子任务自己的任务类型不做卡住判定（永远在做），父任务交给的时限到点就得截停。
        ScriptedChild child = new ScriptedChild(ScriptedChild.running());
        ChildTaskRunner runner = new ChildTaskRunner(new ProgressTracker(1, 5));
        runner.begin(child, TICK);

        TaskResult result = null;
        for (int tick = 0; tick < 5; tick++) {
            TickResult tickResult = runner.tick(TICK);
            if (tickResult instanceof TickResult.Finished finished) result = finished.result();
        }

        assertEquals(TaskResult.Status.FAILED, result.status(), "继承的时限用完要给出失败结果");
        assertEquals(Problem.Kind.STUCK, result.problem().kind());
        assertTrue(result.problem().message().contains("超过"));
        assertEquals(1, child.closes);
        assertEquals(CloseReason.CANCELLED, child.closedWith, "没走到结局被截停的子任务按取消收尾");
    }

    @Test
    void standingChildIsNotStoppedByInheritedBudget() {
        // 常驻任务（等待、跟随）等的就是时间本身：兜底时限到点也不截停，由它自己的进度跟踪负责。
        StandingChild child = new StandingChild();
        ChildTaskRunner runner = new ChildTaskRunner(new ProgressTracker(1, 5));
        runner.begin(child, TICK);

        for (int tick = 0; tick < 10; tick++) {
            assertEquals(TickResult.RUNNING, runner.tick(TICK), "常驻子任务超过兜底时限也照常推进");
        }
        assertTrue(!runner.finished(), "常驻子任务不该被继承的时限截停");
        assertEquals(0, child.closes);
    }

    /** 一直做、不结束的常驻子任务替身：模拟等待与跟随这类没有做完时刻的任务。 */
    static final class StandingChild implements Task, Standing {
        int closes;

        @Override public void start(TickContext context) {}

        @Override public TickResult tick(TickContext context) {
            return TickResult.RUNNING;
        }

        @Override public void pause() {}

        @Override public TaskResult close(CloseReason reason) {
            closes++;
            return TaskResult.done("收尾了");
        }

        @Override public Interruptibility interruptibility(TickContext context) {
            return Interruptibility.WORKING;
        }

        @Override public String describe() {
            return "常驻替身";
        }
    }

    @Test
    void doubleSettleIsRejected() {
        ScriptedChild child = new ScriptedChild(ScriptedChild.finishedDone("做完了"));
        ChildTaskRunner runner = new ChildTaskRunner(new ProgressTracker(1, 100));
        runner.begin(child, TICK);
        resultOf(runner.tick(TICK));
        assertTrue(runner.finished());

        assertThrows(IllegalStateException.class, () -> runner.tick(TICK), "结算后再推进要被拒绝");
        assertThrows(IllegalStateException.class, () -> runner.close(CloseReason.CANCELLED), "重复结算要被拒绝");
        assertThrows(IllegalStateException.class, () -> runner.begin(new ScriptedChild(), TICK), "结算后再开始要被拒绝");
        // 结算前提前收尾同样只允许一次。
        ChildTaskRunner early = new ChildTaskRunner(new ProgressTracker(1, 100));
        ScriptedChild abandoned = new ScriptedChild(ScriptedChild.running());
        abandoned.closeResult = TaskResult.cancelled("放下了手里的半成品");
        early.begin(abandoned, TICK);
        TaskResult closed = early.close(CloseReason.REPLACED);
        assertEquals(TaskResult.Status.CANCELLED, closed.status());
        assertEquals(CloseReason.REPLACED, abandoned.closedWith);
        assertThrows(IllegalStateException.class, () -> early.close(CloseReason.CANCELLED), "收尾也只能做一次");
    }

    @Test
    void exceptionsBecomeInternalErrorProblems() {
        // 启动就抛异常：运行器收场并给出程序错误的结果，不让异常顺着调用链炸到父任务。
        ScriptedChild brokenStart = new ScriptedChild();
        brokenStart.startError = new IllegalStateException("没有登记对应的任务");
        ChildTaskRunner runner = new ChildTaskRunner(new ProgressTracker(1, 100));
        runner.begin(brokenStart, TICK);

        assertTrue(runner.finished());
        TaskResult result = runner.result();
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.INTERNAL_ERROR, result.problem().kind());

        // 推进到一半抛异常同样转成程序错误，父任务拿到的是明确的结算，不是异常。
        ScriptedChild brokenTick = new ScriptedChild(ScriptedChild.running());
        brokenTick.tickError = new ArithmeticException("除数不能为零");
        ChildTaskRunner midRun = new ChildTaskRunner(new ProgressTracker(1, 100));
        midRun.begin(brokenTick, TICK);
        TaskResult midResult = resultOf(midRun.tick(TICK));
        assertEquals(TaskResult.Status.FAILED, midResult.status());
        assertEquals(Problem.Kind.INTERNAL_ERROR, midResult.problem().kind());
        assertEquals(1, brokenTick.closes, "异常收场也要给子任务一次收尾机会");
        assertTrue(midRun.finished());
    }
}
