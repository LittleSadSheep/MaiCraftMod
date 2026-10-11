// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

/**
 * 半径判定：身体离探索原点多远时该收工、该继续还是该截停。
 *
 * <p>判定带是探索半径的外缘：身体向外走、进入 max_distance−容差（容差 = min(8, 半径/8)）
 * 及更远，就算到达半径，成功收尾并取消在途段；越过 max_distance+8 的硬边界截停失败，
 * 如实写明越界。判定带以内继续走，航点也不把它带出去。
 */
public record RadiusVerdict(double maxDistance) {

    /** 越过硬边界就算越界：半径外加 8 格缓冲，防一步跨出去就误报。 */
    private static final double HARD_BOUND_BUFFER = 8;

    public RadiusVerdict {
        if (maxDistance <= 0) {
            throw new IllegalArgumentException("探索半径必须为正：" + maxDistance);
        }
    }

    /** 判定带的内缘：身体走到这里（从里向外）就算到达半径；容差 = min(8, 半径/8)。 */
    public double completionBand() {
        return maxDistance - Math.min(8, maxDistance / 8);
    }

    /** 航点候选不越过判定带：说好跑多大范围就跑多大范围，不为多看两眼一头冲出去。 */
    public double waypointBound() {
        return completionBand();
    }

    /** 身体离探索原点 given 距离时的判定。 */
    public Verdict judge(double distanceFromOrigin) {
        if (distanceFromOrigin > maxDistance + HARD_BOUND_BUFFER) {
            return Verdict.LEFT_BOUND;
        }
        if (distanceFromOrigin >= completionBand()) {
            return Verdict.REACHED;
        }
        return Verdict.WITHIN;
    }

    /** 判定的结论。 */
    public enum Verdict {
        /** 到达半径：成功收尾并取消在途段。 */
        REACHED,
        /** 还在判定带以内，继续。 */
        WITHIN,
        /** 越过硬边界：截停失败，如实写明越界。 */
        LEFT_BOUND
    }
}
