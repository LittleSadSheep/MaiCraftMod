// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 战斗几何：我够不够得着它、它够不够得着我、苦力怕点火与熄火、爆炸波及多远。纯函数，全仓只有这一份。
 *
 * <p>原版玩家出手是一条从眼睛射出、长度为实体交互距离属性的射线，打到的是对方的碰撞箱——所以大史莱姆、
 * 蜘蛛离得远一点也砍得到，小僵尸要凑近；交互距离被药水、模组改了跟着变。原先写死"中心距 3 格"，
 * 宽怪明明够得着却判成够不着，要多凑近、先挨打。
 *
 * <p>原版怪的近战是它的攻击框（碰撞箱水平外扩约 0.83 格）与我的碰撞箱相交才打得到；那是个方形区域，
 * 斜对角站着更容易挨着，要从任何方位都够不着得退到外接圆外，所以按每轴间隙乘 √2。原先写死 2.2 格。
 */
public final class CombatGeometry {

    /** 原版怪攻击框的水平外扩（{@code Mob} 的默认攻击触及：√2.04 − 0.6）。 */
    public static final double MOB_ATTACK_REACH = Math.sqrt(2.04) - 0.6;
    /** 原版玩家实体交互距离属性的默认值。 */
    public static final double DEFAULT_PLAYER_REACH = 3.0;
    /** 苦力怕开始膨胀：目标中心距进了 3 格（原版膨胀目标 {@code distanceToSqr < 9}）。 */
    public static final double CREEPER_IGNITE = 3.0;
    /** 苦力怕引信熄灭：目标中心距出了 7 格（原版 {@code distanceToSqr > 49} 才倒退膨胀）。 */
    public static final double CREEPER_DEFUSE = 7.0;
    /** 普通苦力怕的爆炸威力；闪电劈过的翻倍。 */
    public static final float CREEPER_POWER = 3.0f;

    private CombatGeometry() {}

    /** 点到方框最近点的距离：点在框里为 0。 */
    public static double distanceToBox(Vec3 point, AABB box) {
        double dx = Math.max(Math.max(box.minX - point.x, 0), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0), point.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 我够不够得着它：眼睛到它碰撞箱最近点的距离不超过交互距离。真出手前原生攻击还会沿视线再射一次、
     * 确认准星落在它身上没被方块挡住，这里只回答距离。
     *
     * @param range 实体交互距离属性（默认 3）
     */
    public static boolean playerReaches(Vec3 eye, AABB target, double range) {
        return distanceToBox(eye, target) <= range;
    }

    /** 我还差多远够得着它（格）：已经够得着为 0。 */
    public static double reachShortfall(Vec3 eye, AABB target, double range) {
        return Math.max(0, distanceToBox(eye, target) - range);
    }

    /** 怪这一刻打不打得到我：它的攻击框（碰撞箱水平外扩）与我的碰撞箱相交。 */
    public static boolean mobReaches(AABB mob, AABB me) {
        return mob.inflate(MOB_ATTACK_REACH, 0.0, MOB_ATTACK_REACH).intersects(me);
    }

    /**
     * 怪从任何方位都够不着我的水平中心距：每轴要拉开"它半宽 + 0.83 + 我半宽"，方形区域的外接圆再乘 √2。
     * 走位时离它比这远就不会挨它的近战。
     */
    public static double mobDangerRadius(double mobHalfWidth, double myHalfWidth) {
        return (mobHalfWidth + MOB_ATTACK_REACH + myHalfWidth) * Math.sqrt(2.0);
    }

    /** 另一位玩家够得着我的水平中心距：他的交互距离加我半个身宽（射线打到的是我的碰撞箱）。 */
    public static double playerDangerRadius(double theirRange, double myHalfWidth) {
        return Math.max(0, theirRange) + myHalfWidth;
    }

    /**
     * 我能稳稳砍到它的水平中心距：交互距离加它半个身宽（射线打的是它的碰撞箱），再扣一点余量，
     * 免得站在边上一动就够不着。走位带的外沿用它。
     */
    public static double meleeStandOff(double myRange, double targetHalfWidth) {
        return Math.max(0.5, myRange + targetHalfWidth - 0.15);
    }

    /** 爆炸伤害波及多远：原版爆炸的伤害到威力的两倍远归零。 */
    public static double blastReach(float power) {
        return power * 2.0;
    }

    /** 苦力怕炸到多远：普通 6 格，闪电劈过的 12 格。 */
    public static double creeperBlastReach(boolean powered) {
        return blastReach(powered ? CREEPER_POWER * 2 : CREEPER_POWER);
    }
}
