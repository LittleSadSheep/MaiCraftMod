// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** 弓与近战的取舍、箭的弹道公式。 */
class RangedCombatTest {

    @Test
    void bowWhenNoBladeOrExplosiveOrUnreachable() {
        assertTrue(RangedCombat.preferBow(true, false, false, false, 2.0, false), "没有剑斧，贴脸也射");
        assertTrue(RangedCombat.preferBow(true, true, true, false, 3.0, false), "苦力怕能射就别凑近");
        assertTrue(RangedCombat.preferBow(true, true, false, true, 3.0, false), "走不到跟前的射");
        assertFalse(RangedCombat.preferBow(false, false, true, true, 10.0, false), "没弓没箭谈不上射");
    }

    @Test
    void switchingBetweenBowAndBladeHasHysteresis() {
        // 七格外换弓；已经在射时，贴近到四格半以内才换回近战，对手在六格晃时不来回换手。
        assertFalse(RangedCombat.preferBow(true, true, false, false, 6.0, false));
        assertTrue(RangedCombat.preferBow(true, true, false, false, 7.5, false));
        assertTrue(RangedCombat.preferBow(true, true, false, false, 6.0, true));
        assertFalse(RangedCombat.preferBow(true, true, false, false, 4.0, true));
    }

    @Test
    void fullDrawGivesFullSpeedAndShortDrawIsSlower() {
        assertEquals(ArrowAim.BOW_FULL_SPEED, ArrowAim.bowSpeed(20), 1e-9);
        assertTrue(ArrowAim.bowSpeed(10) < ArrowAim.bowSpeed(20));
        assertEquals(ArrowAim.BOW_FULL_SPEED, ArrowAim.bowSpeed(40), 1e-9, "拉过头不会更快");
    }

    @Test
    void timedDirectionLandsWhereItWasAimed() {
        // 按"飞 20 刻到"倒推的方向，逐刻按原版减速与下坠飞 20 刻，应该正好落在目标上。
        Vec3 start = new Vec3(0, 64, 0);
        Vec3 target = new Vec3(24, 63, 5);
        Vec3 direction = ArrowAim.directionByTime(start, target, 20, ArrowAim.BOW_FULL_SPEED);
        assertNotNull(direction);
        Vec3 position = start;
        Vec3 motion = direction.scale(ArrowAim.BOW_FULL_SPEED);
        for (int tick = 0; tick < 20; tick++) {
            position = position.add(motion);
            motion = motion.scale(ArrowAim.DRAG).add(0, -ArrowAim.GRAVITY, 0);
        }
        assertTrue(position.distanceTo(target) < 1e-6, "落点差 " + position.distanceTo(target));
    }

    @Test
    void lowArcAimsAboveTheTargetAndGivesUpBeyondRange() {
        Vec3 start = new Vec3(0, 64, 0);
        Vec3 arc = ArrowAim.lowArc(start, new Vec3(30, 64, 0), ArrowAim.BOW_FULL_SPEED);
        assertNotNull(arc);
        assertTrue(arc.y > 0, "平射三十格要往上抬一点");
        assertNull(ArrowAim.lowArc(start, new Vec3(1000, 64, 0), 0.5), "这么慢射不到那么远");
    }
}
