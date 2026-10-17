// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.wait;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.game.world.WorldTime;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.game.player.PlayerContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 等待任务：先过 after_seconds 再查条件、条件成立才结束、被暂停的时间承认已经过去。 */
class WaitTaskTest {

    /** 世界事实替身：固定数值。 */
    private record FixedWorld(long dayTime, double health, double maxHealth, int food) implements WaitWorld {}

    /** 刻号替身：任务只读刻号。 */
    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            throw new IllegalStateException("等待任务不碰角色对象");
        }
    }

    @Test
    void elapsedCompletesAfterDelay() {
        // after_seconds=2：20 刻还没到，60 刻后完成，写明实际经过秒数。
        WaitTask task = new WaitTask(new WaitInput(WaitFor.ELAPSED, 2), ctx -> new FixedWorld(1000, 20, 20, 20));
        task.start(new Tick(0));
        // 任务从第一次被推进那刻起算：本测试里就是刻 0。
        assertTrue(task.tick(new Tick(0)) instanceof TickResult.Running);
        assertTrue(task.tick(new Tick(20)) instanceof TickResult.Running);
        TickResult result = task.tick(new Tick(60));
        TaskResult finished = assertInstanceOf(TickResult.Finished.class, result).result();
        assertEquals(TaskResult.Status.DONE, finished.status());
        WaitTask.WaitDetails details = (WaitTask.WaitDetails) finished.details();
        assertEquals(3, details.waitedSeconds());
        assertEquals("elapsed", details.conditionMet());
    }

    @Test
    void dayNightFollowWorldPhase() {
        // 白天（dayTime=1000）等 day 立即成立；等 night 继续等，直到夜里（13000+）。
        WaitTask waitDay = new WaitTask(new WaitInput(WaitFor.DAY, 0), ctx -> new FixedWorld(1000, 20, 20, 20));
        waitDay.start(new Tick(0));
        assertInstanceOf(TickResult.Finished.class, waitDay.tick(new Tick(0)));

        WaitTask waitNight = new WaitTask(new WaitInput(WaitFor.NIGHT, 0), ctx -> new FixedWorld(1000, 20, 20, 20));
        waitNight.start(new Tick(0));
        assertTrue(waitNight.tick(new Tick(0)) instanceof TickResult.Running);
        WaitTask nightReady = new WaitTask(new WaitInput(WaitFor.NIGHT, 0), ctx -> new FixedWorld(13000, 20, 20, 20));
        nightReady.start(new Tick(0));
        assertInstanceOf(TickResult.Finished.class, nightReady.tick(new Tick(0)));
        assertEquals(WorldTime.Phase.NIGHT, WorldTime.phase(13000));
    }

    @Test
    void notHungryUsesNaturalRegenLine() {
        // 饱食度 17 还在等，18 就算等到（原版自然回血线）。
        WaitTask hungry = new WaitTask(new WaitInput(WaitFor.NOT_HUNGRY, 0), ctx -> new FixedWorld(1000, 20, 20, 17));
        hungry.start(new Tick(0));
        assertTrue(hungry.tick(new Tick(0)) instanceof TickResult.Running);
        WaitTask fed = new WaitTask(new WaitInput(WaitFor.NOT_HUNGRY, 0), ctx -> new FixedWorld(1000, 20, 20, 18));
        fed.start(new Tick(0));
        assertInstanceOf(TickResult.Finished.class, fed.tick(new Tick(0)));
    }

    @Test
    void pausedTimeStillCounts() {
        // 从刻 0 开始等 5 秒；中途刻 30 到 200 之间被生存需求打断（没被推进），
        // 世界时刻照走：轮回来时已过去的时间算数。
        WaitTask task = new WaitTask(new WaitInput(WaitFor.ELAPSED, 5), ctx -> new FixedWorld(1000, 20, 20, 20));
        task.start(new Tick(0));
        assertTrue(task.tick(new Tick(30)) instanceof TickResult.Running);
        // 刻 200（已过 10 秒）恢复推进：立即完成。
        TickResult result = task.tick(new Tick(200));
        assertEquals(TaskResult.Status.DONE, assertInstanceOf(TickResult.Finished.class, result).result().status());
    }

    @Test
    void healthFullWaitsForMissingHearts() {
        WaitTask hurt = new WaitTask(new WaitInput(WaitFor.HEALTH_FULL, 0), ctx -> new FixedWorld(1000, 12, 20, 20));
        hurt.start(new Tick(0));
        assertTrue(hurt.tick(new Tick(0)) instanceof TickResult.Running);
        WaitTask healed = new WaitTask(new WaitInput(WaitFor.HEALTH_FULL, 0), ctx -> new FixedWorld(1000, 20, 20, 20));
        healed.start(new Tick(0));
        assertInstanceOf(TickResult.Finished.class, healed.tick(new Tick(0)));
    }
}
