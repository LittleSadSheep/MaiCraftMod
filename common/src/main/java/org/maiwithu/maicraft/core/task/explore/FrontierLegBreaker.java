// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.explore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 探紧行程失败升级链：同一出发点附近反复走不到时，先小半径换锚（换发射位置），再按 45° 轮换
 * 候选扇区，八个方位都试过仍失败就宣布受阻——避免在不可行地形带无限重付寻路成本。
 * 触发不看失败是否严格连续，看最近观察窗内的失败占比：零星到达不代表方向可行，
 * 出发点几何本身不可行时（偶有近距离的行程能到、远距离的全败）也要升级。
 * 全向搜索（无方向请求）没有可轮换的方位，但换锚不依赖方向；换锚与轮换都耗尽后受阻。
 */
public final class FrontierLegBreaker {
    /** 升级观察窗：最近 N 次行程尝试（成败都算）。 */
    public static final int ESCALATION_WINDOW = 5;
    /** 窗内失败达到该值即升级：五次尝试四次失败说明失败已不是瞬时原因，出发点或方向大概率不可行。 */
    public static final int ESCALATION_FAILURES = 4;
    /** 45° 一步的轮换上限：七次后八个方位都已试过，再轮换只会回到出发方位。 */
    public static final int MAX_ROTATIONS = 7;
    /** 换锚上限：两次近距换出发点后仍失败，问题多半不在发射位置而在方向或地形带。 */
    public static final int MAX_RELOCATIONS = 2;

    /** 一段行程失败后的处置：继续按原方向换点重试、换锚、轮换扇区、或宣布受阻。 */
    public enum Decision { KEEP_GOING, RELOCATED, ROTATED, EXHAUSTED }

    private int consecutiveFailures;
    private int rotations;
    private int relocations;
    private final Deque<Boolean> recentAttempts = new ArrayDeque<>();
    private final List<Double> rotatedBearings = new ArrayList<>();
    private final List<Double> relocationBearings = new ArrayList<>();

