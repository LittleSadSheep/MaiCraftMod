// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.explore;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 探索腿连续寻路失败的熔断器：同一方向上连续选出的落点反复走不到时，先按 45° 轮换候选扇区，
 * 八个方位全部试过仍失败就宣布方向受阻，避免在同一条不可行地形带上无限重付寻路成本。
 * 只统计连续失败——任何一条腿真实到达都说明当前方向可行，计数清零；
 * 全向搜索（无方向请求）没有可轮换的方位，或扇区已覆盖整圈时，达阈值直接受阻。
 */
public final class FrontierLegBreaker {
    /** 连续失败达到该值触发一次扇区轮换；零星失败可能是瞬时原因，仍按原方向换点重试。 */
    public static final int FAILURES_BEFORE_ROTATION = 5;
    /** 45° 一步的轮换上限：七次后八个方位都已试过，再轮换只会回到出发方位。 */
    public static final int MAX_ROTATIONS = 7;

    /** 腿失败后的处置：继续按原方向换点重试、轮换扇区、或宣布方向受阻。 */
    public enum Decision { KEEP_GOING, ROTATED, EXHAUSTED }

    private int consecutiveFailures;
    private int rotations;
    private final List<Double> rotatedBearings = new ArrayList<>();

    /** 腿真实到达后清零连续失败计数。 */
    public void onLegSuccess() {
        consecutiveFailures = 0;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public int rotations() {
        return rotations;
    }

    /** 历次轮换后的扇区方位（度，0..360，顺时针）；空列表表示从未轮换。 */
    public List<Double> rotatedBearings() {
        return List.copyOf(rotatedBearings);
    }

    /**
     * 腿失败后的处置。返回 ROTATED 时调用方应把候选扇区换成 {@link #rotated} 的结果；
     * 返回 EXHAUSTED 表示已无可轮换的方位，任务应报告方向受阻并终止，由调用方决定
     * 换出发点、扩大挖掘授权还是放弃。受阻不是结构或群系不存在的证据。
     */
    public Decision onLegFailure(ExplorationSector.Area current) {
        consecutiveFailures++;
        if (consecutiveFailures < FAILURES_BEFORE_ROTATION) return Decision.KEEP_GOING;
        if (current == null || current.request().direction() == null
                || current.request().angleDegrees() >= 360
                || rotations >= MAX_ROTATIONS) {
            return Decision.EXHAUSTED;
        }
        rotations++;
        consecutiveFailures = 0;
        rotatedBearings.add(normalizedBearing(rotated(current)));
        return Decision.ROTATED;
    }

    /** 从当前扇区方位顺时针轮换 45° 得到的下一个候选扇区；宽度与最小距离沿用原请求。 */
    public ExplorationSector.Area rotated(ExplorationSector.Area current) {
        return new ExplorationSector.Area(
                current.originX(), current.originZ(),
                current.bearing() + 360.0 / 8, current.request());
    }

    /**
     * 方向受阻的失败说明：连续失败量、卡住的方位与已轮换历史一次说清，供调用方决策。
     * legNoun 用调用方对腿的称呼（frontier leg / waypoint）。
     */
    public static String blockedMessage(
            String legNoun, int totalFailures, FrontierLegBreaker breaker,
            ExplorationSector.Area current) {
        String stuckAt = current.request().direction() == null
                ? "with no rotatable bearing in the all-direction search"
                : "around bearing "
                        + Math.round(normalizedBearing(current)) + " degrees";
        String rotation = breaker.rotatedBearings().isEmpty()
                ? " No other bearing was available to rotate to."
                : " Bearings "
                        + breaker.rotatedBearings().stream()
                                .map(value -> Math.round(value) + " degrees")
                                .collect(Collectors.joining(", "))
                        + " were rotated through and failed the same way.";
        return "Route finding kept failing on the way to new " + legNoun + "s ("
                + totalFailures + " failed in this search): the last "
                + breaker.consecutiveFailures() + " " + legNoun + "s all failed to reach"
                + " their points " + stuckAt + "." + rotation
                + " The search stopped instead of paying the routing cost again; resume"
                + " from another origin or sector, or grant may_alter_terrain if digging"
                + " and bridging are acceptable.";
    }

    private static double normalizedBearing(ExplorationSector.Area area) {
        return (area.bearing() % 360 + 360) % 360;
    }
}
