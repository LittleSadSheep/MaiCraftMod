// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 走到运行逐刻判断的状态机：到达以身体为准（站实落地，宽松目标允许水里漂入），
 * 中途停下等落地才真正交出身体，路线搜索失败如实失败。
 */
class WalkRunProgressTest {

    private static final BlockPos TARGET = new BlockPos(10, 64, 12);
    private static final BlockPos ELSEWHERE = new BlockPos(40, 64, 60);

    private static WalkRunProgress progressExact() {
        return new WalkRunProgress(GoalCompiler.standOn(TARGET).goal());
    }

    private static WalkRunProgress.Observation seen(BlockPos feet, boolean onGround, boolean inWater,
                                                    boolean routePresent, boolean calcFailed) {
        return new WalkRunProgress.Observation(feet, onGround, inWater, routePresent, !routePresent, calcFailed);
    }

    @Test
    void withoutRouteTheRunReportsPlanning() {
        var progress = progressExact();
        var conclusion = progress.observe(seen(ELSEWHERE, true, false, false, false));
        assertEquals(WalkReport.State.PLANNING, conclusion.report().state());
        assertFalse(conclusion.done());
        assertFalse(progress.stopRequested());
    }

    @Test
    void standingOnTheTargetCellOnGroundArrives() {
        var progress = progressExact();
        progress.observe(seen(ELSEWHERE, true, false, true, false));
        var conclusion = progress.observe(seen(TARGET, true, false, true, false));
        assertEquals(WalkReport.State.ARRIVED, conclusion.report().state());
        assertEquals(WalkRunProgress.Action.FINISH, conclusion.action());
        assertTrue(conclusion.done());
        assertEquals(TARGET, conclusion.report().feet());
        assertFalse(conclusion.report().crossedWater());
    }

    @Test
    void passingThroughTheTargetCellInALeapDoesNotArrive() {
        var progress = progressExact();
        var conclusion = progress.observe(seen(TARGET, false, false, true, false));
        assertEquals(WalkReport.State.ON_THE_WAY, conclusion.report().state());
        assertFalse(conclusion.done());
    }

    @Test
    void lenientGoalAcceptsFloatingIntoRangeButStrictGoalDoesNot() {
        NavGoal lenient = NavGoal.near(TARGET, 3.0);
        var progress = new WalkRunProgress(lenient);
        var conclusion = progress.observe(seen(TARGET.above(), false, true, true, false));
        assertEquals(WalkReport.State.ARRIVED, conclusion.report().state());
        assertTrue(conclusion.report().crossedWater());

        var strict = progressExact();
        var refused = strict.observe(seen(TARGET, false, true, true, false));
        assertEquals(WalkReport.State.ON_THE_WAY, refused.report().state());
    }

    @Test
    void crossingWaterIsReportedOnceItHappenedOnTheWay() {
        var progress = progressExact();
        // 泅渡是这段路的累计事实：一旦下过水，之后的走到情况都带着它，上岸也不洗掉。
        progress.observe(seen(ELSEWHERE, true, true, true, false));
        assertTrue(progress.report().crossedWater());
        progress.observe(seen(ELSEWHERE.east(), true, false, true, false));
        assertTrue(progress.report().crossedWater());
        // 没下过水就到不了：没有泅渡也不冒充有。
        var dry = progressExact();
        dry.observe(seen(ELSEWHERE, true, false, true, false));
        assertFalse(dry.report().crossedWater());
    }

    @Test
    void stopWaitsForSolidGroundBeforeCancelingAndHandingOver() {
        var progress = progressExact();
        progress.observe(seen(ELSEWHERE, true, false, true, false));
        progress.requestStop();
        // 身体还在空中：路线保留，继续走。
        var airborne = progress.observe(seen(ELSEWHERE.east(), false, false, true, false));
        assertEquals(WalkReport.State.ON_THE_WAY, airborne.report().state());
        assertFalse(airborne.done());
        // 落地：取消路线并交出身体。
        var landed = progress.observe(seen(ELSEWHERE.east(), true, false, true, false));
        assertEquals(WalkReport.State.STOPPED, landed.report().state());
        assertEquals(WalkRunProgress.Action.CANCEL_ROUTE, landed.action());
        assertTrue(landed.done());
    }

    @Test
    void stopRequestedBeforeFirstRouteStillStopsOnTheSpot() {
        var progress = progressExact();
        progress.requestStop();
        var conclusion = progress.observe(seen(ELSEWHERE, true, false, false, false));
        assertEquals(WalkReport.State.STOPPED, conclusion.report().state());
        assertEquals(WalkRunProgress.Action.NONE, conclusion.action());
    }

    @Test
    void failedSearchWithoutRouteFailsTheRun() {
        var progress = progressExact();
        progress.observe(seen(ELSEWHERE, true, false, false, false));
        var conclusion = progress.observe(seen(ELSEWHERE, true, false, false, true));
        assertEquals(WalkReport.State.FAILED, conclusion.report().state());
        assertEquals(Problem.Kind.UNREACHABLE, conclusion.report().problem().kind());
        assertTrue(conclusion.done());
    }

    @org.junit.jupiter.api.Test
    void externalFailureIsRecordedOnlyOnce() {
        var progress = progressExact();
        progress.observe(seen(ELSEWHERE, true, false, true, false));
        progress.fail(Problem.of(Problem.Kind.STUCK, "长时间没有进展", null), ELSEWHERE);
        var after = progress.observe(seen(TARGET, true, false, true, false));
        assertEquals(WalkReport.State.FAILED, after.report().state());
        assertEquals(Problem.Kind.STUCK, after.report().problem().kind());
        assertEquals(ELSEWHERE, after.report().feet());
        // 结束后的观察不再改变结果。
        progress.requestStop();
        progress.observe(seen(TARGET, true, false, true, false));
        assertEquals(Problem.Kind.STUCK, progress.report().problem().kind());
    }

    @Test
    void drivingWithoutAGoalFailsHonestlyInsteadOfCrashing() {
        // 走到实现没拿到目标就开始驱动：如实失败并结束，不在到达判断上崩出空指针。
        var progress = new WalkRunProgress(null);
        var conclusion = progress.observe(seen(ELSEWHERE, true, false, false, false));
        assertEquals(WalkReport.State.FAILED, conclusion.report().state());
        assertEquals(Problem.Kind.INTERNAL_ERROR, conclusion.report().problem().kind());
        assertEquals(WalkRunProgress.Action.CANCEL_ROUTE, conclusion.action());
        assertTrue(conclusion.done());
        // 之后的观察不再改变结果，也不会碰空目标。
        var after = progress.observe(seen(TARGET, true, false, true, false));
        assertEquals(WalkReport.State.FAILED, after.report().state());
    }

    @Test
    void drivingBeforeTheRouteIsReadyReportsPlanningThenWalks() {
        // 算路还没就绪的第一刻：如实报告正在算路，不冒充已在路上；路线出来后正常走到。
        var progress = progressExact();
        var notReady = progress.observe(seen(ELSEWHERE, true, false, false, false));
        assertEquals(WalkReport.State.PLANNING, notReady.report().state());
        assertFalse(notReady.done());
        var ready = progress.observe(seen(ELSEWHERE.east(), true, false, true, false));
        assertEquals(WalkReport.State.ON_THE_WAY, ready.report().state());
        assertFalse(ready.done());
    }
}
