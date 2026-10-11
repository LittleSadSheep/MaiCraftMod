// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
     * 这么远内正追着角色的：十秒内真打过她的、引信点着的苦力怕、看得见她又亮着攻击标记的、看得见的近处苦力怕。
     * 自卫与点名战斗撤离时甩没甩开都按它算（三十二格内一只都没有），只在附近游荡、隔着墙的不算。
     */
    default List<Threat> chasers(TickContext context, double radius) {
        List<UUID> attackers = new ArrayList<>();
        for (Attacker attacker : recentAttackers(context)) attackers.add(attacker.uuid());
        List<Threat> result = new ArrayList<>();
        for (Threat threat : threats(context, radius)) {
            if (threatening(threat, attackers)) result.add(threat);
        }
        return result;
    }

    /** 这一只是不是正在威胁角色（判断见 {@link #chasers}）。 */
    static boolean threatening(Threat threat, List<UUID> attackers) {
        if (attackers.contains(threat.uuid()) || threat.armed()) return true;
        if (!threat.visible()) return false;
        boolean closeCreeper = threat.kind() == ThreatAssessment.Kind.EXPLOSIVE
                && threat.distance() <= ThreatAssessment.CREEPER_ALERT_RADIUS;
        return threat.aggressive() || closeCreeper;
    }

    /**
     * 一只敌对生物：位置供追击与出手。
     *
     * @param armed      苦力怕已经膨胀或开始闪白（引信点着了）
     * @param visible    角色看得见它：从眼睛到它之间没有方块挡着
     * @param aggressive 原版同步给客户端的攻击标记：僵尸、骷髅这类锁定了攻击目标、正要动手时亮起
     */
    record Threat(int entityId, UUID uuid, String type, double x, double y, double z,
                  double distance, ThreatAssessment.Kind kind, boolean armed, boolean visible,
                  boolean aggressive) {}

    /** 一条伤害证据：谁、什么时候（游戏刻）、什么类型的伤害。 */
    record Attacker(UUID uuid, String type, long tick, String damageKind) {}

    /** 角色的战斗资质。 */
    record CombatProfile(double health, int armorPoints,
                         Optional<WeaponChoice.Picked> weapon, int foodCount) {}
}
