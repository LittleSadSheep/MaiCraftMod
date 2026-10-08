// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.FakeBreaking;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.TestPlayer;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.TestTick;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.inAir;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.scripted;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.situation;

/** 刨出临时任务：朝着埋住身体的格子挖，脱身即结束并结清挖掘；一直挖不开就按卡住收场。 */
class DigOutTaskTest {

    private static final BlockPos HEAD_CELL = new BlockPos(10, 64, -3);

    /** 卡在头部一格：下落 0、氧气满、头部格子埋住。 */
    private static SurvivalSituation buried() {
        return situation(0, 12.0, 64.0, 0.0f, false, 10, false, true, HEAD_CELL, false);
    }

    @Test
    void digsTheBuriedCellUntilFree() {
        TestPlayer player = new TestPlayer();
        TestTick tick = new TestTick(player);
        // 第一刻挖开一格（有真实进展），第二刻还在挖，第三刻处境已经不再被埋。
        FakeBreaking digging = new FakeBreaking(ActionStatus.progressed(), ActionStatus.running());
        DigOutTask task = new DigOutTask(scripted(buried(), buried(), inAir(64.0)), digging);
        task.start(tick);

        assertTrue(task.tick(tick) instanceof TickResult.Running);
        player.nextTick();
        assertTrue(task.tick(tick) instanceof TickResult.Running);
        player.nextTick();

        TickResult result = task.tick(tick);

        assertTrue(result instanceof TickResult.Finished);
        assertEquals(TaskResult.Status.DONE, ((TickResult.Finished) result).result().status());
        assertEquals(HEAD_CELL, digging.dug.get(0), "朝埋住身体的那一格挖");
        assertTrue(digging.stopped, "结束的当刻要结清挖掘");
    }

    @Test
    void stuckWhenNoBlockBreaksForAWhile() {
        TestPlayer player = new TestPlayer();
        TestTick tick = new TestTick(player);
        FakeBreaking digging = new FakeBreaking(ActionStatus.running());
        DigOutTask task = new DigOutTask(scripted(buried()), digging);
        task.start(tick);

        TaskResult result = null;
        for (int i = 0; i < 80; i++) {
            TickResult tickResult = task.tick(tick);
            if (tickResult instanceof TickResult.Finished finished) {
                result = finished.result();
                break;
            }
        }

        assertTrue(result != null, "一直挖不开时应当收场");
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertSame(Problem.Kind.STUCK, result.problem().kind());
        assertTrue(digging.stopped, "收场的当刻要结清挖掘");
    }
}