    /** 一段行程真实到达：连续失败清零（观察窗保留历史，占比语义不受单次到达打断）。 */
    public void onLegSuccess() {
        consecutiveFailures = 0;
        recordAttempt(true);
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public int rotations() {
        return rotations;
    }

    public int relocations() {
        return relocations;
    }

    /** 当前观察窗内的失败次数；未满窗时即已发生的失败数。 */
    public int recentWindowFailures() {
        return (int) recentAttempts.stream().filter(reached -> !reached).count();
    }

    /** 历次轮换后的扇区方位（度，0..360，顺时针）；空列表表示从未轮换。 */
    public List<Double> rotatedBearings() {
        return List.copyOf(rotatedBearings);
    }

    /** 历次换锚的出发方位（度，0..360）；空列表表示从未换锚。 */
    public List<Double> anchorRelocationBearings() {
        return List.copyOf(relocationBearings);
    }

    /**
     * 一段行程失败后的处置。返回 RELOCATED 时调用方应把发射位置小半径挪到 {@link #relocationBearing}
     * 指向的附近落点；返回 ROTATED 时把候选扇区换成 {@link #rotated} 的结果；返回 EXHAUSTED
     * 表示换锚与轮换都已耗尽，任务应报告受阻并终止，由调用方决定远距换区、扩大挖掘授权
     * 还是放弃。受阻不是结构或群系不存在的证据。
     */
    public Decision onLegFailure(ExplorationSector.Area current) {
        consecutiveFailures++;
        recordAttempt(false);
        if (recentAttempts.size() < ESCALATION_WINDOW
                || recentWindowFailures() < ESCALATION_FAILURES) {
            return Decision.KEEP_GOING;
        }
        return escalate(current);
    }

    /**
     * 换锚行程自身失败（或选不出换锚落点）时的处置：发射位置没有真正挪动，不再等观察窗补满，
     * 直接走下一级升级，否则会在坏锚点周围又烧掉一整窗的尝试。
     */
    public Decision onRelocationLegFailed(ExplorationSector.Area current) {
        return escalate(current);
    }

    private Decision escalate(ExplorationSector.Area current) {
        if (relocations < MAX_RELOCATIONS) {
            relocations++;
            relocationBearings.add(normalized(relocationBearing(current)));
            consecutiveFailures = 0;
            recentAttempts.clear();
            return Decision.RELOCATED;
        }
        if (current == null || current.request().direction() == null
                || current.request().angleDegrees() >= 360
                || rotations >= MAX_ROTATIONS) {
            return Decision.EXHAUSTED;
        }
        rotations++;
        consecutiveFailures = 0;
        recentAttempts.clear();
        rotatedBearings.add(normalized(rotated(current)));
        return Decision.ROTATED;
    }

    /** 从当前扇区方位顺时针轮换 45° 得到的下一个候选扇区；宽度与最小距离沿用原请求。 */
    public ExplorationSector.Area rotated(ExplorationSector.Area current) {
        return new ExplorationSector.Area(
                current.originX(), current.originZ(),
                current.bearing() + 360.0 / 8, current.request());
    }

    /**
     * 换锚建议方位：垂直于当前扇区方位，第一次向右 90°、第二次向左 90°，归一化到 0..360。
     * 沿崖壁、水线这类条带地形横移比迎着同一面障碍换角度更容易找到可行出口。
     */
    public double relocationBearing(ExplorationSector.Area current) {
        double base = current == null ? 0.0 : current.bearing();
        double bearing = base + (relocations % 2 == 1 ? 90.0 : -90.0);
        return (bearing % 360 + 360) % 360;
    }

    /**
     * 受阻的失败说明：连续失败量、卡住的方位、换锚与轮换历史一次说清。换锚也失败时明示
     * 出发地形本身不可行，建议远距换区后重提，供调用方决策。legNoun 用调用方对这段行程的称呼。
     */
    public static String blockedMessage(
            String legNoun, int totalFailures, FrontierLegBreaker breaker,
            ExplorationSector.Area current) {
        String stuckAt = current.request().direction() == null
                ? "with no rotatable bearing in the all-direction search"
                : "around bearing "
                        + Math.round(normalized(current)) + " degrees";
        StringBuilder message = new StringBuilder()
                .append("Route finding kept failing on the way to new ").append(legNoun)
                .append("s (").append(totalFailures).append(" failed in this search) ")
                .append(stuckAt).append('.');
        if (breaker.consecutiveFailures() > 0) {
            message.append(" The last ").append(breaker.consecutiveFailures())
                    .append(' ').append(legNoun)
                    .append("s all failed to reach their points.");
        }
        if (!breaker.anchorRelocationBearings().isEmpty()) {
            message.append(" Anchor relocations toward bearings ")
                    .append(breaker.anchorRelocationBearings().stream()
                            .map(value -> Math.round(value) + " degrees")
                            .collect(Collectors.joining(", ")))
                    .append(" also failed, which points at the launch terrain itself rather")
                    .append(" than one direction: travel to open terrain a few hundred blocks")
                    .append(" away and start a fresh search from there instead of retrying here.");
        }
        if (breaker.rotatedBearings().isEmpty()) {
            message.append(" No other bearing was available to rotate to.");
        } else {
            message.append(" Bearings ")
                    .append(breaker.rotatedBearings().stream()
                            .map(value -> Math.round(value) + " degrees")
                            .collect(Collectors.joining(", ")))
                    .append(" were rotated through and failed the same way.");
        }
        message.append(" The search stopped instead of paying the routing cost again; resume")
                .append(" from another origin or sector, or grant may_alter_terrain if digging")
                .append(" and bridging are acceptable.");
        return message.toString();
    }

    private void recordAttempt(boolean reached) {
        recentAttempts.addLast(reached);
        while (recentAttempts.size() > ESCALATION_WINDOW) {
            recentAttempts.removeFirst();
        }
    }

    private static double normalized(ExplorationSector.Area area) {
        return (area.bearing() % 360 + 360) % 360;
    }

    private static double normalized(double bearing) {
        return (bearing % 360 + 360) % 360;
    }
}
