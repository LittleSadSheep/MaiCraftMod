// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.interrupt.ThreatResponder;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 被攻击这项生存需求：有东西在威胁角色（正在攻击、锁定、近处点着引信）就尽快处理，
 * 血线跌破拒战线且还在挨打就是立刻处理；派自卫临时任务——那是战斗能力的一个无差别实例，
 * 不按名单，谁构成威胁打谁。
 *
 * <p>主任务自己就是打仗的活、且评估不是"打不过"时让位给它（任务通过威胁应答的特征接口声明）；
 * 血线破了则本能说了算，自卫照插。
 */
public final class SelfDefenseNeed implements SurvivalNeed {

    private final CombatSenses senses;
    private final SelfDefenseTask.CombatMoves moves;
    private final TaskEventSink events;

    /** @param moves 出手与走位怎么落地；生产用原生攻击与走到，测试换替身。 */
    public SelfDefenseNeed(CombatSenses senses, SelfDefenseTask.CombatMoves moves, TaskEventSink events) {
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
        SelfDefenseTask.ThreatSituation situation = SelfDefenseTask.situationOf(senses, context);
        if (situation.foes().isEmpty()) {
            return null;
        }
        Urgency urgency = ThreatAssessment.belowRetreatLine(situation.mine())
                ? Urgency.NOW : Urgency.SOON;
        // 主任务自己在打仗且接得住：让位；血线破了则不打折，NOW 照样打断一切。
        if (urgency == Urgency.SOON && currentTask instanceof ThreatResponder responder
                && responder.confidentAgainstCurrentThreats()) {
            return null;
        }
        return urgency;
    }

    @Override
    public Task createTask(TickContext context) {
        return new SelfDefenseTask(senses, moves, events);
    }

    /** 供事件与日志用：当前证据里的仇家（按类型与数量）。 */
    static List<String> describeAttackers(List<CombatSenses.Attacker> attackers) {
        List<String> names = new ArrayList<>();
        for (CombatSenses.Attacker attacker : attackers) {
            names.add(attacker.type() + "#" + ShortUUID.of(attacker.uuid()));
        }
        return names;
    }

    /** 实体编号的短写：事件里指认谁打了她，不必贴完整 UUID。 */
    static final class ShortUUID {
        private ShortUUID() {}

        static String of(UUID uuid) {
            return uuid.toString().substring(0, 8);
        }
    }
}
