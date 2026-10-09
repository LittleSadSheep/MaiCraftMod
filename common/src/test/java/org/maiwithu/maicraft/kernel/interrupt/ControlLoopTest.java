// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.kernel.task.Urgency;

import java.util.List;
import java.util.function.Supplier;

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

    private static final TickContext TICK = new TestContext(1);

    @Test
    void noNeedFiresSoMainTaskAdvances() {
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.never("饥饿")));
        loop.setMainTask(main);

        ControlLoop.Decision decision = loop.tick(TICK);

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
        assertSame(ControlLoop.Decision.IDLE, loop.tick(TICK));
    }

    @Test
    void noTaskSoEvenLaterNeedGetsItsTurn() {
        // 手上没有任务就没有东西要保护：饿了也能马上开吃，不用等空当。
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.always("饥饿", Urgency.LATER)));

        ControlLoop.Decision.Advanced advanced = assertAdvanced(loop.tick(TICK));

        assertEquals("饥饿", advanced.interrupting().name());
        assertNull(advanced.deferred());
    }

    @Test
    void laterNeedWaitsForGapBetweenActions() {
        // 挖矿挖到一半饿了：忍到两个动作之间再吃。
        FakeTask main = new FakeTask("挖矿", Interruptibility.BETWEEN_ACTIONS);
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.always("饥饿", Urgency.LATER)));
        loop.setMainTask(main);

        assertAdvancedIsTempTask(loop.tick(TICK), "饥饿");

        assertEquals(1, main.pauses);
    }

    @Test
    void laterNeedHoldsBackWhileWorking() {
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        FakeNeed hunger = FakeNeed.always("饥饿", Urgency.LATER);
        ControlLoop loop = new ControlLoop(List.of(hunger));
        loop.setMainTask(main);

        ControlLoop.Decision.Advanced advanced = assertAdvanced(loop.tick(TICK));

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

        ControlLoop.Decision.Advanced advanced = assertAdvanced(loop.tick(TICK));

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

        assertAdvancedIsTempTask(loop.tick(TICK), "自卫");

        assertEquals(1, main.pauses);
    }

    @Test
    void nowNeedInterruptsEvenWhenStoppingIsUnsafe() {
        // 正往虚空掉：悬空也得马上自救，因为继续手上的活只会死得更快。
        FakeTask main = new FakeTask("搭桥", Interruptibility.UNSAFE_TO_STOP);
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.always("坠落", Urgency.NOW)));
        loop.setMainTask(main);

        assertAdvancedIsTempTask(loop.tick(TICK), "坠落");

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

        assertAdvancedIsTempTask(loop.tick(TICK), "自卫");

        assertEquals(0, hunger.created);
    }

    @Test
    void firstRegisteredNeedWinsWhenEquallyUrgent() {
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        ControlLoop loop = new ControlLoop(List.of(
                FakeNeed.always("自卫", Urgency.SOON), FakeNeed.always("换气", Urgency.SOON)));
        loop.setMainTask(main);

        assertAdvancedIsTempTask(loop.tick(TICK), "自卫");
    }

    @Test
    void mainTaskResumesAfterTempTaskFinishes() {
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.of("换气",
                () -> new FakeTask("换气临时", Interruptibility.WORKING).finishingAfter(2),
                Urgency.NOW, null)));
        loop.setMainTask(main);
        assertAdvancedIsTempTask(loop.tick(TICK), "换气");

        // 临时任务这一刻做完了；它是被换气插进来的，结束的一刻仍算在它头上。
        ControlLoop.Decision.Advanced finished = assertAdvanced(loop.tick(TICK));
        assertEquals("换气", finished.interrupting().name());
        assertEquals(TaskResult.Status.DONE, finished.finished().status());

        // 下一刻轮到主任务：从原地接着做，不再 start，也没有再被暂停；被打断的那两刻没有轮到它。
        ControlLoop.Decision.Advanced resumed = assertAdvanced(loop.tick(TICK));
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
        assertAdvancedIsTempTask(loop.tick(TICK), "换气");
        loop.tick(TICK);
        loop.tick(TICK);

        // 它的临时任务还在推进（做完的另说），不再为同一个需求创建第二个临时任务。
        assertEquals(1, breath.created);
    }

    @Test
    void finishingTaskIsClosedOnceAndLoopGoesIdle() {
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING).finishingAfter(1);
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.never("饥饿")));
        loop.setMainTask(main);

        ControlLoop.Decision.Advanced finished = assertAdvanced(loop.tick(TICK));
        assertSame(main, finished.task());
        assertEquals(TaskResult.Status.DONE, finished.finished().status());
        assertTrue(main.closed);
        assertEquals(CloseReason.FINISHED, main.closeReason);

        assertSame(ControlLoop.Decision.IDLE, loop.tick(TICK));
        assertNull(loop.currentTask());
    }

    @Test
    void setMainTaskReplacesRunningMainWithReplacedReason() {
        FakeTask old = new FakeTask("旧活", Interruptibility.WORKING);
        ControlLoop loop = new ControlLoop(List.of(FakeNeed.never("饥饿")));
        loop.setMainTask(old);
        loop.tick(TICK);

        FakeTask replacement = new FakeTask("新活", Interruptibility.WORKING);
        loop.setMainTask(replacement);
        ControlLoop.Decision.Advanced advanced = assertAdvanced(loop.tick(TICK));

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
        assertAdvancedIsTempTask(loop.tick(TICK), "换气");

        // 主任务被压在临时任务下面时被替换：临时任务不受影响，做完后轮到的是新主任务。
        FakeTask replacement = new FakeTask("新活", Interruptibility.WORKING);
        loop.setMainTask(replacement);
        assertTrue(old.closed);
        assertEquals(CloseReason.REPLACED, old.closeReason);

        ControlLoop.Decision.Advanced finished = assertAdvanced(loop.tick(TICK));
        assertEquals("换气", finished.interrupting().name());
        ControlLoop.Decision.Advanced resumed = assertAdvanced(loop.tick(TICK));
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
        assertAdvancedIsTempTask(loop.tick(TICK), "换气");

        assertAdvancedIsTempTask(loop.tick(TICK), "坠落");
        assertEquals(1, breath.pauses);

        // 坠落这一刻做完并收尾；下一刻回到换气，换气做完后回到主任务。
        ControlLoop.Decision.Advanced fallFinished = assertAdvanced(loop.tick(TICK));
        assertEquals("坠落", fallFinished.interrupting().name());
        assertEquals(TaskResult.Status.DONE, fallFinished.finished().status());
        assertAdvancedIsTempTask(loop.tick(TICK), "换气");
        ControlLoop.Decision.Advanced breathFinished = assertAdvanced(loop.tick(TICK));
        assertEquals("换气", breathFinished.interrupting().name());
        assertEquals(TaskResult.Status.DONE, breathFinished.finished().status());
        assertSame(main, assertAdvanced(loop.tick(TICK)).task());
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
    @Test
    void needWhoseTempTaskFailedWaitsBeforeTakingOverAgain() {
        // 被埋却刨不动（头上是基岩）：没做成就先缓一阵，主任务有机会动，而不是每刻重建一个刨不动的临时任务。
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        FakeNeed buried = FakeNeed.always("刨出", Urgency.NOW, () -> new FailingTask("刨出临时"));
        ControlLoop loop = new ControlLoop(List.of(buried));
        loop.setMainTask(main);

        for (long tick = 0; tick < ControlLoop.RETRY_AFTER_FAILED_TICKS; tick++) {
            loop.tick(new TestContext(tick));
        }

        assertEquals(1, buried.created, "同样急的需求在缓冲期内不再重建临时任务");
        assertTrue(main.ticks > 0, "缓冲期里主任务照常推进");
        loop.tick(new TestContext(ControlLoop.RETRY_AFTER_FAILED_TICKS + 1));
        assertEquals(2, buried.created, "缓冲期过了再试一次");
    }

    @Test
    void mainTaskThatTurnedSurvivalNeedsOffIsNeverInterrupted() {
        // 寻死这类任务把 survival_needs 关掉：快淹死也不插换气，手上的事照常推进。
        FakeTask main = new SurvivalOffTask();
        FakeNeed breath = FakeNeed.always("换气", Urgency.NOW);
        ControlLoop loop = new ControlLoop(List.of(breath));
        loop.setMainTask(main);

        for (long tick = 0; tick < 5; tick++) {
            loop.tick(new TestContext(tick));
        }

        assertEquals(0, breath.created, "生存需求关掉时不建临时任务");
        assertTrue(main.ticks > 0, "主任务照常推进");
    }

    /** 许可里关掉了生存需求的主任务替身。 */
    static final class SurvivalOffTask extends FakeTask implements SurvivalNeedsOff {
        SurvivalOffTask() {
            super("寻死", Interruptibility.WORKING);
        }

        @Override public boolean survivalNeedsOff() {
            return true;
        }
    }

    @Test
    void needThatKeepsFailingWaitsLongerEachTime() {
        // 饿了却弄不到吃的：接连没做成，等待逐次加倍，不每隔五秒就打断一次手上的活。
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        FakeNeed hunger = FakeNeed.always("饥饿", Urgency.SOON, () -> new FailingTask("进食临时"));
        ControlLoop loop = new ControlLoop(List.of(hunger));
        loop.setMainTask(main);

        long secondTry = ControlLoop.RETRY_AFTER_FAILED_TICKS;
        long thirdTry = secondTry + 2 * ControlLoop.RETRY_AFTER_FAILED_TICKS;
        for (long tick = 0; tick < thirdTry; tick++) {
            loop.tick(new TestContext(tick));
        }

        assertEquals(2, hunger.created, "第二次没做成后要等两倍的时间");
        loop.tick(new TestContext(thirdTry));
        assertEquals(3, hunger.created, "加倍后的等待过了再试一次");
    }

    @Test
    void needThatGotMoreUrgentDoesNotWaitOutItsBackoff() {
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        FakeNeed breath = FakeNeed.of("换气", () -> new FailingTask("换气临时"),
                Urgency.SOON, Urgency.SOON, Urgency.NOW);
        ControlLoop loop = new ControlLoop(List.of(breath));
        loop.setMainTask(main);

        loop.tick(new TestContext(0));
        loop.tick(new TestContext(1));
        loop.tick(new TestContext(2));

        assertEquals(2, breath.created, "从尽快升到立刻，不等缓冲期就再试");
    }

    @Test
    void brokenTempTaskBecomesAResultInsteadOfCrashingTheLoop() {
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        FakeNeed broken = FakeNeed.always("坏需求", Urgency.NOW, () -> new FakeTask("坏临时", Interruptibility.WORKING) {
            @Override public TickResult tick(TickContext context) {
                throw new IllegalStateException("临时任务出错");
            }
        });
        ControlLoop loop = new ControlLoop(List.of(broken));
        loop.setMainTask(main);

        ControlLoop.Decision.Advanced advanced = assertAdvanced(loop.tick(TICK));

        assertEquals(Problem.Kind.INTERNAL_ERROR, advanced.finished().problem().kind());
        assertSame(main, loop.currentTask(), "出错的临时任务弹出，主任务还在");
    }

    @Test
    void replacingOrEndingTheMainTaskHandsBackWhatItDid() {
        FakeTask first = new FakeTask("挖矿", Interruptibility.WORKING);
        FakeTask second = new FakeTask("砍树", Interruptibility.WORKING);
        ControlLoop loop = new ControlLoop(List.of());
        loop.setMainTask(first);
        loop.tick(TICK);

        TaskResult replaced = loop.setMainTask(second);

        assertEquals("挖矿收尾", replaced.summary(), "被替换的主任务交代的结果交回给调用方");
        assertEquals(CloseReason.REPLACED, first.closeReason);
        TaskResult ended = loop.endMainTask(CloseReason.CANCELLED);
        assertEquals("砍树收尾", ended.summary(), "还没推进过的主任务也要收尾交代");
        assertEquals(CloseReason.CANCELLED, second.closeReason);
        assertSame(ControlLoop.Decision.IDLE, loop.tick(TICK));
    }

    /** 一推进就失败的临时任务：模拟刨不动、换不上气这类自救没成功的处境。 */
    static class FailingTask extends FakeTask {
        FailingTask(String name) {
            super(name, Interruptibility.UNSAFE_TO_STOP);
        }

        @Override public TickResult tick(TickContext context) {
            ticks++;
            return TickResult.finished(TaskResult.failed("没做成", Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "做不了")));
        }
    }

    private record TestContext(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            return null;
        }
    }

    /** 任务替身：按脚本报告可打断性，做到指定刻数后结束；记下被 start、推进、暂停、收尾的次数。 */
    static class FakeTask implements Task {
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
        private final Supplier<Task> factory;
        private int calls;
        int created;

        private FakeNeed(String name, Urgency[] script, Supplier<Task> factory) {
            this.name = name;
            this.script = script;
            this.factory = factory;
        }

        /** 每刻都以同样的急迫程度需要处理。 */
        static FakeNeed always(String name, Urgency urgency) {
            return of(name, urgency);
        }

        static FakeNeed always(String name, Urgency urgency, Supplier<Task> factory) {
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

        static FakeNeed of(String name, Supplier<Task> factory, Urgency... script) {
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
