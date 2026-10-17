// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

/**
 * 平地复核：脚下与四邻至少 3 格同高支撑，才算一块能站的平地（target=platform 要找的地方）。
 *
 * <p>站上去之后按脚下事实复查，不只凭远处的采样宣布到达——采样只看一列，
 * 真能站的地方要看四邻是不是也在同一高度踩得住。
 */
public final class LevelGroundCheck {

    /** 四邻至少几格同高支撑才算平台。 */
    public static final int MIN_NEIGHBORS = 3;

    private LevelGroundCheck() {}

    /**
     * 脚下这一格算不算站上了平台：脚下踩得住，且东西南北四邻至少三格也在同一高度踩得住。
     * 各方向的支撑事实由现场读端按"那一格的下面同高且稳固"给出。
     */
    public static boolean holds(boolean feetSupported, boolean northSupported, boolean eastSupported,
            boolean southSupported, boolean westSupported) {
        if (!feetSupported) {
            return false;
        }
        int neighbors = (northSupported ? 1 : 0) + (eastSupported ? 1 : 0)
                + (southSupported ? 1 : 0) + (westSupported ? 1 : 0);
        return neighbors >= MIN_NEIGHBORS;
    }
}
