// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.TestPlayer;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.TestTick;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.scripted;

/**
 * 落地防护：身上没东西、附近也没现成的水就如实说接不住；落点是水不用做事；下面是虚空救不了；
 * 已经没在掉了就安静收场。真正接住这一掉（拿东西、对准、放、收回）由落地救援负责。
 */
class FallGuardTaskTest {

    private final TestPlayer player = new TestPlayer();
    private final TestTick tick = new TestTick(player);

    private FallGuardTask task(SurvivalSituation... script) {
        FallGuardTask task = new FallGuardTask(scripted(script), context -> null);
        task.start(tick);
        return task;
    }

    private TaskResult finish(FallGuardTask task) {
        return ((TickResult.Finished) task.tick(tick)).result();
    }

    @Test
    void failsHonestlyWhenNothingCanCatchTheFall() {
        TaskResult result = finish(task(SurvivalFakes.calm().feet(80).fallingHard().build()));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.DANGER, result.problem().kind());
        assertTrue(result.problem().message().contains("水桶"), "问题要写明缺的是哪类东西");
    }

    @Test
    void landingInWaterNeedsNothing() {
        assertEquals(TaskResult.Status.DONE, finish(task(SurvivalFakes.calm().fallingIntoWater().build())).status());
    }

    @Test
    void voidCannotBeCushioned() {
        assertEquals(Problem.Kind.DANGER, finish(task(SurvivalFakes.calm().fallingIntoVoid().build())).problem().kind());
    }

    @Test
    void noLongerFallingEndsQuietly() {
        assertEquals(TaskResult.Status.DONE, finish(task(SurvivalFakes.calm().build())).status());
    }
}
