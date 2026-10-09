// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.FakeBreaking;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.TestPlayer;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.TestTick;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.inAir;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.scripted;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.underwater;

/** 换气临时任务：水下抬头按跳上游；头出水后踩着水等氧气补满才结束；一直上不去按卡住收场。 */
class BreathTaskTest {

    private final TestPlayer player = new TestPlayer();
    private final TestTick tick = new TestTick(player);

    @Test
    void underwaterItHoldsJumpAndLooksUp() {
        BreathTask task = new BreathTask(scripted(underwater(40.0)));
        task.start(tick);

        TickResult result = task.tick(tick);

        assertTrue(result instanceof TickResult.Running);
        PlayerInput.Movement movement = player.applied.get(0);
        assertTrue(movement.jumping(), "水里要按住跳跃才上浮");
        assertEquals(0.0f, movement.forward(), "上浮不需要前进");
        assertEquals(-90.0f, player.looks.get(0)[1], 0.001f, "镜头要压到向上");
        assertEquals(37.0f, player.looks.get(0)[0], 0.001f, "水平朝向保持不变");
    }

    @Test
    void headAboveWaterKeepsTreadingUntilAirIsFull() {
        // 刚露头氧气还只有一半：先踩着水等，补满了才结束，不让手上的活马上又把人带回水下。
        BreathTask task = new BreathTask(scripted(underwater(40.0),
                SurvivalFakes.calm().feet(41.5).air(150).build(), inAir(41.5)));
        task.start(tick);
        task.tick(tick);
        player.nextTick();

        assertTrue(task.tick(tick) instanceof TickResult.Running, "氧气没补满就不结束");
        assertTrue(player.applied.get(1).jumping(), "在水面上按住跳跃踩水");
        player.nextTick();
        TickResult result = task.tick(tick);

        assertTrue(result instanceof TickResult.Finished);
        assertEquals(TaskResult.Status.DONE, ((TickResult.Finished) result).result().status());
    }

    @Test
    void stuckWhenItNeverRises() {
        // 高度和氧气都一直不涨：进度跟踪等不到真实进展，按卡住收场。
        BreathTask task = new BreathTask(scripted(underwater(40.0)));
        task.start(tick);
        TaskResult result = null;
        for (int i = 0; i < 250; i++) {
            player.nextTick();
            if (task.tick(tick) instanceof TickResult.Finished finished) {
                result = finished.result();
                break;
            }
        }
        assertTrue(result != null, "一直上不去时应当收场");
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.STUCK, result.problem().kind());
    }

    @Test
    void iceOverTheWaterIsDugOpenBeforeSwimmingUp() {
        // 水面结了冰：往上顶出不去，先挖开头顶那一格，挖开后接着往上游。
        BlockPos ice = new BlockPos(0, 44, 0);
        SurvivalSituation underIce = SurvivalFakes.calm().feet(40.0).underwater(30).underCeiling(ice).build();
        FakeBreaking digging = new FakeBreaking(ActionStatus.running(), ActionStatus.done());
        BreathTask task = new BreathTask(scripted(underIce, underIce, underIce, underwater(40.0)),
                (context, cell) -> {
                    digging.aimAt(cell);
                    return Optional.of(digging);
                });
        task.start(tick);
        for (int i = 0; i < 4; i++) {
            assertTrue(task.tick(tick) instanceof TickResult.Running);
            player.nextTick();
        }

        assertTrue(digging.dug.contains(ice), "挖的是压在水面上的那一格");
        assertTrue(digging.closed, "挖完收尾");
    }

    @Test
    void iceThatMayNotBeDugEndsTheTaskWithTheReason() {
        // 头顶那一格挖不动（基岩这类）：如实失败，交给上层。
        SurvivalSituation underIce = SurvivalFakes.calm().feet(40.0).underwater(30)
                .underCeiling(new BlockPos(0, 44, 0)).build();
        BreathTask task = new BreathTask(scripted(underIce), BreathTask.DigsCeiling.NONE);
        task.start(tick);

        TickResult result = task.tick(tick);

        assertTrue(result instanceof TickResult.Finished);
        TaskResult finished = ((TickResult.Finished) result).result();
        assertEquals(TaskResult.Status.FAILED, finished.status());
        assertTrue(finished.problem().message().contains("挖不动"), finished.problem().message());
    }
}
