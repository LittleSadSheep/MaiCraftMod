// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.FakeBreaking;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.TestPlayer;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.TestTick;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.inAir;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.scripted;

/** 刨出临时任务：朝埋住头的那一格挖到脱身；空手挖石头这类慢活留足时间，真挖不动才按卡住收场。 */
class DigOutTaskTest {

    private static final BlockPos HEAD_CELL = new BlockPos(10, 64, -3);

    private static SurvivalSituation buried() {
        return SurvivalFakes.situation(0, 20.0, 64.0, 37.0f, false, 10, false, true, HEAD_CELL, false);
    }

    @Test
    void digsTheBuriedCellUntilFree() {
        TestPlayer player = new TestPlayer();
        TestTick tick = new TestTick(player);
        FakeBreaking digging = new FakeBreaking(ActionStatus.progressed(), ActionStatus.running());
        DigOutTask task = new DigOutTask(scripted(buried(), buried(), inAir(64.0)), digging);
        task.start(tick);

        assertTrue(task.tick(tick) instanceof TickResult.Running);
        player.nextTick();
        assertTrue(task.tick(tick) instanceof TickResult.Running);
        player.nextTick();
        TaskResult finished = ((TickResult.Finished) task.tick(tick)).result();
        task.close(CloseReason.FINISHED);

        assertEquals(TaskResult.Status.DONE, finished.status());
        assertEquals(HEAD_CELL, digging.dug.get(0), "朝埋住头的那一格挖");
        assertEquals(1, finished.changes().size(), "挖开的那一格记进结果");
        assertTrue(digging.closed, "结束时挖掘动作被收尾");
    }

    @Test
    void slowDiggingIsNotGivenUpTooEarly() {
        // 空手挖石头要七秒多：十秒还没挖开不能就放弃。
        TestPlayer player = new TestPlayer();
        TestTick tick = new TestTick(player);
        DigOutTask task = new DigOutTask(scripted(buried()), new FakeBreaking(ActionStatus.running()));
        task.start(tick);
        for (int i = 0; i < 200; i++) {
            player.nextTick();
            assertTrue(task.tick(tick) instanceof TickResult.Running, "第 " + i + " 刻不该收场");
        }
    }

    @Test
    void stuckWhenNoBlockBreaksForHalfAMinute() {
        TestPlayer player = new TestPlayer();
        TestTick tick = new TestTick(player);
        FakeBreaking digging = new FakeBreaking(ActionStatus.running());
        DigOutTask task = new DigOutTask(scripted(buried()), digging);
        task.start(tick);

        TaskResult result = null;
        for (int i = 0; i < 700 && result == null; i++) {
            player.nextTick();
            if (task.tick(tick) instanceof TickResult.Finished finished) result = finished.result();
        }
        task.close(CloseReason.FINISHED);

        assertTrue(result != null, "一直挖不开时应当收场");
        assertSame(Problem.Kind.STUCK, result.problem().kind());
        assertTrue(digging.closed, "收场时挖掘动作被收尾");
    }
}
