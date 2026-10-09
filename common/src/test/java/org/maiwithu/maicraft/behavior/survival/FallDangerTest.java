// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.task.Urgency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 坠落判断：看预计落地伤害，不看已经掉了多远；虚空必救，落进水里不用救。 */
class FallDangerTest {

    @Test
    void notFallingIsNotAFallHazard() {
        // 站在地上、贴着墙走：没有在往下掉，哪怕下面就是悬崖。
        assertNull(FallDanger.assess(view(false, false, false, false)));
    }

    @Test
    void survivableFallIsNotUrgent() {
        // 落地会疼但死不了：落地时游戏自己结算，不打断手上的活。
        assertNull(FallDanger.assess(view(true, false, false, true)));
    }

    @Test
    void lethalLandingMustBeHandledRightAway() {
        // 刚从高台边掉下去，预计落地伤害够得到生命：趁还在高处就得自救，不等摔到一半。
        assertEquals(Urgency.NOW, FallDanger.assess(view(true, false, false, false)));
    }

    @Test
    void landingInWaterNeedsNoRescue() {
        assertNull(FallDanger.assess(view(true, false, true, false)), "落进水里不掉血");
    }

    @Test
    void fallingIntoTheVoidIsLethalRegardlessOfHealth() {
        assertEquals(Urgency.NOW, FallDanger.assess(view(true, true, false, false)));
    }

    private static FallDanger.View view(boolean falling, boolean overVoid, boolean water, boolean survives) {
        return new FallDanger.View() {
            @Override public boolean falling() { return falling; }
            @Override public boolean overVoid() { return overVoid; }
            @Override public boolean landsInWater() { return water; }
            @Override public boolean survivesLanding() { return survives; }
        };
    }
}
