// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.FakeHotbar;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.FakeScene;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.TestPlayer;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.TestTick;
import static org.maiwithu.maicraft.behavior.survival.SurvivalFakes.scripted;

/**
 * 落地放水：快捷栏没有水桶如实说做不了；落点是水不用做事；下面是虚空救不了；
 * 水桶在手上就低头盯着落点，落点够不着之前不出手；没来得及放就落地如实交代。
 */
class FallGuardTaskTest {

    private static final BlockPos LANDING_WATER_CELL = new BlockPos(0, 41, 0);

    private final TestPlayer player = new TestPlayer();
    private final TestTick tick = new TestTick(player);
    private final FakeScene scene = new FakeScene();
    private final FakeHotbar hotbar = new FakeHotbar();

    private FallGuardTask task(SurvivalSituation... script) {
        FallGuardTask task = new FallGuardTask(scripted(script), null, context -> scene, context -> hotbar);
        task.start(tick);
        return task;
    }

    private static SurvivalSituation deadlyFall() {
        return SurvivalFakes.calm().feet(80).fallingOnto(LANDING_WATER_CELL, false).build();
    }

    @Test
    void failsHonestlyWithoutABucketInTheHotbar() {
        TaskResult result = ((TickResult.Finished) task(deadlyFall()).tick(tick)).result();

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.DANGER, result.problem().kind());
        assertTrue(result.problem().message().contains("水桶"), "问题要写明缺的是水桶");
    }

    @Test
    void landingInWaterNeedsNothing() {
        TaskResult result = ((TickResult.Finished) task(SurvivalFakes.calm().fallingIntoWater().build())
                .tick(tick)).result();
        assertEquals(TaskResult.Status.DONE, result.status());
    }

    @Test
    void voidCannotBeCushioned() {
        TaskResult result = ((TickResult.Finished) task(SurvivalFakes.calm().fallingIntoVoid().build())
                .tick(tick)).result();
        assertEquals(Problem.Kind.DANGER, result.problem().kind());
    }

    @Test
    void withTheBucketInHandItLooksDownAndWaitsUntilTheLandingIsInReach() {
        hotbar.waterBucketSlot = 3;
        hotbar.selected = 3;
        FallGuardTask task = task(deadlyFall());

        task.tick(tick);
        player.nextTick();
        assertTrue(task.tick(tick) instanceof TickResult.Running, "落点还够不着，继续往下掉");

        assertEquals(90.0f, player.looks.get(player.looks.size() - 1)[1], 0.001f, "低头盯着落点");
        assertTrue(task.describe().contains("低头"), "调试面板要能看懂此刻在做什么");
    }

    @Test
    void landingBeforePouringIsReportedHonestly() {
        hotbar.waterBucketSlot = 0;
        FallGuardTask task = task(deadlyFall(), deadlyFall(), SurvivalFakes.calm().feet(41).build());
        task.tick(tick);
        player.nextTick();
        task.tick(tick);
        player.nextTick();

        TaskResult result = ((TickResult.Finished) task.tick(tick)).result();

        assertEquals(Problem.Kind.DANGER, result.problem().kind());
        assertTrue(result.problem().message().contains("落地"));
    }
}
