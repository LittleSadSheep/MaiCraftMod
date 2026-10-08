// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.task.Urgency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 坠落判断：摔得死的这一掉必须立刻自救；摔不死的不归坠落需求管。 */
class FallDangerTest {

    @Test
    void notFallingIsNotAFallHazard() {
        // 站在地上、贴着墙走：没有在往下掉。
        assertNull(FallDanger.assess(situation(0, 20, false)));
    }

    @Test
    void survivableFallIsNotUrgent() {
        // 掉了 10 格、20 颗血：落地扣 7 点，疼但不致命，落地时游戏自己结算。
        assertNull(FallDanger.assess(situation(10, 20, false)));
    }

    @Test
    void lethalFallMustBeHandledNow() {
        // 掉了 25 格、只剩 8 颗血：预计扣 22 点，摔下去就死。
        assertEquals(Urgency.NOW, FallDanger.assess(situation(25, 8, false)));
    }

    @Test
    void damageExactlyReachingHealthIsLethal() {
        // 预计伤害正好等于当前生命也是死：宁可错报，不赌落地位置。
        assertEquals(Urgency.NOW, FallDanger.assess(situation(23, 20, false)));
    }

    @Test
    void fallingIntoTheVoidIsLethalRegardlessOfHealth() {
        // 下面是虚空：掉一格也是死。
        assertEquals(Urgency.NOW, FallDanger.assess(situation(0.5, 20, true)));
    }

    private FallDanger.View situation(double fallDistance, double health, boolean overVoid) {
        return new FallDanger.View() {
            @Override public double fallDistance() {
                return fallDistance;
            }

            @Override public double health() {
                return health;
            }

            @Override public boolean overVoid() {
                return overVoid;
            }
        };
    }
}
