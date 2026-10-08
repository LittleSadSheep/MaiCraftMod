// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.TestPlayer;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.TestTick;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.inAir;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.scripted;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.underwater;

/** 换气临时任务：水下游＝抬头加按住跳跃，头出水即完成；一直上不去就按卡住收场。 */
class BreathTaskTest {

    private final TestPlayer player = new TestPlayer();
    private final TestTick tick = new TestTick(player);

    @Test
    void underwaterItHoldsJumpAndLooksUp() {
        BreathTask task = new BreathTask(scripted(underwater(40.0)));
        task.start(tick);

        TickResult result = task.tick(tick);

        assertTrue(result instanceof TickResult.Running);
        assertEquals(1, player.movements);
        PlayerInput.Movement movement = player.applied.get(0);
        assertTrue(movement.jumping(), "水里要按住跳跃才上浮");
        assertEquals(0.0f, movement.forward(), "上浮不需要前进");
        assertEquals(-90.0f, player.looks.get(0)[1], 0.001f, "镜头要压到向上");
        assertEquals(37.0f, player.looks.get(0)[0], 0.001f, "水平朝向保持不变");
    }

    @Test
    void finishesOnceTheHeadLeavesTheWater() {
        BreathTask task = new BreathTask(scripted(underwater(40.0), inAir(41.5)));
        task.start(tick);
        task.tick(tick);
        player.nextTick();

        TickResult result = task.tick(tick);

        assertTrue(result instanceof TickResult.Finished);
        assertEquals(TaskResult.Status.DONE, ((TickResult.Finished) result).result().status());
        // 出水的这一刻不再续发游泳输入，下一刻按键自然松开。
        assertEquals(1, player.movements);
    }

    @Test
    void stuckWhenItNeverRises() {
        // 高度一直不涨：进度跟踪等不到真实进展，按卡住收场并写明最后一次进展。
        BreathTask task = new BreathTask(scripted(underwater(40.0)));
        task.start(tick);
        TaskResult result = null;
        for (int i = 0; i < 250; i++) {
            TickResult tickResult = task.tick(tick);
            if (tickResult instanceof TickResult.Finished finished) {
                result = finished.result();
                break;
            }
        }
        assertTrue(result != null, "一直上不去时应当收场");
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.STUCK, result.problem().kind());
    }
}
