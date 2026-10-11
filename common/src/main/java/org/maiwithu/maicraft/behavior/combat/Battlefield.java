// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战场快照：这一刻我方与每只对手，按战斗几何算好。每刻读一次，打法（{@link Tactic}）与走位都只看它，
 * 点名战斗和自卫读的是同一份。
 *
 * <p>对手只收两种：正在追我的（十秒内真打过我的、引信点着的、看得见我又亮着攻击标记的、看得见的近处苦力怕），
 * 和名单上要打的。只在附近游荡、隔着墙的不收——不躲它，也不打它。
 */
public record Battlefield(Me me, List<Foe> foes) {

    public Battlefield {
        foes = List.copyOf(foes);
    }

    /**
     * 我方：位置、身板、血、交互距离、手上的本事。
     *
     * @param reach       实体交互距离属性
     * @param attackReady 攻击充能好了（原生出手的门槛）
     * @param hasMelee    身上有剑斧这类近战武器
     * @param canShoot    身上有能射的（有箭的弓、能装的弩、联动远程）
     * @param weaponScore 挑中的武器分，威胁评估用
     */
    public record Me(double x, double y, double z, double halfWidth, double health, int armorPoints, double reach,
                     boolean attackReady, boolean hasMelee, boolean canShoot, int weaponScore, int foodCount) {

        /** 威胁评估看的我方处境；退没退路由调用方给。 */
        public ThreatAssessment.MySide side(boolean hasEscapeRoute) {
            return new ThreatAssessment.MySide(health, armorPoints, weaponScore, foodCount, hasEscapeRoute);
        }

        /** 血线破了：护甲折算后的有效血量跌到拒战线之下。 */
        public boolean belowRetreatLine() {
            return ThreatAssessment.belowRetreatLine(side(true));
        }
    }

    /**
     * 一只对手。
     *
     * @param threat   身板与几何（够不够得着、危险半径）
     * @param chasing  正在追我
     * @param attacker 十秒内真打过我
     * @param wanted   名单上要打的（不在名单上的追兵照样躲、照样还手，由名单决定）
     * @param primary  点名的或清扫的那一批：同样急时先打它们
     */
    public record Foe(CombatSenses.Threat threat, boolean chasing, boolean attacker, boolean wanted, boolean primary) {

        public int id() {
            return threat.entityId();
        }

        public double distance() {
            return threat.distance();
        }

        /** 引信点着的会炸物。 */
        public boolean fusing() {
            return threat.kind() == ThreatAssessment.Kind.EXPLOSIVE && threat.armed();
        }

        /** 躲它的爆炸要跑多远：出了熄火距离（7 格）、也出了爆炸波及（普通 6 格，闪电劈过的 12 格），再留半格。 */
        public double blastClearance() {
            return Math.max(CombatGeometry.CREEPER_DEFUSE, CombatGeometry.creeperBlastReach(threat.powered())) + 0.5;
        }

        /** 马上就要挨它的打：已经碰到、或贴到它的危险半径再加一格半以内。 */
        public boolean imminent() {
            return threat.canHitMe() || threat.distance() <= threat.dangerRadius() + 1.5;
        }

        /** 会还手的：没点引信的会炸物还只是个桶，其余都会。 */
        public boolean fightsBack() {
            return threat.kind() != ThreatAssessment.Kind.EXPLOSIVE || threat.armed();
        }

        ThreatAssessment.Foe assessed() {
            return new ThreatAssessment.Foe(threat.distance(), threat.kind(), chasing, threat.armed());
        }
    }

    /** 名单：这一仗要打谁。不在名单上的追兵照样躲开、被贴脸照样还手。 */
    public interface Roster {
        /**
         * 这一只要不要打。
         *
         * @param attacker 十秒内真打过我
         * @param chasing  正在追我
         */
        boolean wanted(CombatSenses.Threat threat, boolean attacker, boolean chasing);

        /** 整只不理：不打、不躲、不当追兵（自家人打我多半是玩笑）。 */
        default boolean ignores(CombatSenses.Threat threat) {
            return false;
        }

        /** 点名或清扫的那一批：同样急时先打。 */
        default boolean primary(CombatSenses.Threat threat) {
            return false;
        }

        /** 名单上不在敌对列表里的（点名的动物、玩家）：按实体编号补读身板。 */
        default List<Integer> namedIds() {
            return List.of();
        }
    }

    /** 读一份战场：给定半径内的追兵与名单上的，按威胁先后排好。 */
    public static Battlefield read(CombatSenses senses, BattleMoves moves, TickContext context,
                                   double radius, Roster roster) {
        CombatSenses.CombatProfile profile = senses.profile(context);
        double[] at = moves.selfPosition(context);
        int weaponScore = profile.weapon().map(WeaponChoice::scoreOf).orElse(0);
        Me me = new Me(at[0], at[1], at[2], PLAYER_HALF_WIDTH, profile.health(), profile.armorPoints(),
                profile.reach(), moves.attackReady(context), moves.hasMeleeWeapon(context),
                moves.canShoot(context), weaponScore, profile.foodCount());
        Set<UUID> attackers = new HashSet<>();
        for (CombatSenses.Attacker attacker : senses.recentAttackers(context)) attackers.add(attacker.uuid());
        List<Foe> foes = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (CombatSenses.Threat threat : senses.threats(context, radius)) {
            if (roster.ignores(threat)) continue;
            boolean attacker = attackers.contains(threat.uuid());
            boolean chasing = CombatSenses.threatening(threat, List.copyOf(attackers));
            boolean wanted = roster.wanted(threat, attacker, chasing);
            if (!chasing && !wanted) continue;
            foes.add(new Foe(threat, chasing, attacker, wanted, roster.primary(threat)));
            seen.add(threat.entityId());
        }
        // 点名的动物、玩家不在敌对列表里：按编号补读，照样算身板与够不够得着。
        for (int id : roster.namedIds()) {
            if (seen.contains(id)) continue;
            senses.living(context, id).ifPresent(threat -> {
                boolean attacker = attackers.contains(threat.uuid());
                foes.add(new Foe(threat, attacker, attacker, true, true));
            });
        }
        foes.sort(Comparator.comparing(Foe::assessed, ThreatAssessment.BY_THREAT));
        return new Battlefield(me, foes);
    }

    /** 玩家半个身宽（站着、潜行都是 0.6 宽）。 */
    public static final double PLAYER_HALF_WIDTH = 0.3;

    /** 正在追我的那些。 */
    public List<Foe> chasers() {
        return foes.stream().filter(Foe::chasing).toList();
    }

    /** 这只对手此刻还在不在场上。 */
    public Foe find(int entityId) {
        for (Foe foe : foes) {
            if (foe.id() == entityId) return foe;
        }
        return null;
    }
}
