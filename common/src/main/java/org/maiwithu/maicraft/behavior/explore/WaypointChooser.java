// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 航点选择：扇形内的一堆候选落点里选下一个走去哪的纯判断。
 *
 * <p>打分偏向往外走与沿射线走远：得分 = 离探索原点的距离 + 射线距离×0.2 − 偏心×0.1。
 * 穿水的候选整体让位（渡不渡得过去由走到的跨水保证结算），全穿水（身处岛屿）仍选得分最高的；
 * 落点列是纯水面的不选——会把身体领进水里。已试过或已证实走不通的邻域不再选。
 */
public final class WaypointChooser {

    /** 已试过的落点周围这个范围内的候选不再选（格）。 */
    public static final int TRIED_NEIGHBORHOOD = 16;

    private WaypointChooser() {}

    /**
     * 一个航点候选：落点列与打分要用的几个量。
     *
     * @param column            落点列（y 无意义，走行段只约束这一柱）
     * @param waterSurface      这一列的顶盖是纯水面（不能当落点）
     * @param crossesWater      从当前位置走过去要跨水（让位，不是否决）
     * @param distanceFromOrigin 离探索原点的水平距离（打分主项，偏向往外走）
     * @param rayDistance       离当前位置的水平距离（射线走多远）
     * @param eccentricity      相对扇形中线的偏心角（度，带符号）
     */
    public record Candidate(BlockPos column, boolean waterSurface, boolean crossesWater,
            double distanceFromOrigin, double rayDistance, double eccentricity) {
    }

    /**
     * 从候选里选下一个航点：滤掉纯水面与已试邻域，穿水的让位给干的，
     * 剩下的取得分最高的；一个都不剩给空。
     */
    public static Optional<Candidate> choose(List<Candidate> candidates, Set<Long> triedColumns) {
        Candidate bestDry = null;
        Candidate bestAny = null;
        double bestDryScore = Double.NEGATIVE_INFINITY;
        double bestAnyScore = Double.NEGATIVE_INFINITY;
        for (Candidate candidate : candidates) {
            if (candidate.waterSurface() || tried(candidate.column(), triedColumns)) {
                continue;
            }
            double score = score(candidate);
            if (score > bestAnyScore) {
                bestAny = candidate;
                bestAnyScore = score;
            }
            // 穿水是优先级不是否决：有干路先走干路，全穿水时再回头用穿水的。
            if (!candidate.crossesWater() && score > bestDryScore) {
                bestDry = candidate;
                bestDryScore = score;
            }
        }
        return Optional.ofNullable(bestDry != null ? bestDry : bestAny);
    }

    /** 得分 = 离原点距离 + 射线距离×0.2 − 偏心×0.1：往外走、沿中线走远。 */
    public static double score(Candidate candidate) {
        return candidate.distanceFromOrigin() + candidate.rayDistance() * 0.2
                - Math.abs(candidate.eccentricity()) * 0.1;
    }

    /** 已试过或已证实走不通的落点周围 16 格内的候选不再选。 */
    private static boolean tried(BlockPos column, Set<Long> triedColumns) {
        for (long tried : triedColumns) {
            BlockPos spot = BlockPos.of(tried);
            int dx = spot.getX() - column.getX();
            int dz = spot.getZ() - column.getZ();
            if (dx * dx + dz * dz < (double) TRIED_NEIGHBORHOOD * TRIED_NEIGHBORHOOD) {
                return true;
            }
        }
        return false;
    }
}
