// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 等咬钩一段的离线场景：同步咬钩立即收竿；钩消失、钩住实体、未入水五秒、六十秒无咬钩
 * 各自报废重抛；钩住实体与没入水的水面进排除集，久等不咬的不进。
 */
class BiteWatchTest {

    private static BiteWatch.BiteFacts facts(boolean hasHook, boolean biting, boolean hooked,
                                             boolean inWater, int waited) {
        return new BiteWatch.BiteFacts(hasHook, biting, hooked, inWater, waited);
    }

    @Test
    void 咬钩立刻收竿不等粒子() {
        assertEquals(BiteWatch.Step.REEL_IN,
                BiteWatch.judge(facts(true, true, false, true, 1)).step());
        // 刚抛出去 20 刻就咬钩也一样收。
        assertEquals(BiteWatch.Step.REEL_IN,
                BiteWatch.judge(facts(true, true, false, true, 20)).step());
    }

    @Test
    void 鱼钩消失直接报废不用收() {
        assertEquals(BiteWatch.Step.SCRAP_GONE,
                BiteWatch.judge(facts(false, false, false, false, 30)).step());
    }

    @Test
    void 钩住实体先收竿报废且水面进排除集() {
        var verdict = BiteWatch.judge(facts(true, false, true, false, 40));
        assertEquals(BiteWatch.Step.REEL_AND_SCRAP, verdict.step());
        assertTrue(verdict.rejectsLanding(), "钩住实体的水面进排除集");
    }

    @Test
    void 抛出五秒还没入水收竿报废且水面进排除集() {
        var verdict = BiteWatch.judge(facts(true, false, false, false, BiteWatch.SETTLE_TICKS));
        assertEquals(BiteWatch.Step.REEL_AND_SCRAP, verdict.step());
        assertTrue(verdict.rejectsLanding(), "没入水的水面进排除集");
    }

    @Test
    void 一竿六十秒没咬钩报废但水面不进排除集() {
        var verdict = BiteWatch.judge(facts(true, false, false, true, BiteWatch.LIFETIME_TICKS));
        assertEquals(BiteWatch.Step.REEL_AND_SCRAP, verdict.step());
        assertFalse(verdict.rejectsLanding(), "水没毛病，久等不咬不怪水面");
    }

    @Test
    void 没到耐心上限就接着等() {
        assertEquals(BiteWatch.Step.WAIT, BiteWatch.judge(facts(true, false, false, true, 600)).step());
        assertEquals(BiteWatch.Step.WAIT, BiteWatch.judge(facts(true, false, false, false, 10)).step());
    }
}
