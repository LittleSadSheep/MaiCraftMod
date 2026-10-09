// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.kernel.task.Urgency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.TestPlayer;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.TestTick;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.inAir;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.scripted;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.underwater;

/**
 * 三项基本生存需求接进控制循环的调度：在水里干活会先被换气临时任务接管，
 * 出水后主流程从原地恢复；同样急时被埋先于换气；悬在虚空上时落地防护打断一切。
 */
class SurvivalNeedsTest {

    /** 任务替身：只按给定的可打断性推进，供控制循环调度。 */
    static final class MainTask implements Task {
        private final Interruptibility interruptibility;
        int starts;
        int pauses;

        MainTask(Interruptibility interruptibility) { this.interruptibility = interruptibility; }

        @Override public void start(TickContext context) { starts++; }
        @Override public TickResult tick(TickContext context) { return TickResult.RUNNING; }
        @Override public void pause() { pauses++; }
        @Override public TaskResult close(CloseReason reason) { return TaskResult.done("主任务收尾"); }
        @Override public Interruptibility interruptibility(TickContext context) { return interruptibility; }
        @Override public String describe() { return "主任务"; }
    }

    private static ControlLoop.Decision.Advanced advance(ControlLoop.Decision decision) {
        assertTrue(decision instanceof ControlLoop.Decision.Advanced, "应是推进，实际是 " + decision);
        return (ControlLoop.Decision.Advanced) decision;
    }

    @Test
    void drowningTakesOverFromWorkAndMainResumesAfterAir() {
        TestPlayer player = new TestPlayer();
        TestTick tick = new TestTick(player);
        // 前两刻头在水里氧气只剩一泡（立刻），第三刻头已出水。
        SurvivalSituation.SituationReader reader = scripted(underwater(40.0), underwater(40.0), inAir(41.0));
        MainTask main = new MainTask(Interruptibility.WORKING);
        ControlLoop loop = new ControlLoop(List.of(new BreathNeed(reader)));
        loop.setMainTask(main);

        // 干活挖矿时憋到告急：换气临时任务立刻接管，主任务被压栈。
        ControlLoop.Decision.Advanced first = advance(loop.tick(tick));
        assertEquals("换气", first.interrupting().name());
        assertEquals(1, main.pauses);
        assertTrue(first.task() instanceof BreathTask);

        player.nextTick();
        ControlLoop.Decision.Advanced second = advance(loop.tick(tick));
        assertEquals("换气", second.interrupting().name());

        // 头一出水，换气临时任务当刻完成。
        player.nextTick();
        ControlLoop.Decision.Advanced finished = advance(loop.tick(tick));
        assertEquals("换气", finished.interrupting().name());
        assertEquals(TaskResult.Status.DONE, finished.finished().status());

        // 下一刻轮回主流程：从原地接着做，这时才第一次 start；被压住的那几刻没有轮到它。
        player.nextTick();
        ControlLoop.Decision.Advanced resumed = advance(loop.tick(tick));
        assertSame(main, resumed.task());
        assertNull(resumed.interrupting());
        assertEquals(1, main.starts);
    }

    @Test
    void soonDrowningInterruptsWorkButNotUnsafeWork() {
        TestPlayer player = new TestPlayer();
        TestTick tick = new TestTick(player);
        // 氧气剩三泡、浅水里游上去来得及：尽快换气，但不算立刻。
        SurvivalSituation.SituationReader reader = scripted(SurvivalFakes.calm().feet(40.0).underwater(90).build());
        MainTask working = new MainTask(Interruptibility.WORKING);
        ControlLoop loop = new ControlLoop(List.of(new BreathNeed(reader)));
        loop.setMainTask(working);

        // 正常干活：尽快处理的能插进来，主任务被暂停。
        ControlLoop.Decision.Advanced taken = advance(loop.tick(tick));
        assertEquals("换气", taken.interrupting().name());
        assertEquals(1, working.pauses);

        // 停下不安全的活（悬空搭桥）：先忍住，主任务继续，被按住的需求留给调用方。
        MainTask unsafe = new MainTask(Interruptibility.UNSAFE_TO_STOP);
        ControlLoop unsafeLoop = new ControlLoop(List.of(new BreathNeed(scripted(
                SurvivalFakes.calm().feet(40.0).underwater(90).build()))));
        unsafeLoop.setMainTask(unsafe);
        ControlLoop.Decision.Advanced heldUnsafe = advance(unsafeLoop.tick(tick));
        assertSame(unsafe, heldUnsafe.task());
        assertEquals("换气", heldUnsafe.deferred().name());
    }

    @Test
    void buriedBeatsEquallyUrgentDrowning() {
        TestPlayer player = new TestPlayer();
        TestTick tick = new TestTick(player);
        // 又被埋又在水里憋到只剩一泡：两件都是立刻，按登记顺序被埋先处理。
        SurvivalSituation both = SurvivalFakes.calm().feet(40.0).underwater(30)
                .buriedAt(new BlockPos(1, 64, 2)).build();
        ControlLoop loop = new ControlLoop(List.of(
                new DigOutNeed(scripted(both), SurvivalFakes.FakeBreaking::new),
                new BreathNeed(scripted(both))));
        loop.setMainTask(new MainTask(Interruptibility.WORKING));

        ControlLoop.Decision.Advanced decision = advance(loop.tick(tick));

        assertEquals("刨出", decision.interrupting().name());
        assertTrue(decision.task() instanceof DigOutTask);
    }

    @Test
    void deadlyFallInterruptsEvenUnsafeWork() {
        TestPlayer player = new TestPlayer();
        TestTick tick = new TestTick(player);
        // 悬空搭桥时往虚空掉：掉下去就是死，落地防护立刻打断。
        SurvivalSituation falling = SurvivalFakes.calm().feet(200.0).fallingIntoVoid().build();
        MainTask bridging = new MainTask(Interruptibility.UNSAFE_TO_STOP);
        ControlLoop loop = new ControlLoop(List.of(new FallNeed(scripted(falling), null, null, null)));
        loop.setMainTask(bridging);

        ControlLoop.Decision.Advanced decision = advance(loop.tick(tick));

        assertEquals("落地防护", decision.interrupting().name());
        assertEquals(1, bridging.pauses);
    }

    @Test
    void healthySituationLeavesTheMainTaskAlone() {
        TestPlayer player = new TestPlayer();
        TestTick tick = new TestTick(player);
        SurvivalSituation fine = SurvivalFakes.calm().build();
        ControlLoop loop = new ControlLoop(List.of(
                new DigOutNeed(scripted(fine), SurvivalFakes.FakeBreaking::new),
                new BreathNeed(scripted(fine)),
                new FallNeed(scripted(fine), null, null, null)));
        MainTask main = new MainTask(Interruptibility.WORKING);
        loop.setMainTask(main);

        ControlLoop.Decision.Advanced decision = advance(loop.tick(tick));

        assertSame(main, decision.task());
        assertNull(decision.interrupting());
        assertNull(decision.deferred());
    }
}
