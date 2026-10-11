// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.Comparator;
import java.util.Set;

/**
 * 打法：这一刻做什么。纯函数，看战场快照与这一仗记着的几件事，点名战斗和自卫用同一份。
 *
 * <p>先后次序：
 * <ol>
 *   <li>躲爆炸：引信点着、还在它波及范围里的会炸物，压过一切（含血线撤离和点名目标）；</li>
 *   <li>已在撤离：开始了就跑到甩开（退无可退除外），回了一点血不回头；</li>
 *   <li>打不过：血线破了还有追兵，或手上什么都没有、对面会还手，就撤；</li>
 *   <li>挑目标：近处的苦力怕最急，其次马上要打到我的，再是点名的那一批，最后最近的；认准的那只没被更急的顶掉就接着打；</li>
 *   <li>打法：有远程且（目标点着引信、走不到、手上没近战、目标是会炸的、或离得远）就射，否则近身打；</li>
 *   <li>挑不出能打的，但还有压着我的追兵（点着引信的、刚打过我的、贴到快够得着我的）：只走位，离开所有危险半径；</li>
 *   <li>都没有：打完了。隔着坑、隔着栅栏走不到又不打我的不算，不为它们站着干耗。</li>
 * </ol>
 */
public final class Tactic {

    /** 这么近、看得见的没点引信的苦力怕最急：等它膨胀再反应就来不及躲。 */
    static final double URGENT_CREEPER = ThreatAssessment.CREEPER_ALERT_RADIUS;

    private Tactic() {}

    /** 这一刻的打法。 */
    public sealed interface Plan permits EvadeBlast, Withdraw, Melee, Shoot, KeepClear, Finished {}

    /** 躲开这只点着引信的。 */
    public record EvadeBlast(Battlefield.Foe bomb) implements Plan {}

    /** 撤离，附上为什么。 */
    public record Withdraw(String why) implements Plan {}

    /** 近身打这只。 */
    public record Melee(Battlefield.Foe target) implements Plan {}

    /** 射这只。 */
    public record Shoot(Battlefield.Foe target) implements Plan {}

    /** 没有能打的，只走位避开追兵。 */
    public record KeepClear(String why) implements Plan {}

    /** 打完了：没有要打的，也没有追兵。 */
    public record Finished() implements Plan {}

    /**
     * 这一仗记着的事。
     *
     * @param locked      认准的那只（-1 为没有）
     * @param retreating  已在撤离
     * @param cornered    撤离连着走不通，退无可退：血线不再触发撤离，就地迎战
     * @param unreachable 寻路说走不到跟前的那些
     * @param bowGivenUp  弓连着射不出去，交回近战
     * @param shooting    这一刻正在用弓（换回近战要贴得更近，进出不同线）
     */
    public record Memory(int locked, boolean retreating, boolean cornered, Set<Integer> unreachable,
                         boolean bowGivenUp, boolean shooting) {
        public Memory {
            unreachable = Set.copyOf(unreachable);
        }

        public static final Memory FRESH = new Memory(-1, false, false, Set.of(), false, false);
    }

    public static Plan decide(Battlefield field, Memory memory) {
        Battlefield.Me me = field.me();
        // 1 躲爆炸：最近的那只点着引信、还在波及范围里的。
        Battlefield.Foe bomb = null;
        for (Battlefield.Foe foe : field.foes()) {
            if (foe.fusing() && foe.distance() < foe.blastClearance()
                    && (bomb == null || foe.distance() < bomb.distance())) {
                bomb = foe;
            }
        }
        if (bomb != null) return new EvadeBlast(bomb);
        boolean chased = !field.chasers().isEmpty();
        // 2 已在撤离：保持到甩开。
        if (memory.retreating() && !memory.cornered()) return new Withdraw("已在撤离，跑到甩开为止");
        // 3 打不过。
        if (!memory.cornered() && chased && me.belowRetreatLine()) return new Withdraw("血快见底，先撤离保命");
        if (!memory.cornered() && !me.hasMelee() && !me.canShoot() && me.weaponScore() <= 0
                && field.chasers().stream().anyMatch(Battlefield.Foe::fightsBack)) {
            return new Withdraw("手上没有任何武器，对面会还手");
        }
        // 4 挑目标。
        Battlefield.Foe target = pickTarget(field, memory);
        if (target == null) {
            boolean pressed = field.chasers().stream()
                    .anyMatch(foe -> foe.fusing() || foe.attacker() || foe.imminent());
            return pressed ? new KeepClear("没有能打的，先避开追兵") : new Finished();
        }
        // 5 打法。
        return shoots(me, memory, target) ? new Shoot(target) : new Melee(target);
    }

    // 能打的：名单上要打的；点着引信的只能射；走不到的只能射。
    private static boolean eligible(Battlefield.Foe foe, Battlefield.Me me, Memory memory) {
        if (!foe.wanted()) return false;
        boolean canShoot = me.canShoot() && !memory.bowGivenUp() && foe.threat().visible()
                && foe.distance() <= RangedCombat.ENGAGE_RANGE;
        if (foe.fusing() && !canShoot) return false;
        return !memory.unreachable().contains(foe.id()) || canShoot;
    }

    // 急的先：近处看得见的苦力怕 → 马上要打到我的 → 点名的那一批 → 其余；同档近的先，同距按编号定。
    private static int urgency(Battlefield.Foe foe) {
        if (foe.threat().kind() == ThreatAssessment.Kind.EXPLOSIVE && !foe.fusing() && foe.threat().visible()
                && foe.distance() <= URGENT_CREEPER) {
            return 0;
        }
        if (foe.chasing() && foe.imminent()) return 1;
        return foe.primary() ? 2 : 3;
    }

    static Battlefield.Foe pickTarget(Battlefield field, Memory memory) {
        Battlefield.Foe best = null;
        Battlefield.Foe locked = null;
        Comparator<Battlefield.Foe> order = Comparator.comparingInt(Tactic::urgency)
                .thenComparingDouble(Battlefield.Foe::distance)
                .thenComparingInt(Battlefield.Foe::id);
        for (Battlefield.Foe foe : field.foes()) {
            if (!eligible(foe, field.me(), memory)) continue;
            if (foe.id() == memory.locked()) locked = foe;
            if (best == null || order.compare(foe, best) < 0) best = foe;
        }
        // 认准的那只接着打（分裂的史莱姆不来回转头），除非有更急的一档冒出来。
        if (locked != null && urgency(locked) <= urgency(best)) return locked;
        return best;
    }

    // 用不用弓：看得见、射程内，目标点着引信、走不到、手上没近战、是会炸的，或离得远（七格外换弓、贴近到四格半才换回）。
    private static boolean shoots(Battlefield.Me me, Memory memory, Battlefield.Foe target) {
        if (!me.canShoot() || memory.bowGivenUp() || !target.threat().visible()
                || target.distance() > RangedCombat.ENGAGE_RANGE) {
            return false;
        }
        return RangedCombat.preferBow(true, me.hasMelee(),
                target.threat().kind() == ThreatAssessment.Kind.EXPLOSIVE,
                memory.unreachable().contains(target.id()), target.distance(), memory.shooting());
    }
}
