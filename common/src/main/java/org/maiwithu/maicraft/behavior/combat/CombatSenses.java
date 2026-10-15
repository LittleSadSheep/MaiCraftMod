// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战斗的现场感观：角色周围的敌对威胁、谁最近真打过她。
 *
 * <p>敌对判定用敌对标记接口，不用怪物类的继承树——史莱姆、恶魂、幻翼、疣猪兽
 * 都不在 Monster 的常用子包里，按类扫会漏。伤害证据（谁在十秒内真打过她）由同一份
 * 记忆给出：中立的生物没进威胁集，但动了手就在。
 */
public interface CombatSenses {

    /** 给定半径内看见的敌对威胁；自卫用警戒半径，区域清扫给清扫半径。 */
    List<Threat> threats(TickContext context, double radius);

    /** 最近真打过角色的实体（伤害证据窗口 10 秒），含沿投射物回溯到的射手。 */
    List<Attacker> recentAttackers(TickContext context);

    /** 角色此刻的战斗资质：血量、护甲点数、随身武器（用于威胁评估与武器挑选）。 */
    CombatProfile profile(TickContext context);

    /** 按游戏实体编号找回实体；还活着且在附近时才有。自卫出手要的是实体本身。 */
    Entity entityById(TickContext context, int entityId);

    /**
     * 按实体编号读任意一只活物的身板与几何（点名的动物、玩家不在敌对列表里）：玩家的危险半径按他的交互距离算，
     * 会打人的按原版攻击框算，不会打人的（鸡牛羊）危险半径为 0，走上去打就是。不在附近为空。
     */
    default Optional<Threat> living(TickContext context, int entityId) {
        return Optional.empty();
    }

    /**
     * 打龙的龙场：龙、龙头、水晶、龙息、龙火球、末影人、出口门。没有加载着的活龙（或在死亡动画里的龙）为空。
     *
     * @param dragonId 打哪一条（实体编号）；-1 为离出口门最近的那条
     */
    default Optional<DragonArena> dragonArena(TickContext context, int dragonId) {
        return Optional.empty();
    }

    /** 正冲着角色飞来、能打回去的火球（恶魂火球这类）里最近的那个的实体编号；没有为空。 */
    default OptionalInt returnableIncoming(TickContext context) {
        return OptionalInt.empty();
    }

    /**
     * 这一只是不是正在威胁角色：十秒内真打过她的、引信点着的苦力怕、看得见她又亮着攻击标记的、看得见的近处苦力怕。
     * 只在附近游荡、隔着墙的不算。
     */
    static boolean threatening(Threat threat, List<UUID> attackers) {
        if (attackers.contains(threat.uuid()) || threat.armed()) return true;
        if (!threat.visible()) return false;
        boolean closeCreeper = threat.kind() == ThreatAssessment.Kind.EXPLOSIVE
                && threat.distance() <= ThreatAssessment.CREEPER_ALERT_RADIUS;
        return threat.aggressive() || closeCreeper;
    }

    /**
     * 一只敌对生物：位置供追击与出手，身板与够不够得着按战斗几何算好（{@link CombatGeometry}）。
     *
     * @param distance     两边脚下中心的距离（格）
     * @param armed        苦力怕已经膨胀或开始闪白（引信点着了）
     * @param visible      角色看得见它：从眼睛到它之间没有方块挡着
     * @param aggressive   原版同步给客户端的攻击标记：僵尸、骷髅这类锁定了攻击目标、正要动手时亮起
     * @param halfWidth    它半个身宽（格）
     * @param reachGap     我眼睛到它碰撞箱最近点的距离（格）：不超过我的交互距离就够得着
     * @param canHitMe     它此刻打不打得到我：原版攻击框与我的碰撞箱相交（只算近战）
     * @param dangerRadius 它从任何方位都够不着我的水平中心距（格）
     * @param powered      闪电劈过的苦力怕：爆炸范围翻倍
     * @param dodgesArrows 箭打不中它（末影人会瞬移躲开）：只近战，不白费箭
     */
    record Threat(int entityId, UUID uuid, String type, double x, double y, double z,
                  double distance, ThreatAssessment.Kind kind, boolean armed, boolean visible,
                  boolean aggressive, double halfWidth, double reachGap, boolean canHitMe,
                  double dangerRadius, boolean powered, boolean dodgesArrows) {

        /** 箭打得中的一只。 */
        public Threat(int entityId, UUID uuid, String type, double x, double y, double z,
                      double distance, ThreatAssessment.Kind kind, boolean armed, boolean visible,
                      boolean aggressive, double halfWidth, double reachGap, boolean canHitMe,
                      double dangerRadius, boolean powered) {
            this(entityId, uuid, type, x, y, z, distance, kind, armed, visible, aggressive, halfWidth, reachGap,
                    canHitMe, dangerRadius, powered, false);
        }

        /** 身板按僵尸估的一只（离线替身用；生产实现按真实碰撞箱算）。 */
        public Threat(int entityId, UUID uuid, String type, double x, double y, double z,
                      double distance, ThreatAssessment.Kind kind, boolean armed, boolean visible,
                      boolean aggressive) {
            this(entityId, uuid, type, x, y, z, distance, kind, armed, visible, aggressive,
                    0.3, Math.max(0, distance - 0.3),
                    distance < CombatGeometry.mobDangerRadius(0.3, 0.3) / Math.sqrt(2.0),
                    CombatGeometry.mobDangerRadius(0.3, 0.3), false);
        }

        /** 我此刻够不够得着它。 */
        public boolean withinReach(double myRange) {
            return reachGap <= myRange;
        }

        /**
         * 它是不是冲着我来的：十秒内真打过我的、引信点着的、看得见我又亮着攻击标记的。
         * 原先点名战斗只看引信，正追着砍我的僵尸被当成闲逛的，排序与"打不打得过"都算轻了。
         */
        public boolean comingAt(Collection<UUID> attackers) {
            return attackers.contains(uuid) || armed || aggressive && visible;
        }
    }

    /** 一条伤害证据：谁、什么时候（游戏刻）、什么类型的伤害。 */
    record Attacker(UUID uuid, String type, long tick, String damageKind) {}

    /**
     * 角色的战斗资质。
     *
     * @param reach 实体交互距离属性（默认 3；药水、模组改了跟着变）
     */
    record CombatProfile(double health, int armorPoints,
                         Optional<WeaponChoice.Picked> weapon, int foodCount, double reach) {

        public CombatProfile(double health, int armorPoints, Optional<WeaponChoice.Picked> weapon, int foodCount) {
            this(health, armorPoints, weapon, foodCount, CombatGeometry.DEFAULT_PLAYER_REACH);
        }
    }
}
