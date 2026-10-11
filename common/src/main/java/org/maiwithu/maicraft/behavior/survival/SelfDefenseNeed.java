// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.CombatGeometry;
import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;

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
 */
public final class SelfDefenseNeed implements SurvivalNeed {

    private final CombatSenses senses;
    private final SelfDefenseTask.CombatMoves moves;
    private final TaskEventSink events;
    /** 打击推进连续空转的笔数：任务每次空转满 600 刻记一笔，满两笔就该撤离了。 */
    private int attackStalls;

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
        boolean belowLine = ThreatAssessment.belowRetreatLine(situation.mine());
        // 主任务自己在打仗且接得住：让位（贴脸的、点着引信的它自己处理）；血线破了则不打折，本能接管。
        if (!belowLine && currentTask instanceof ThreatResponder responder
                && responder.confidentAgainstCurrentThreats()) {
            return null;
        }
        return belowLine || imminent(situation) ? Urgency.NOW : Urgency.SOON;
    }

    /** 贴脸边上再加这么多（格）就算马上要挨打：僵尸一步就跨过来了。 */
    static final double IMMINENT_MARGIN = 1.5;

    /** 马上就要出事：点着引信的会炸物进了熄火距离，或有怪贴到它的危险半径再加一格半以内。 */
    static boolean imminent(SelfDefenseTask.ThreatSituation situation) {
        for (SelfDefenseTask.Tracked foe : situation.foes()) {
            if (foe.foe().kind() == ThreatAssessment.Kind.EXPLOSIVE) {
                if (foe.foe().armed() && foe.distance() <= CombatGeometry.CREEPER_DEFUSE) return true;
                continue;
            }
            if (foe.canHitMe() || foe.distance() <= foe.dangerRadius() + IMMINENT_MARGIN) return true;
        }
        return false;
    }

    /** 被攻击不缓：自卫没做成后下一刻处境还在就接着插，不让角色站着挨打。 */
    @Override
    public boolean waitsAfterFailure() {
        return false;
    }

    @Override
    public Task createTask(TickContext context) {
        // 空转的账记在需求自己这里：临时任务每次运行都是新的，但"已经空转过几次"要跨运行算总账，
        // 这样第二次空转一满 600 刻就转撤离，不会无限地"打不出去→重来→再打不出去"。
        return new SelfDefenseTask(senses, moves, events, this);
    }

    /** 打击推进又空转满 600 刻：记一笔；这是第二笔就该撤了。 */
    boolean noteAttackStalled() {
        return ++attackStalls >= 2;
    }

    /** 打击有了真实进展（出手、追上、走位在动）：空转的账清零。 */
    void noteAttackProgress() {
        attackStalls = 0;
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
