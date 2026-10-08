// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.navigation.WalkArrival;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 一次走到运行的逐刻判断：只读观察，给出一刻的走到情况与要做的动作。
 * 身体在空中时不响应停下请求——等到落回地面或站上支撑才真正交出身体，
 * 免得把一次可以避免的坠落换成中途撒手。到达判断交给 {@link WalkArrival}，
 * 这里不按目标类型分别写一遍。
 */
final class WalkRunProgress {

    /** 需要执行方做的动作；NONE 之外的动作只在状态切换的一刻给出。 */
    enum Action { NONE, CANCEL_ROUTE, FINISH }

    /**
     * 一刻的观察：脚下格与身体状态来自角色，路线状态来自寻路引擎。
     * {@code routePresent} 与 {@code calculating} 分别表示已有可走的路线和仍在搜索。
     */
    record Observation(BlockPos feet, boolean onGround, boolean inWater,
                       boolean routePresent, boolean calculating, boolean calcFailed) {}

    record Conclusion(WalkReport report, Action action, boolean done, boolean progressed) {}

    private final NavGoal goal;
    private WalkReport report = WalkReport.planning();
    private boolean crossedWater;
    private boolean stopRequested;

    WalkRunProgress(NavGoal goal) {
        this.goal = goal;
    }

    WalkReport report() {
        return report;
    }

    /** 请求中途停下；到达或失败后请求不再改变结果。 */
    void requestStop() {
        stopRequested = true;
    }

    boolean stopRequested() {
        return stopRequested;
    }

    /** 由执行方在路线确实无法继续时写入失败（长时间没有进展、引擎内部出错等）。 */
    void fail(Problem problem, BlockPos feet) {
        if (done()) return;
        report = WalkReport.failed(problem, feet, crossedWater);
    }

    boolean done() {
        var state = report.state();
        return state == WalkReport.State.ARRIVED || state == WalkReport.State.STOPPED
                || state == WalkReport.State.FAILED;
    }

    Conclusion observe(Observation o) {
        if (done()) {
            return new Conclusion(report, Action.NONE, true, false);
        }
        // 泅渡发生在路上就如实记账；到达后不再把水里的漂动算进这段路。
        crossedWater |= o.inWater && o.routePresent;

        if (o.calcFailed && !o.routePresent) {
            report = WalkReport.failed(
                    Problem.of(Problem.Kind.UNREACHABLE, "没有能走到目标的路线", null), o.feet, crossedWater);
            return new Conclusion(report, Action.CANCEL_ROUTE, true, false);
        }

        // 到达以身体为准：目标格判断通过且站实落地；宽松目标允许水里漂进范围。
        if (WalkArrival.reached(goal, o.feet, o.onGround, o.inWater)) {
            report = WalkReport.arrived(o.feet, crossedWater);
            return new Conclusion(report, Action.FINISH, true, true);
        }

        // 中途停下分两步：请求先记下，身体仍在空中时继续沿路线走到安全边界再交出。
        if (stopRequested && o.onGround) {
            report = WalkReport.stopped(o.feet, crossedWater);
            Action action = o.routePresent ? Action.CANCEL_ROUTE : Action.NONE;
            return new Conclusion(report, action, true, true);
        }

        // 已请求停下而身体还在空中：路线保留，执行段继续把身体带回支撑，落地一刻再取消并交出；
        // 只要还有路线在路上走，走到情况就仍是在路上，不冒充停稳。
        var state = o.routePresent ? WalkReport.State.ON_THE_WAY : WalkReport.State.PLANNING;
        if (state == WalkReport.State.ON_THE_WAY) {
            report = WalkReport.onTheWay(o.feet, crossedWater);
        } else {
            report = WalkReport.planning();
        }
        return new Conclusion(report, Action.NONE, false, false);
    }
}
