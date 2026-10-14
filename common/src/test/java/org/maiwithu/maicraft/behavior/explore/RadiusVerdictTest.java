// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 半径判定：判定带是探索半径的外缘，从里向外走进带就算到达；越过硬边界截停失败。 */
class RadiusVerdictTest {

    private static final RadiusVerdict RADIUS = new RadiusVerdict(256);

    @Test
    void 判定带在半径减容差处() {
        // 容差 = min(8, 半径/8)，半径下限 64 起容差就是 8：64 的判定带内缘在 56。
        assertEquals(56, new RadiusVerdict(64).completionBand());
        assertEquals(120, new RadiusVerdict(128).completionBand());
        assertEquals(248, RADIUS.completionBand());
    }

    @Test
    void 从里向外走进判定带就算到达半径() {
        assertEquals(RadiusVerdict.Verdict.WITHIN, RADIUS.judge(0), "出发点还在圆心，不算到达");
        assertEquals(RadiusVerdict.Verdict.WITHIN, RADIUS.judge(247.9));
        assertEquals(RadiusVerdict.Verdict.REACHED, RADIUS.judge(248));
        assertEquals(RadiusVerdict.Verdict.REACHED, RADIUS.judge(260));
    }

    @Test
    void 走到判定带附近没有新落点就算跑到头() {
        // 半径 100：判定带 92，差不到 16 格（76 起）算附近；走到 80 多格西边没新落点了就收工，不转去别的方向。
        RadiusVerdict verdict = new RadiusVerdict(100);
        assertTrue(verdict.nearlyReached(80));
        assertFalse(verdict.nearlyReached(60));
        // 半径小时附近的宽度按四分之一算：半径 32，判定带 28，20 起算附近。
        assertTrue(new RadiusVerdict(32).nearlyReached(21));
        assertFalse(new RadiusVerdict(32).nearlyReached(15));
    }

    @Test
    void 越过硬边界截停() {
        // 硬边界在半径 + 8：一次跳出去超过它，要在边界截停失败，如实写明越界。
        assertEquals(RadiusVerdict.Verdict.LEFT_BOUND, RADIUS.judge(264.1));
        assertEquals(RadiusVerdict.Verdict.REACHED, RADIUS.judge(263), "缓冲带内进过判定带的照常收尾");
    }

    @Test
    void 航点不越过判定带() {
        // 实机 165 场景：首个航点不越过判定带，身体就不会一跳出去被截停。
        assertEquals(RADIUS.completionBand(), RADIUS.waypointBound());
    }
}
