// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void afterRetargetArrivalIsJudgedByTheNewTarget() {
        // 跟人走时换了目标点：走到旧目标点不算到，走到新目标点才算。
        var progress = progressExact();
        progress.observe(seen(ELSEWHERE, true, false, true, false));
        progress.retarget(GoalCompiler.standOn(ELSEWHERE.east(3)).goal());
        var atOld = progress.observe(seen(TARGET, true, false, true, false));
        assertEquals(WalkReport.State.ON_THE_WAY, atOld.report().state());
        var atNew = progress.observe(seen(ELSEWHERE.east(3), true, false, true, false));
        assertEquals(WalkReport.State.ARRIVED, atNew.report().state());
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

    @Test
    void programErrorInTheSearchIsNotReportedAsNoRoute() {
        // 实机里算路线程抛异常时结果写成"没有能走到目标的路线"，模型照着换目标、带垫料白试了十几次。
        var progress = progressExact();
        progress.observe(seen(ELSEWHERE, true, false, false, false));
        var conclusion = progress.observe(new WalkRunProgress.Observation(ELSEWHERE, true, false, false, false, true,
                "java.lang.IllegalStateException: the player control boundary may only run on the Minecraft client thread"));
        assertEquals(WalkReport.State.FAILED, conclusion.report().state());
        Problem problem = conclusion.report().problem();
        assertEquals(Problem.Kind.INTERNAL_ERROR, problem.kind());
        assertTrue(problem.message().startsWith("寻路程序出错，这次没算出路线，不是没有路"));
        assertTrue(problem.message().contains("IllegalStateException"), "异常本身要带上，方便对着日志查");
        assertFalse(problem.message().contains("没有能走到目标的路线"));
        assertTrue(conclusion.done());
    }

    @Test
    void aFailureIsExplainedWithSiteFactsExactlyOnce() {
        var progress = progressExact();
        // 还在路上时没有失败可解释，补现场不改结果。
        progress.observe(seen(ELSEWHERE, true, false, true, false));
        progress.explainFailure(problem -> Problem.of(problem.kind(), "不该出现", null));
        assertEquals(WalkReport.State.ON_THE_WAY, progress.report().state());
        // 走不通后补一次现场：种类、位置不变，消息与建议换成补过的；第二次不再叠加。
        progress.observe(seen(ELSEWHERE, true, false, false, true));
        progress.explainFailure(problem -> Problem.of(problem.kind(), problem.message() + "；现场", "换条路"));
        progress.explainFailure(problem -> Problem.of(problem.kind(), problem.message() + "；又一遍", null));
        assertEquals(Problem.Kind.UNREACHABLE, progress.report().problem().kind());
        assertEquals("没有能走到目标的路线；现场", progress.report().problem().message());
        assertEquals("换条路", progress.report().problem().suggestion());
        assertEquals(ELSEWHERE, progress.report().feet());
    }

    @Test
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
    void aRunWithoutAGoalIsRejectedWhenBuilt() {
        // 没有目标的走到是调用方的程序错误：建的时候就拒绝，不等到路上才发现。
        assertThrows(NullPointerException.class, () -> new WalkRunProgress(null));
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

    @Test
    void yieldingTheBodySettlesAsStoppedButKeepsAnEarlierConclusion() {
        // 被暂停的走到交出身体：按"已停下"结算，恢复时任务会从原地重新上路；已经有结论的不改。
        var stopped = progressExact();
        stopped.observe(seen(ELSEWHERE, true, false, true, false));
        stopped.stopWhereLastSeen(ELSEWHERE.north());
        assertEquals(WalkReport.State.STOPPED, stopped.report().state());
        assertEquals(ELSEWHERE, stopped.report().feet(), "停在最近一次看到的位置");

        var failed = progressExact();
        failed.fail(Problem.of(Problem.Kind.STUCK, "走不动"), null);
        failed.stopWhereLastSeen(ELSEWHERE);
        assertEquals(WalkReport.State.FAILED, failed.report().state());
    }

    @Test
    void stillPlanningWhenTheBodyIsYielded_settlesWithoutThrowing() {
        // 实机：还在算路、一步没迈的运行被让出身体时，结算抛了异常，运行永远占着身体，之后所有走到都卡死。
        var planning = progressExact();
        planning.stopWhereLastSeen(ELSEWHERE);
        assertEquals(WalkReport.State.STOPPED, planning.report().state());
        assertEquals(ELSEWHERE, planning.report().feet(), "用交出时脚下的格子");

        var unknown = progressExact();
        unknown.stopWhereLastSeen(null);
        assertEquals(WalkReport.State.FAILED, unknown.report().state(), "连脚下都读不到也要有结论");
    }
}
