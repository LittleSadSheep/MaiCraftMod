// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.behavior.combat.BattleMoves;
import org.maiwithu.maicraft.behavior.combat.CombatGeometry;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.interrupt.ThreatResponder;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 被攻击这项生存需求：有东西在威胁角色（正在攻击、锁定、近处点着引信）就处理；派自卫临时任务——
 * 那是战斗能力的一个无差别实例，不按名单，谁构成威胁打谁。
 *
 * <p>多急：点着引信的苦力怕进了 7 格（引信 1.5 秒就炸）、有怪已经贴到快够得着我的地方（它的危险半径再加
 * 一格半）、血线跌破拒战线还在挨打——这三种立刻处理，手上的活停不停得下都先顾命；其余尽快处理。
 * 原先只有血线破了才算立刻，施工、走路这类一时停不下来的活会让僵尸贴着打、苦力怕贴着炸。
 *
 * <p>主任务自己就是打仗的活、且评估不是"打不过"时让位给它（任务通过威胁应答的特征接口声明），
 * 怪贴脸、苦力怕点着它自己会处理；只有血线破了本能说了算，自卫照插。
 *
 * <p>自卫没做成（比如追不上、打不出去）也不缓：每一下新挨的打都是新的处境，缓着就是站着挨打。
 * 隔着坑、隔着栅栏走不到又没打我的，上一次自卫记下后半分钟内不再为它插进来，它真动手或贴上来照样处理。
 */
public final class SelfDefenseNeed implements SurvivalNeed {

    /** 贴脸边上再加这么多（格）就算马上要挨打：僵尸一步就跨过来了。 */
    static final double IMMINENT_MARGIN = 1.5;
    /** 自卫走不到、又没打我的那只，这么久内不再为它插进来（刻）。 */
    static final long IGNORE_UNREACHABLE_TICKS = 20L * 30;

    private final CombatSenses senses;
    private final BattleMoves moves;
    private final TaskEventSink events;
    /** 上一次自卫走不到、又没打我的：实体编号到哪一刻为止不再理它。隔着坑、隔着栅栏冲我吼的怪不让主任务停停走走。 */
    private final Map<Integer, Long> ignoredUntil = new HashMap<>();
    private long now;

    /** @param moves 出手与走位怎么落地；生产用原生攻击与走到，测试换替身。 */
    public SelfDefenseNeed(CombatSenses senses, BattleMoves moves, TaskEventSink events) {
        this.senses = senses;
        this.moves = moves;
        this.events = events;
    }

    @Override public String name() { return "自卫"; }

    @Override
    public Urgency urgency(TickContext context) {
        return urgency(context, null);
    }

    @Override
    public Urgency urgency(TickContext context, Task currentTask) {
        now = context.gameTick();
        List<UUID> attackers = new ArrayList<>();
        for (CombatSenses.Attacker attacker : senses.recentAttackers(context)) attackers.add(attacker.uuid());
        boolean threatened = false;
        boolean imminent = false;
        for (CombatSenses.Threat threat : senses.threats(context, ThreatAssessment.VIGILANCE_RADIUS)) {
            if (!CombatSenses.threatening(threat, attackers)) continue;
            if (!attackers.contains(threat.uuid()) && ignored(threat.entityId()) && !imminent(threat)) continue;
            threatened = true;
            imminent |= imminent(threat);
        }
        if (!threatened) {
            return null;
        }
        CombatSenses.CombatProfile profile = senses.profile(context);
        boolean belowLine = ThreatAssessment.effectiveHealth(profile.health(), profile.armorPoints())
                < ThreatAssessment.RETREAT_LINE_EFFECTIVE_HEALTH;
        // 主任务自己在打仗且接得住：让位（贴脸的、点着引信的它自己处理）；血线破了则不打折，本能接管。
        if (!belowLine && currentTask instanceof ThreatResponder responder
                && responder.confidentAgainstCurrentThreats()) {
            return null;
        }
        return belowLine || imminent ? Urgency.NOW : Urgency.SOON;
    }

    /** 马上就要出事：点着引信的会炸物进了熄火距离，或有怪贴到它的危险半径再加一格半以内。 */
    static boolean imminent(CombatSenses.Threat threat) {
        if (threat.kind() == ThreatAssessment.Kind.EXPLOSIVE) {
            return threat.armed() && threat.distance() <= CombatGeometry.CREEPER_DEFUSE;
        }
        return threat.canHitMe() || threat.distance() <= threat.dangerRadius() + IMMINENT_MARGIN;
    }

    /** 被攻击不缓：自卫没做成后下一刻处境还在就接着插，不让角色站着挨打。 */
    @Override
    public boolean waitsAfterFailure() {
        return false;
    }

    @Override
    public Task createTask(TickContext context) {
        return new SelfDefenseTask(senses, moves, events, this);
    }

    /** 这只是不是上一次自卫记下的走不到、又没打我的（还在记着的时间里）。 */
    boolean ignored(int entityId) {
        Long until = ignoredUntil.get(entityId);
        return until != null && until > now;
    }

    /** 自卫收场时记下走不到的那些：半分钟内它们不打我就不再为它们插进来。 */
    void ignoreUnreachable(Set<Integer> entityIds, long tick) {
        ignoredUntil.values().removeIf(until -> until <= tick);
        for (int id : entityIds) ignoredUntil.put(id, tick + IGNORE_UNREACHABLE_TICKS);
    }
}
