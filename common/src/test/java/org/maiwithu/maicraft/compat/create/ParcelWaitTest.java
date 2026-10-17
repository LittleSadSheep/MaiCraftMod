// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.function.ToIntFunction;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 下单后在取货口跟前等包裹：每秒看一眼，够数就取，一分钟没新包裹或满五分钟收场；
 * 放进"连续一分钟没有真实进展算卡住"的拿东西任务里，等包裹不被外层抢先判卡住。
 */
class ParcelWaitTest {

    /** 剧本：每隔 everyTicks 刻到一件，最多到 most 件。 */
    private static ToIntFunction<TickContext> oneEvery(int everyTicks, int most) {
        return tick -> (int) Math.min(most, tick.gameTick() / everyTicks);
    }

    /** 一刻一刻推进到等完，返回在第几刻做完。 */
    private static long runToEnd(ParcelWait wait, long startTick) {
        for (long tick = startTick + 1; tick <= startTick + ParcelWait.TOTAL_TICKS * 2L; tick++) {
            if (wait.tick(TickContext.of(tick, null)) instanceof ActionStatus.Done) return tick - startTick;
        }
        throw new AssertionError("等了十分钟还没收场");
    }

    /** 和拿东西任务同一套进度跟踪：连续一分钟没有真实进展算卡住，最多做半小时；唯一的一段就是等包裹。 */
    private static final class WaitingTask extends PhasedTask<WaitingTask.Phase> {
        enum Phase { WAIT }

        private final ParcelWait wait;

        WaitingTask(ParcelWait wait) {
            super("拿东西", Phase.WAIT, new ProgressTracker(20L * 60, 20L * 60 * 30));
            this.wait = wait;
        }

        @Override protected Action enter(Phase phase) {
            return wait;
        }

        @Override protected Next<Phase> tick(Phase phase, TickContext context) {
            return runActionThen(context, () -> Next.done(TaskResult.builder(TaskResult.Status.DONE,
                    "等完了，到了 " + wait.arrived() + " 件").build()));
        }
    }

    /** 推进任务直到收场，返回结果；收场的刻数记进 endedAt[0]。 */
    private static TaskResult runTask(WaitingTask task, long[] endedAt) {
        task.start(TickContext.of(0, null));
        for (long tick = 1; tick <= 20L * 60 * 30; tick++) {
            if (task.tick(TickContext.of(tick, null)) instanceof TickResult.Finished finished) {
                endedAt[0] = tick;
                return finished.result();
            }
        }
        throw new AssertionError("半小时还没收场");
    }

    @Test
    void 包裹每五十秒到一件_要三件_等两分半拿够_不在一分钟时判卡住() {
        // 链条输送机长、包裹一件件来：每件之间不到一分钟，按规格接着等，第三件到了去取。
        long[] endedAt = new long[1];
        TaskResult result = runTask(new WaitingTask(new ParcelWait(3, oneEvery(20 * 50, 3))), endedAt);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(20L * 150, endedAt[0], "第三件在第 150 秒到，看到就去取");
        assertEquals("等完了，到了 3 件", result.summary());
    }

    @Test
    void 包裹一直陆续到_五分钟封顶_不在一分钟时判卡住() {
        long[] endedAt = new long[1];
        TaskResult result = runTask(new WaitingTask(new ParcelWait(10, oneEvery(20 * 50, 10))), endedAt);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(ParcelWait.TOTAL_TICKS, endedAt[0], "等满五分钟收场，取已经到的");
        assertEquals("等完了，到了 6 件", result.summary());
    }

    @Test
    void 一件都不来_一分钟时由等包裹自己收场() {
        long[] endedAt = new long[1];
        TaskResult result = runTask(new WaitingTask(new ParcelWait(5, tick -> 0)), endedAt);
        assertEquals(TaskResult.Status.DONE, result.status(), "收场的是等包裹自己的一分钟，不是外层判卡住");
        assertEquals(ParcelWait.IDLE_TICKS, endedAt[0]);
        assertEquals("等完了，到了 0 件", result.summary());
    }

    @Test
    void 每秒看一眼_期限以内每看一眼报一次进展() {
        ParcelWait wait = new ParcelWait(5, tick -> 0);
        for (int tick = 1; tick < ParcelWait.LOOK_TICKS; tick++) {
            assertEquals(ActionStatus.running(), wait.tick(TickContext.of(tick, null)), "没到看的时候");
        }
        assertEquals(ActionStatus.progressed(), wait.tick(TickContext.of(ParcelWait.LOOK_TICKS, null)),
                "看了一眼、还在期限以内：报一次进展，判卡住交给等包裹自己的期限");
    }

    @Test
    void 新包裹重新计时_一分钟没新的才收场() {
        int[] inPort = {0};
        ParcelWait wait = new ParcelWait(5, tick -> inPort[0]);
        for (int tick = 1; tick < 600; tick++) wait.tick(TickContext.of(tick, null));
        inPort[0] = 2;
        assertEquals(ActionStatus.progressed(), wait.tick(TickContext.of(600, null)), "第 30 秒到了两件");
        for (int tick = 601; tick < 600 + ParcelWait.IDLE_TICKS; tick++) {
            assertInstanceOf(ActionStatus.Running.class, wait.tick(TickContext.of(tick, null)), "第 " + tick + " 刻");
        }
        assertEquals(ActionStatus.done(), wait.tick(TickContext.of(600 + ParcelWait.IDLE_TICKS, null)),
                "两件到了以后一分钟没新的，收场");
        assertEquals(2, wait.arrived());
    }

    @Test
    void 走过来路上已经到齐_第一眼就去取() {
        ParcelWait wait = new ParcelWait(4, tick -> 4);
        assertEquals(ParcelWait.LOOK_TICKS, runToEnd(wait, 0));
        assertEquals(4, wait.arrived());
    }

    @Test
    void 游戏刻很大时开始等_第一眼不收场() {
        // 世界已经跑了好几天：等包裹按自己等了多久算，不拿世界刻和没起算的时刻比。
        ParcelWait wait = new ParcelWait(5, tick -> 0);
        long start = 20L * 60 * 60 * 24 * 7;
        assertEquals(ParcelWait.IDLE_TICKS, runToEnd(wait, start));
    }
}
