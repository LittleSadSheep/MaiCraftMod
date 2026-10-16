// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 摔落伤害：摔完还剩多少血（黄心先扣）、剩不剩一半、摔完还剩若干血的最大落差。 */
class FallDamageTest {

    // 普通身体：安全摔落三格、伤害倍率 1、没有附魔药水，从站着起跳。
    private static FallDamage body(float health, float absorption) {
        return new FallDamage(health, absorption, 3, 1, 0, 0, 0, 0, 0.08, 0, false);
    }

    @Test
    void healthAfterTakesAbsorptionFirst() {
        assertEquals(18, body(20, 0).healthAfter(5, FallDamage.Landing.ORDINARY, false));
        // 四点黄心先吃掉四点伤害：跳七格扣四点，血一点不少。
        assertEquals(20, body(20, 4).healthAfter(7, FallDamage.Landing.ORDINARY, false));
        assertEquals(Float.NEGATIVE_INFINITY, body(20, 0).healthAfter(-1, FallDamage.Landing.ORDINARY, false));
    }

    @Test
    void keepsHalfIsHalfOfMaxHealth() {
        // 满血 20：跳十三格扣十点，剩十点正好一半；十四格就不够。
        assertTrue(body(20, 0).keepsHalf(20, 13, FallDamage.Landing.ORDINARY, false));
        assertFalse(body(20, 0).keepsHalf(20, 14, FallDamage.Landing.ORDINARY, false));
        // 血本来就不到一半：三格以内不扣血才算剩一半以上之外，扣一点都不够。
        assertFalse(body(9, 0).keepsHalf(20, 4, FallDamage.Landing.ORDINARY, false));
        // 干草块把伤害打两折：同样十四格，落在干草上剩得下一半。
        assertTrue(body(20, 0).keepsHalf(20, 14, FallDamage.Landing.CUSHIONED, false));
    }

    @Test
    void maxDropKeepingFollowsCurrentBody() {
        assertEquals(13, body(20, 0).maxDropKeeping(10, 64));
        assertEquals(5, body(12, 0).maxDropKeeping(10, 64));
        assertEquals(0, body(8, 0).maxDropKeeping(10, 64), "血不到要剩的那么多，一格都算不上");
        assertEquals(12, body(20, 0).maxDropKeeping(10, 12), "最多查到 limit 格");
        FallDamage immune = new FallDamage(20, 0, 3, 1, 0, 0, 0, 0, 0.08, 0, true);
        assertEquals(30, immune.maxDropKeeping(10, 30));
    }
}
