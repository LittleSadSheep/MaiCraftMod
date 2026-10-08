// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.outcome.Blocker;
import org.maiwithu.maicraft.kernel.outcome.Effect;
import org.maiwithu.maicraft.kernel.outcome.Outcome;
import org.maiwithu.maicraft.kernel.progress.ProgressMeter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 阶段式执行器的行为：阶段走向、停滞判定、抢占与收尾、回执组装。 */
class PhasedExecutorTest {

    enum Phase { APPROACH, ACT, DECIDE }

    /** 用一张"阶段 → 子步骤"表和一个走向函数拼出的执行器，模拟"走到床边 → 躺下"这类两段流程。 */
    static final class TwoStepExecutor extends PhasedExecutor<Phase> {
        final Map<Phase, ScriptedStep> steps;

        TwoStepExecutor(Map<Phase, ScriptedStep> steps, ProgressMeter meter) {
            super("测试", Phase.APPROACH, meter);
            this.steps = steps;
        }

        @Override protected Step enter(Phase phase) {
            return steps.get(phase);
        }

        @Override protected Next<Phase> tick(Phase phase, TickContext context) {
            return switch (phase) {
                case APPROACH -> runThen(context, () -> Next.go(Phase.ACT, "到了"));
                case ACT -> runThen(context, () -> {
                    achieved(Effect.of(Effect.Kind.OTHER, "minecraft:red_bed", 1));
                    return Next.done(Outcome.done("躺下了"));
                });
                case DECIDE -> Next.stay();
            };
        }

        @Override protected List<String> remaining() {
            return List.of("躺下");
        }
    }

    private static TaskStatus runUntilFinished(Task task, int maxTicks) {
        TaskStatus status = TaskStatus.RUNNING;
        for (int tick = 0; tick < maxTicks && status instanceof TaskStatus.Running; tick++) {
            status = task.tick(new TestTick(tick));
        }
        return status;
    }

    @Test
    void walksThroughPhasesAndAssemblesOutcome() {
        ScriptedStep approach = new ScriptedStep("走向床边", StepStatus.progressed(), StepStatus.done());
        ScriptedStep act = new ScriptedStep("右键床头", StepStatus.running(), StepStatus.done());
        var executor = new TwoStepExecutor(Map.of(Phase.APPROACH, approach, Phase.ACT, act), new ProgressMeter(20, 1000));

        TaskStatus status = runUntilFinished(executor, 10);

        Outcome outcome = assertInstanceOf(TaskStatus.Finished.class, status).outcome();
        assertEquals(Outcome.Status.DONE, outcome.status());
        assertEquals("躺下了", outcome.summary());
        assertEquals(1, outcome.achieved().size(), "执行中记下的效果要进回执");
        assertEquals(1, approach.closes, "换阶段时旧子步骤要收尾");
        assertEquals(1, act.closes, "结束时当前子步骤要收尾");
    }

    @Test
    void stepFailureCarriesBlockerAndKeepsAchievedEffects() {
        ScriptedStep approach = new ScriptedStep("走向床边", StepStatus.done());
        Blocker refused = Blocker.of(Blocker.Kind.NATIVE_REFUSED, "服务器说附近有怪物");
        ScriptedStep act = new ScriptedStep("右键床头", StepStatus.failed(refused));
        var executor = new TwoStepExecutor(Map.of(Phase.APPROACH, approach, Phase.ACT, act), new ProgressMeter(20, 1000));

        Outcome outcome = assertInstanceOf(TaskStatus.Finished.class, runUntilFinished(executor, 10)).outcome();

        assertEquals(Outcome.Status.FAILED, outcome.status());
        assertEquals(refused, outcome.blocker());
        assertEquals(List.of("躺下"), outcome.remaining(), "没做完的部分要写进回执");
    }

    @Test
    void stallsWhenStepMakesNoRealProgress() {
        ScriptedStep approach = new ScriptedStep("走向床边", StepStatus.running());
        var executor = new TwoStepExecutor(Map.of(Phase.APPROACH, approach), new ProgressMeter(5, 1000));

        Outcome outcome = assertInstanceOf(TaskStatus.Finished.class, runUntilFinished(executor, 20)).outcome();

        assertEquals(Outcome.Status.FAILED, outcome.status());
        assertEquals(Blocker.Kind.STALLED, outcome.blocker().kind());
    }

    @Test
    void bouncingBetweenPhasesIsNotProgress() {
        // 在两个阶段之间来回跳、却没有任何子步骤报告进展：这是原地打转，必须被判停滞。
        var executor = new PhasedExecutor<Phase>("来回跳", Phase.APPROACH, new ProgressMeter(5, 1000)) {
            @Override protected Step enter(Phase phase) {
                return null;
            }

            @Override protected Next<Phase> tick(Phase phase, TickContext context) {
                return Next.go(phase == Phase.APPROACH ? Phase.ACT : Phase.APPROACH, "换个阶段试试");
            }
        };

        Outcome outcome = assertInstanceOf(TaskStatus.Finished.class, runUntilFinished(executor, 20)).outcome();

        assertEquals(Blocker.Kind.STALLED, outcome.blocker().kind());
    }

    @Test
    void pauseForwardsToStepAndCloseReportsWhatHappened() {
        ScriptedStep approach = new ScriptedStep("走向床边", StepStatus.progressed());
        var executor = new TwoStepExecutor(Map.of(Phase.APPROACH, approach), new ProgressMeter(20, 1000));
        executor.tick(new TestTick(0));
        // 走到一半已经挪动了位置：被替换时这条效果也要出现在回执里。
        executor.achieved(Effect.of(Effect.Kind.MOVED, "minecraft:player", 1));

        executor.pause();
        Outcome outcome = executor.close(CloseReason.REPLACED);

        assertEquals(1, approach.pauses, "被抢占时要暂停子步骤");
        assertEquals(1, approach.closes, "被替换时要收尾子步骤");
        assertEquals(Outcome.Status.CANCELLED, outcome.status());
        assertTrue(outcome.summary().contains("替换"));
        assertEquals(1, outcome.achieved().size(), "被替换也要如实交代已经发生的效果");
        assertEquals(List.of("躺下"), outcome.remaining());
    }

    @Test
    void reportsFreeBetweenPhases() {
        var executor = new TwoStepExecutor(Map.of(), new ProgressMeter(20, 1000));
        executor.start(new TestTick(0));

        assertEquals(Interruptibility.FREE, executor.interruptibility(new TestTick(0)),
                "没有进行中的子步骤时处在阶段之间，舒适需求可以插进来");
        assertTrue(executor.describe().startsWith("测试：APPROACH"));
    }
}
