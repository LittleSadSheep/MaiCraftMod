// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.FakeCushion;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.TestPlayer;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.TestTick;

/** 落地防护临时任务：有水桶就放水缓冲，没有就如实交代做不了，不假装自救成功。 */
class FallGuardTaskTest {

    @Test
    void failsHonestlyWithoutABucket() {
        FallGuardTask task = new FallGuardTask(() -> null);
        TestTick tick = new TestTick(new TestPlayer());
        task.start(tick);

        TickResult result = task.tick(tick);

        assertTrue(result instanceof TickResult.Finished);
        TaskResult finished = ((TickResult.Finished) result).result();
        assertEquals(TaskResult.Status.FAILED, finished.status());
        assertEquals(Problem.Kind.DANGER, finished.problem().kind());
        assertTrue(finished.problem().message().contains("水桶"), "问题要写明缺的是水桶缓冲");
    }

    @Test
    void finishesWhenTheWaterGoesOut() {
        FallGuardTask task = new FallGuardTask(() -> FakeCushion.succeedingAfter(3));
        TestTick tick = new TestTick(new TestPlayer());
        task.start(tick);

        TaskResult result = null;
        for (int i = 0; i < 10; i++) {
            TickResult tickResult = task.tick(tick);
            if (tickResult instanceof TickResult.Finished finished) {
                result = finished.result();
                break;
            }
        }

        assertTrue(result != null, "水放出去之后应当结束");
        assertEquals(TaskResult.Status.DONE, result.status());
    }

    @Test
    void reportsFailureWhenTheCushionFails() {
        FallGuardTask task = new FallGuardTask(() -> FakeCushion.failing());
        TestTick tick = new TestTick(new TestPlayer());
        task.start(tick);

        TaskResult result = null;
        for (int i = 0; i < 10; i++) {
            TickResult tickResult = task.tick(tick);
            if (tickResult instanceof TickResult.Finished finished) {
                result = finished.result();
                break;
            }
        }

        assertTrue(result != null);
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.REFUSED_BY_GAME, result.problem().kind());
    }

    @Test
    void reportsItsPhaseInChinese() {
        FallGuardTask task = new FallGuardTask(() -> FakeCushion.succeedingAfter(3));
        assertTrue(task.describe().contains("放水"), "调试面板要能看懂此刻在做什么");
    }
}
