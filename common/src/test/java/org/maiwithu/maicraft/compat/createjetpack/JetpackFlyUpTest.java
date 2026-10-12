// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.createjetpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 背罐里的气换成一次能飞多高：气少不飞（留给落地防护刹车），气多也有上限，往上推按悬停的一半算。 */
class JetpackFlyUpTest {

    @Test
    void 气少于十秒不飞() {
        assertTrue(JetpackFlyUp.reach(9.9, 0.4).isEmpty());
    }

    @Test
    void 气够时按往上飞的速度算格数和每格的时间() {
        var reach = JetpackFlyUp.reach(20, 0.4).orElseThrow();
        // (20 - 5) 秒 × 一半 × 20 刻 × 0.4 格 = 60 格，封顶 24。
        assertEquals(JetpackFlyUp.MAX_BLOCKS, reach.maxBlocks());
        assertEquals(2.5, reach.ticksPerBlock(), 1e-9);
        assertEquals(5, JetpackFlyUp.reach(10.5, 0.1).orElseThrow().maxBlocks());
    }
}
