// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分阶段任务的行为：阶段走向、卡住判定、被打断与收尾、结果组装。 */
class PhasedTaskTest {

    enum Phase { APPROACH, LIE_DOWN, DECIDE }

    /** 用一张"阶段 → 动作"表拼出的任务，模拟"走到床边 → 躺下"这类两段流程。 */
    static final class GoToBed extends PhasedTask<Phase> {
        final Map<Phase, ScriptedAction> actions;

        GoToBed(Map<Phase, ScriptedAction> actions, ProgressTracker tracker) {
            super("测试", Phase.APPROACH, tracker);
            this.actions = actions;
        }

        @Override protected Action enter(Phase phase) {
            return actions.get(phase);
        }

        @Override protected Next<Phase> tick(Phase phase, TickContext context) {
            return switch (phase) {
                case APPROACH -> runActionThen(context, () -> Next.go(Phase.LIE_DOWN, "到了"));
                case LIE_DOWN -> runActionThen(context, () -> {
                    recordChange(Change.of(Change.Kind.OTHER, "minecraft:red_bed", 1));
                    return Next.done(TaskResult.done("躺下了"));
                });
                case DECIDE -> Next.stay();
            };
        }

        @Override protected List<String> remaining() {
            return List.of("躺下");
        }
    }

    private static TickResult runUntilFinished(Task task, int maxTicks) {
        TickResult tickResult = TickResult.RUNNING;
        for (int tick = 0; tick < maxTicks && tickResult instanceof TickResult.Running; tick++) {
            tickResult = task.tick(new TestTick(tick));
        }
        return tickResult;
    }

    private static TaskResult resultOf(TickResult tickResult) {
        return assertInstanceOf(TickResult.Finished.class, tickResult).result();
    }

    @Test
    void walksThroughPhasesAndAssemblesResult() {
        ScriptedAction approach = new ScriptedAction("走向床边", ActionStatus.progressed(), ActionStatus.done());
        ScriptedAction lieDown = new ScriptedAction("右键床头", ActionStatus.running(), ActionStatus.done());
        var task = new GoToBed(Map.of(Phase.APPROACH, approach, Phase.LIE_DOWN, lieDown), new ProgressTracker(20, 1000));

        TaskResult result = resultOf(runUntilFinished(task, 10));

        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals("躺下了", result.summary());
        assertEquals(1, result.changes().size(), "运行中记下的变化要进结果");
        assertEquals(1, approach.closes, "换阶段时旧动作要收尾");
        assertEquals(1, lieDown.closes, "结束时当前动作要收尾");
    }

    @Test
    void actionFailureCarriesProblemAndKeepsChanges() {
        ScriptedAction approach = new ScriptedAction("走向床边", ActionStatus.done());
        Problem refused = Problem.of(Problem.Kind.REFUSED_BY_GAME, "游戏提示：附近有怪物，不能休息");
        ScriptedAction lieDown = new ScriptedAction("右键床头", ActionStatus.failed(refused));
        var task = new GoToBed(Map.of(Phase.APPROACH, approach, Phase.LIE_DOWN, lieDown), new ProgressTracker(20, 1000));

        TaskResult result = resultOf(runUntilFinished(task, 10));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(refused, result.problem());
        assertEquals(List.of("躺下"), result.remaining(), "没做完的部分要写进结果");
    }

