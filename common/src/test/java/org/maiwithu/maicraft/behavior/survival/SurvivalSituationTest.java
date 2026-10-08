// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 生存处境的换算：氧气刻数换泡数按原版每 30 刻一格泡，两条边界都要对上；
 * 处境本身只是观察事实的容器，三个判断接口读到什么就答什么。
 */
class SurvivalSituationTest {

    private static SurvivalSituation situation(
            double fallDistance, double health, boolean headInWater,
            int airBubbles, boolean drowning, boolean stuck, boolean overVoid) {
        return new SurvivalSituation(fallDistance, health, 64.0, 0.0f,
                headInWater, airBubbles, drowning, stuck, null, overVoid);
    }

    @Test
    void oxygenTicksConvertToBubblesByThirty() {
        assertEquals(10, SurvivalSituation.bubblesOf(300), "满氧气 300 刻是 10 泡");
        assertEquals(3, SurvivalSituation.bubblesOf(99), "99 刻只算 3 格整泡");
        assertEquals(0, SurvivalSituation.bubblesOf(29), "不足 30 刻连一格泡都不到");
        assertEquals(0, SurvivalSituation.bubblesOf(0), "氧气耗尽是 0 泡");
    }

    @Test
    void situationAnswersFallView() {
        SurvivalSituation situation = situation(7.5, 6.0, false, 10, false, false, true);
        assertEquals(7.5, situation.fallDistance());
        assertEquals(6.0, situation.health());
        assertEquals(true, situation.overVoid());
    }

    @Test
    void situationAnswersDrowningView() {
        SurvivalSituation situation = situation(0, 20.0, true, 2, true, false, false);
        assertEquals(true, situation.headInWater());
        assertEquals(2, situation.airBubbles());
        assertEquals(true, situation.drowning());
    }

    @Test
    void situationAnswersBuriedView() {
        SurvivalSituation situation = situation(0, 20.0, false, 10, false, true, false);
        assertEquals(true, situation.stuckInSolidBlock());
    }
}
