// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.kernel.task.Urgency;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制循环的调度：不打断时主任务照常推进；找空当的只在两个动作之间插进来；
 * 停下不安全时忍住；必须立刻处理的打断一切；临时任务做完后主任务从原地接着做。
 */
class ControlLoopTest {

    private static final TickContext 刻 = new TestContext(1);

    @Test
    void noNeedFiresSoMainTaskAdvances() {
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.never("饥饿")));
        loop.setMainTask(main);

        ControlLoop.Decision decision = loop.tick(刻);

        ControlLoop.Decision.Advanced advanced = assertAdvanced(decision);
        assertSame(main, advanced.task());
        assertNull(advanced.interrupting());
        assertNull(advanced.deferred());
        assertNull(advanced.finished());
        assertEquals(1, main.started);
        assertEquals(1, main.ticks);
        assertEquals(0, main.pauses);
    }

    @Test
    void nothingToRunWithNoTaskAndNoNeed() {
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.never("饥饿")));
        assertSame(ControlLoop.Decision.IDLE, loop.tick(刻));
    }

    @Test
    void noTaskSoEvenLaterNeedGetsItsTurn() {
        // 手上没有任务就没有东西要保护：饿了也能马上开吃，不用等空当。
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.always("饥饿", Urgency.LATER)));

        ControlLoop.Decision.Advanced advanced = assertAdvanced(loop.tick(刻));

        assertEquals("饥饿", advanced.interrupting().name());
        assertNull(advanced.deferred());
    }

    @Test
    void laterNeedWaitsForGapBetweenActions() {
        // 挖矿挖到一半饿了：忍到两个动作之间再吃。
        FakeTask main = new FakeTask("挖矿", Interruptibility.BETWEEN_ACTIONS);
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.always("饥饿", Urgency.LATER)));
        loop.setMainTask(main);

        assertAdvancedIsTempTask(loop.tick(刻), "饥饿");

        assertEquals(1, main.pauses);
    }

    @Test
    void laterNeedHoldsBackWhileWorking() {
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        FakeNeed hunger = FakeNeed.always("饥饿", Urgency.LATER);
        ControlLoop loop = new ControlLoop(List.of(hunger));
        loop.setMainTask(main);

        ControlLoop.Decision.Advanced advanced = assertAdvanced(loop.tick(刻));

        // 手上正在干活：饿了忍住，主任务继续；被按住的需求留给调用方提醒。
        assertSame(main, advanced.task());
        assertNull(advanced.interrupting());
        assertSame(hunger, advanced.deferred());
        assertEquals(0, hunger.created);
        assertEquals(0, main.pauses);
    }

    @Test
    void soonNeedHoldsBackWhenStoppingIsUnsafe() {
        // 悬空搭桥时被怪打了：先把这一格放稳，不中途停手。
        FakeTask main = new FakeTask("搭桥", Interruptibility.UNSAFE_TO_STOP);
        FakeNeed mob = FakeNeed.always("自卫", Urgency.SOON);
        ControlLoop loop = new ControlLoop(List.of(mob));
        loop.setMainTask(main);

        ControlLoop.Decision.Advanced advanced = assertAdvanced(loop.tick(刻));

        assertSame(main, advanced.task());
        assertSame(mob, advanced.deferred());
        assertEquals(0, mob.created);
        assertEquals(0, main.pauses);
    }

    @Test
    void soonNeedInterruptsNormalWork() {
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.always("自卫", Urgency.SOON)));
        loop.setMainTask(main);

        assertAdvancedIsTempTask(loop.tick(刻), "自卫");

        assertEquals(1, main.pauses);
    }

    @Test
    void nowNeedInterruptsEvenWhenStoppingIsUnsafe() {
        // 正往虚空掉：悬空也得马上自救，因为继续手上的活只会死得更快。
        FakeTask main = new FakeTask("搭桥", Interruptibility.UNSAFE_TO_STOP);
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.always("坠落", Urgency.NOW)));
        loop.setMainTask(main);

        assertAdvancedIsTempTask(loop.tick(刻), "坠落");

        assertEquals(1, main.pauses);
    }

    @Test
    void mostUrgentNeedWinsWhenSeveralFire() {
        // 又饿又被围殴：先挨打的自卫（SOON），饿了（LATER）继续等。
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        FakeNeed hunger = FakeNeed.always("饥饿", Urgency.LATER);
        FakeNeed mob = FakeNeed.always("自卫", Urgency.SOON);
        ControlLoop loop = new ControlLoop(List.of(hunger, mob));
        loop.setMainTask(main);

        assertAdvancedIsTempTask(loop.tick(刻), "自卫");

        assertEquals(0, hunger.created);
    }

    @Test
    void firstRegisteredNeedWinsWhenEquallyUrgent() {
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        ControlLoop loop = new ControlLoop(List.of(
                FakeNeed.always("自卫", Urgency.SOON), FakeNeed.always("换气", Urgency.SOON)));
        loop.setMainTask(main);

        assertAdvancedIsTempTask(loop.tick(刻), "自卫");
    }

    @Test
    void mainTaskResumesAfterTempTaskFinishes() {
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.of("换气",
                () -> new FakeTask("换气临时", Interruptibility.WORKING).finishingAfter(2),
                Urgency.NOW, null)));
        loop.setMainTask(main);
        assertAdvancedIsTempTask(loop.tick(刻), "换气");

        // 临时任务这一刻做完了；它是被换气插进来的，结束的一刻仍算在它头上。
        ControlLoop.Decision.Advanced finished = assertAdvanced(loop.tick(刻));
        assertEquals("换气", finished.interrupting().name());
        assertEquals(TaskResult.Status.DONE, finished.finished().status());

        // 下一刻轮回主任务：从原地接着做，不再 start，也没有再被暂停；被打断的那两刻没有轮到它。
        ControlLoop.Decision.Advanced resumed = assertAdvanced(loop.tick(刻));
        assertSame(main, resumed.task());
        assertNull(resumed.interrupting());
        assertEquals(1, main.started);
        assertEquals(1, main.pauses);
        assertEquals(1, main.ticks);
    }

    @Test
    void needDoesNotRelaunchWhileItsTempTaskIsStillLive() {
        FakeNeed breath = FakeNeed.always("换气", Urgency.NOW);
        ControlLoop loop = new ControlLoop(List.of(breath));
        loop.setMainTask(new FakeTask("挖矿", Interruptibility.WORKING));
        assertAdvancedIsTempTask(loop.tick(刻), "换气");
        loop.tick(刻);
        loop.tick(刻);

        // 它的临时任务还在推进（做完的另说），不再为同一个需求创建第二个临时任务。
        assertEquals(1, breath.created);
    }

    @Test
    void finishingTaskIsClosedOnceAndLoopGoesIdle() {
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING).finishingAfter(1);
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.never("饥饿")));
        loop.setMainTask(main);

        ControlLoop.Decision.Advanced finished = assertAdvanced(loop.tick(刻));
        assertSame(main, finished.task());
        assertEquals(TaskResult.Status.DONE, finished.finished().status());
        assertTrue(main.closed);
        assertEquals(CloseReason.FINISHED, main.closeReason);

        assertSame(ControlLoop.Decision.IDLE, loop.tick(刻));
        assertNull(loop.currentTask());
    }

    @Test
    void setMainTaskReplacesRunningMainWithReplacedReason() {
        FakeTask old = new FakeTask("旧活", Interruptibility.WORKING);
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.never("饥饿")));
        loop.setMainTask(old);
        loop.tick(刻);

        FakeTask replacement = new FakeTask("新活", Interruptibility.WORKING);
        loop.setMainTask(replacement);
        ControlLoop.Decision.Advanced advanced = assertAdvanced(loop.tick(刻));

        assertSame(replacement, advanced.task());
        assertTrue(old.closed);
        assertEquals(CloseReason.REPLACED, old.closeReason);
    }

    @Test
    void setMainTaskWhileTempTaskRunningReplacesTheSuspendedMain() {
        FakeTask old = new FakeTask("旧活", Interruptibility.WORKING);
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.of("换气",
                () -> new FakeTask("换气临时", Interruptibility.WORKING).finishingAfter(2),
                Urgency.NOW, null)));
        loop.setMainTask(old);
        assertAdvancedIsTempTask(loop.tick(刻), "换气");

        // 主任务被压在临时任务下面时被替换：临时任务不受影响，做完后轮到的是新主任务。
        FakeTask replacement = new FakeTask("新活", Interruptibility.WORKING);
        loop.setMainTask(replacement);
        assertTrue(old.closed);
        assertEquals(CloseReason.REPLACED, old.closeReason);

        ControlLoop.Decision.Advanced finished = assertAdvanced(loop.tick(刻));
        assertEquals("换气", finished.interrupting().name());
        ControlLoop.Decision.Advanced resumed = assertAdvanced(loop.tick(刻));
        assertSame(replacement, resumed.task());
        assertEquals(1, replacement.started);
    }

    @Test
    void nowNeedNestsAnotherTempTaskOnTopOfOneAlreadyRunning() {
        // 正在换气时往虚空掉了：立刻处理的连临时任务也打断。
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        FakeTask breath = new FakeTask("换气临时", Interruptibility.WORKING).finishingAfter(3);
        // 换气先插进来；第二刻才开始往虚空掉，处理完之后各自不再急。
        FakeNeed breathNeed = FakeNeed.of("换气", () -> breath, Urgency.SOON, null);
        FakeNeed fall = FakeNeed.of("坠落",
                () -> new FakeTask("坠落临时", Interruptibility.WORKING).finishingAfter(2),
                null, Urgency.NOW, null);
        ControlLoop loop = new ControlLoop(List.of(breathNeed, fall));
        loop.setMainTask(main);
        assertAdvancedIsTempTask(loop.tick(刻), "换气");

        assertAdvancedIsTempTask(loop.tick(刻), "坠落");
        assertEquals(1, breath.pauses);

        // 坠落这一刻做完并收尾；下一刻回到换气，换气做完后回到主任务。
        ControlLoop.Decision.Advanced fallFinished = assertAdvanced(loop.tick(刻));
        assertEquals("坠落", fallFinished.interrupting().name());
        assertEquals(TaskResult.Status.DONE, fallFinished.finished().status());
        assertAdvancedIsTempTask(loop.tick(刻), "换气");
        ControlLoop.Decision.Advanced breathFinished = assertAdvanced(loop.tick(刻));
        assertEquals("换气", breathFinished.interrupting().name());
        assertEquals(TaskResult.Status.DONE, breathFinished.finished().status());
        assertSame(main, assertAdvanced(loop.tick(刻)).task());
        assertEquals(1, main.started);
    }

    private void assertAdvancedIsTempTask(ControlLoop.Decision decision, String needName) {
        ControlLoop.Decision.Advanced advanced = assertAdvanced(decision);
        assertEquals(needName, advanced.interrupting().name());
        assertNull(advanced.deferred());
        assertNull(advanced.finished());
    }

    private ControlLoop.Decision.Advanced assertAdvanced(ControlLoop.Decision decision) {
        assertTrue(decision instanceof ControlLoop.Decision.Advanced, "应是推进，实际是 " + decision);
        return (ControlLoop.Decision.Advanced) decision;
    }

    /** 本刻上下文替身：离线测试不碰游戏。 */
    private record TestContext(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            return null;
        }
    }

    /** 任务替身：按脚本报告可打断性，做到指定刻数后结束；记下被 start、推进、暂停、收尾的次数。 */
    static final class FakeTask implements Task {
        private final String name;
        private final List<Interruptibility> script;
        private long finishAfterTicks = Long.MAX_VALUE;
        int started;
        int ticks;
        int pauses;
        boolean closed;
        CloseReason closeReason;

        FakeTask(String name, Interruptibility... interruptibility) {
            this.name = name;
            this.script = List.of(interruptibility);
        }

        FakeTask finishingAfter(long ticks) {
            this.finishAfterTicks = ticks;
            return this;
        }

        @Override public void start(TickContext context) {
            started++;
        }

        @Override public TickResult tick(TickContext context) {
            ticks++;
            return ticks >= finishAfterTicks
                    ? TickResult.finished(TaskResult.done(name + "做完了"))
                    : TickResult.RUNNING;
        }

        @Override public void pause() {
            pauses++;
        }

        @Override public TaskResult close(CloseReason reason) {
            closed = true;
            closeReason = reason;
            return TaskResult.done(name + "收尾");
        }

        @Override public Interruptibility interruptibility(TickContext context) {
            return script.get(Math.min(ticks, script.size() - 1));
        }

        @Override public String describe() {
            return name;
        }
    }

    /** 生存需求替身：按调用次序回答急迫程度，脚本用完后重复最后一个（null 表示不再需要处理）。 */
    static final class FakeNeed implements SurvivalNeed {
        private final String name;
        private final Urgency[] script;
        private final java.util.function.Supplier<Task> factory;
        private int calls;
        int created;

        private FakeNeed(String name, Urgency[] script, java.util.function.Supplier<Task> factory) {
            this.name = name;
            this.script = script;
            this.factory = factory;
        }

        /** 每刻都以同样的急迫程度需要处理。 */
        static FakeNeed always(String name, Urgency urgency) {
            return of(name, urgency);
        }

        static FakeNeed always(String name, Urgency urgency, java.util.function.Supplier<Task> factory) {
            return of(name, factory, urgency);
        }

        /** 从不需要处理。 */
        static FakeNeed never(String name) {
            return of(name, new Urgency[0]);
        }

        /** 按次序回答急迫程度，脚本用完后重复最后一个；写 null 表示那一次不再需要处理。 */
        static FakeNeed of(String name, Urgency... script) {
            return new FakeNeed(name, script, () -> new FakeTask(name + "临时", Interruptibility.WORKING));
        }

        static FakeNeed of(String name, java.util.function.Supplier<Task> factory, Urgency... script) {
            return new FakeNeed(name, script, factory);
        }

        @Override public String name() {
            return name;
        }

        @Override public Urgency urgency(TickContext context) {
            if (script.length == 0) return null;
            Urgency urgency = script[Math.min(calls, script.length - 1)];
            calls++;
            return urgency;
        }

        @Override public Task createTask(TickContext context) {
            created++;
            return factory.get();
        }
    }
}