    @Test
    void stuckWhenActionMakesNoRealProgress() {
        ScriptedAction approach = new ScriptedAction("走向床边", ActionStatus.running());
        var task = new GoToBed(Map.of(Phase.APPROACH, approach), new ProgressTracker(5, 1000));

        TaskResult result = resultOf(runUntilFinished(task, 20));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.STUCK, result.problem().kind());
    }

    @Test
    void bouncingBetweenPhasesIsNotProgress() {
        // 在两个阶段之间来回跳、却没有任何动作报告进展：这是原地打转，必须判为卡住。
        var task = new PhasedTask<Phase>("来回跳", Phase.APPROACH, new ProgressTracker(5, 1000)) {
            @Override protected Action enter(Phase phase) {
                return null;
            }

            @Override protected Next<Phase> tick(Phase phase, TickContext context) {
                return Next.go(phase == Phase.APPROACH ? Phase.LIE_DOWN : Phase.APPROACH, "换个阶段试试");
            }
        };

        TaskResult result = resultOf(runUntilFinished(task, 20));

        assertEquals(Problem.Kind.STUCK, result.problem().kind());
    }

    @Test
    void progressShowsRecentPhasesAndHowLongWithoutRealProgress() {
        // 在两个阶段之间来回跳：面板要看得出最近走过哪几个阶段，也要看得出多久没有真实进展、多久算卡住。
        var task = new PhasedTask<Phase>("来回跳", Phase.APPROACH, new ProgressTracker(100, 1000)) {
            @Override protected Action enter(Phase phase) {
                return null;
            }

            @Override protected Next<Phase> tick(Phase phase, TickContext context) {
                return Next.go(phase == Phase.APPROACH ? Phase.LIE_DOWN : Phase.APPROACH, "换个阶段试试");
            }
        };
        for (int tick = 0; tick < 7; tick++) {
            task.tick(new TestTick(tick));
        }

        TaskProgress progress = task.currentProgress().orElseThrow();

        assertEquals(5, progress.recentPhases().size(), "只回看最近五次换阶段");
        assertEquals(new TaskProgress.PhaseChange("APPROACH", "LIE_DOWN", "换个阶段试试"), progress.recentPhases().getLast());
        assertEquals("LIE_DOWN", progress.phase());
        assertEquals("开始", progress.lastProgress());
        assertEquals(7, progress.ticksSinceProgress(), "换阶段本身不算进展");
        assertEquals(100, progress.stuckAfterTicks());
        assertEquals(1000, progress.maxTicks());
    }

    @Test
    void finishedTaskHasNoProgressToShow() {
        ScriptedAction approach = new ScriptedAction("走向床边", ActionStatus.done());
        ScriptedAction lieDown = new ScriptedAction("右键床头", ActionStatus.done());
        var task = new GoToBed(Map.of(Phase.APPROACH, approach, Phase.LIE_DOWN, lieDown), new ProgressTracker(20, 1000));

        runUntilFinished(task, 10);

        assertTrue(task.currentProgress().isEmpty(), "结束了就没有进展可说");
    }

    @Test
    void pauseReachesActionAndCloseReportsWhatHappened() {
        ScriptedAction approach = new ScriptedAction("走向床边", ActionStatus.progressed());
        var task = new GoToBed(Map.of(Phase.APPROACH, approach), new ProgressTracker(20, 1000));
        task.tick(new TestTick(0));
        // 走到一半已经挪动了位置：被替换时这条变化也要出现在结果里。
        task.recordChange(Change.of(Change.Kind.MOVED, "minecraft:player", 1));

        task.pause();
        TaskResult result = task.close(CloseReason.REPLACED);

        assertEquals(1, approach.pauses, "被打断时要暂停动作");
        assertEquals(1, approach.closes, "被替换时要收尾动作");
        assertEquals(TaskResult.Status.CANCELLED, result.status());
        assertTrue(result.summary().contains("替换"));
        assertEquals(1, result.changes().size(), "被替换也要如实交代已经发生的变化");
        assertEquals(List.of("躺下"), result.remaining());
    }

    @Test
    void reportsBetweenActionsWhenNoActionIsRunning() {
        var task = new GoToBed(Map.of(), new ProgressTracker(20, 1000));
        task.start(new TestTick(0));

        assertEquals(Interruptibility.BETWEEN_ACTIONS, task.interruptibility(new TestTick(0)),
                "没有进行中的动作时处在两个动作之间，不急的事可以插进来");
        assertTrue(task.describe().startsWith("测试：APPROACH"));
    }
}
