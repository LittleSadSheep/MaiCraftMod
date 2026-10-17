// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** 战斗几何：出手按对方碰撞箱、挨打按原版攻击框、斜对角更近也挨打、苦力怕炸多远。 */
class CombatGeometryTest {

    private static final double EYE = 1.62;

    /** 脚下中心在 (x, 0, z)、宽 width 高 height 的碰撞箱。 */
    private static AABB box(double x, double z, double width, double height) {
        double half = width / 2;
        return new AABB(x - half, 0, z - half, x + half, height, z + half);
    }

    private static AABB zombieAt(double x, double z) {
        return box(x, z, 0.6, 1.95);
    }

    private static AABB me() {
        return box(0, 0, 0.6, 1.8);
    }

    @Test
    void myReachIsMeasuredToTheTargetsHitbox() {
        Vec3 eye = new Vec3(0, EYE, 0);
        // 默认交互距离 3：僵尸半宽 0.3，中心 3.3 格还砍得到，再远一点就够不着。
        assertTrue(CombatGeometry.playerReaches(eye, zombieAt(3.3, 0), 3.0));
        assertFalse(CombatGeometry.playerReaches(eye, zombieAt(3.31, 0), 3.0));
        // 大史莱姆（宽 2.08）离 4 格也砍得到：原先写死中心距 3 格，要白白凑近挨打。
        assertTrue(CombatGeometry.playerReaches(eye, box(4.0, 0, 2.08, 2.08), 3.0));
        // 交互距离被药水、模组拉到 4.5：跟着变。
        assertTrue(CombatGeometry.playerReaches(eye, zombieAt(4.8, 0), 4.5));
        assertEquals(0.5, CombatGeometry.reachShortfall(eye, zombieAt(3.8, 0), 3.0), 1e-9);
    }

    @Test
    void aMobHitsMeOnlyWhenItsAttackBoxTouchesMyHitbox() {
        // 正对面：每轴间隙不到"半宽 + 0.83 + 半宽"≈ 1.43 才挨打。
        assertTrue(CombatGeometry.mobReaches(zombieAt(1.42, 0), me()));
        assertFalse(CombatGeometry.mobReaches(zombieAt(1.44, 0), me()));
        // 斜对角：中心距 2 格仍挨打（攻击框是方的），要退到 2.02 格外才从任何方位都安全。
        double diagonal = 2.0 / Math.sqrt(2.0);
        assertTrue(CombatGeometry.mobReaches(zombieAt(diagonal, diagonal), me()));
        double safe = CombatGeometry.mobDangerRadius(0.3, 0.3);
        assertEquals(2.02, safe, 0.01);
        double past = (safe + 0.01) / Math.sqrt(2.0);
        assertFalse(CombatGeometry.mobReaches(zombieAt(past, past), me()));
    }

    @Test
    void widerMobsAreDangerousFromFurtherAway() {
        // 蜘蛛宽 1.4：危险半径比僵尸大一圈，原先写死 2.2 格的走位环站在它够得着的地方。
        assertTrue(CombatGeometry.mobDangerRadius(0.7, 0.3) > 2.5);
        // 我能稳稳砍到它的距离也跟着它的身板走。
        assertEquals(3.15, CombatGeometry.meleeStandOff(3.0, 0.3), 1e-9);
        assertEquals(3.55, CombatGeometry.meleeStandOff(3.0, 0.7), 1e-9);
    }

    @Test
    void creeperBlastReach() {
        assertEquals(6.0, CombatGeometry.creeperBlastReach(false), 1e-9);
        assertEquals(12.0, CombatGeometry.creeperBlastReach(true), 1e-9, "闪电劈过的翻倍");
    }
}
