// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Objects;

import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.interrupt.DisplacedTask;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 自卫临时任务：谁威胁角色就打谁，打完回到开打的原地。
 *
 * <p>无差别实例：不按名单，正在威胁的都算（会分裂的怪裂开后新个体天然进威胁集）。
 * 不追出打点 16 格（只比水平距离）；撤退与躲爆炸不受这个限制。危险消失后再观察一小段
 * （跑得几乎一样快的怪会在边界上一进一出，不撒手太早）。收尾走回打点；走不回去
 * 就声明回不了岗位——主任务停在半路等 LLM 决定，事件里写明停在了哪里。
 */
final class SelfDefenseTask extends PhasedTask<SelfDefenseTask.Phase> implements DisplacedTask {

    /** 自卫的阶段：打 → 危险消失后的观察期 → 走回打点。 */
    enum Phase { ENGAGE, WATCH, RETURN }

    /** 打点圆心到追击上限（只比水平距离）。 */
    static final double CHASE_LIMIT = 16.0;

    /** 危险刚离开的观察期（刻）；太短会把边界上进一出的怪漏掉。 */
    static final int WATCH_TICKS = 40;

    /** 视为贴脸、可以出手的距离（格）。 */
    private static final double STRIKE_RANGE = 3.0;

    /** 连续半分钟没打到也没走成才算卡住。 */
    private static final long STUCK_AFTER_TICKS = 20L * 30;

    /**
     * 出手与走位怎么落地：任务负责挑目标与时机，动手交给行为模型。
     * 生产实现是原生攻击与走到；离线测试换替身记录调用。
     */
    interface CombatMoves {
        /** 对这只生物出手一次（原生攻击，逐刻确认）。 */
        Action strike(TickContext context, int entityId);

        /** 朝一个位置走过去（只走不改）。 */
        Action walkTo(double x, double y, double z);

        /** 此刻角色所在的位置。 */
        double[] selfPosition(TickContext context);
    }

    private final CombatSenses senses;
    private final CombatMoves moves;
    private final TaskEventSink events;

    private final double[] anchor = new double[3];
    private double healthAtStart = -1;
    private Action current;
    private int watchTicksLeft;
    /** 收尾时走不回打点：主任务不能在新地点悄悄续上。 */
    private boolean displaced;

    SelfDefenseTask(CombatSenses senses, CombatMoves moves, TaskEventSink events) {
        super("自卫", Phase.ENGAGE, new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.senses = Objects.requireNonNull(senses, "senses");
        this.moves = Objects.requireNonNull(moves, "moves");
        this.events = Objects.requireNonNull(events, "events");
    }

    private final Driver driver = new Driver();

    @Override protected Action enter(Phase phase) { return driver; }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case ENGAGE -> engage(context);
            case WATCH -> watch(context);
            case RETURN -> back(context);
        };
    }

    private Next<Phase> engage(TickContext context) {
        ThreatSituation situation = situationOf(senses, context);
        if (healthAtStart < 0) {
            healthAtStart = situation.mine().health();
            double[] self = moves.selfPosition(context);
            anchor[0] = self[0];
            anchor[1] = self[1];
            anchor[2] = self[2];
            events.publish(TaskEvent.Kind.TEMPORARY_TASK_STARTED,
                    "被威胁，插入自卫：打点在 " + Math.round(self[0]) + ", " + Math.round(self[1]) + ", " + Math.round(self[2]));
        }
        if (situation.foes().isEmpty()) {
            watchTicksLeft = WATCH_TICKS;
            current = null;
            return Next.go(Phase.WATCH, "威胁暂时消失，观察一阵");
        }
        var target = situation.foes().get(0);
        double[] self = moves.selfPosition(context);
        // 追击上限只比水平距离：出打点 16 格的敌人不再追，守在打点附近迎击。
        double horizontalFromAnchor = Math.hypot(target.x() - anchor[0], target.z() - anchor[2]);
        if (target.distance() <= STRIKE_RANGE && horizontalFromAnchor <= CHASE_LIMIT) {
            current = moves.strike(context, target.entityId());
            stepCurrent(context, "出手打 " + target.type());
        } else if (horizontalFromAnchor <= CHASE_LIMIT) {
            current = moves.walkTo(target.x(), target.y(), target.z());
            stepCurrent(context, "追向 " + target.type());
        } else {
            current = null;
            recordProgress("敌人在追击圈外，守在打点附近");
        }
        return Next.stay();
    }

    private Next<Phase> watch(TickContext context) {
        ThreatSituation situation = situationOf(senses, context);
        if (!situation.foes().isEmpty()) {
            return Next.go(Phase.ENGAGE, "又有威胁出现，继续打");
        }
        if (--watchTicksLeft <= 0) {
            return Next.go(Phase.RETURN, "观察期内没有新威胁，走回打点");
        }
        recordProgress("观察期还剩 " + watchTicksLeft + " 刻");
        return Next.stay();
    }

    private Next<Phase> back(TickContext context) {
        double[] self = moves.selfPosition(context);
        double drift = Math.hypot(self[0] - anchor[0], self[2] - anchor[2]);
        double heartsLost = Math.max(0, (healthAtStart - situationOf(senses, context).mine().health()) / 2.0);
        if (drift < 1.5) {
            events.publish(TaskEvent.Kind.TEMPORARY_TASK_FINISHED,
                    "自卫结束，回到打点；位移 " + Math.round(drift) + " 格，掉了约 " + Math.round(heartsLost) + " 颗心");
            return Next.done(TaskResult.builder(TaskResult.Status.DONE, "自卫结束，回到打点继续干活")
                    .details(new DefenseDetails(drift, heartsLost)).build());
        }
        if (current == null) {
            current = moves.walkTo(anchor[0], anchor[1], anchor[2]);
        }
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Failed) {
            // 走不回去：不冒充回到了岗位。主任务停在半路，事件里说明停在了哪里。
            displaced = true;
            String where = Math.round(self[0]) + ", " + Math.round(self[1]) + ", " + Math.round(self[2]);
            events.publish(TaskEvent.Kind.NEED_UNHANDLED,
                    "自卫结束但走不回打点（还差 " + Math.round(drift) + " 格），停在了 " + where
                            + "；主任务已暂停，等下一步指示");
            return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                            "自卫结束；人停在了 " + where + "，回不去原来的打点")
                    .details(new DefenseDetails(drift, heartsLost)).build());
        }
        if (status instanceof ActionStatus.Running running && running.progressed()) {
            recordProgress(current.describe());
        }
        if (status instanceof ActionStatus.Done) {
            current = null;
        }
        return Next.stay();
    }

    // 推进当前动作一刻；做完了清掉等下一轮，失败也清掉（下一轮重新决定走还是打）。
    private void stepCurrent(TickContext context, String what) {
        if (current == null) {
            return;
        }
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Done || status instanceof ActionStatus.Failed) {
            current = null;
            recordProgress(what + "：一轮结束");
        } else if (status instanceof ActionStatus.Running running && running.progressed()) {
            recordProgress(what);
        }
    }

    @Override
    protected ResultDetails details() {
        return ResultDetails.NONE;
    }

    @Override
    public boolean cannotResumeInPlace() {
        return displaced;
    }

    @Override
    public String stoppedWhere() {
        return Math.round(anchor[0]) + ", " + Math.round(anchor[1]) + ", " + Math.round(anchor[2]);
    }

    /** 把每刻在换的当前动作接进基类的暂停：基类持有的是这个外壳。 */
    private final class Driver implements Action {
        @Override
        public ActionStatus tick(TickContext context) {
            return current == null ? ActionStatus.running() : current.tick(context);
        }

        @Override
        public void pause() {
            if (current != null) current.pause();
        }

        @Override
        public void close() {
            if (current != null) current.close();
        }

        @Override
        public String describe() {
            return current == null ? "警戒中" : current.describe();
        }
    }

    /** 一次评估好的现场：我方处境与按威胁排序的敌人（含位置）。 */
    record ThreatSituation(ThreatAssessment.MySide mine, List<Tracked> foes) {
        ThreatSituation {
            foes = List.copyOf(foes);
        }
    }

    /** 一只排好序的敌人：威胁评估的输入加上位置与编号，供追击与出手。 */
    record Tracked(int entityId, String type, double x, double y, double z,
                   double distance, ThreatAssessment.Foe foe) {}

    /** 从感观读一份现场：敌对威胁与伤害证据合并成威胁列表，按威胁排序。 */
    static ThreatSituation situationOf(CombatSenses senses, TickContext context) {
        CombatSenses.CombatProfile profile = senses.profile(context);
        int weaponScore = profile.weapon().map(picked -> WeaponChoice.scoreOf(picked.weapon())).orElse(0);
        ThreatAssessment.MySide mine = new ThreatAssessment.MySide(
                profile.health(), profile.armorPoints(), weaponScore, profile.foodCount(), true);
        List<UUID> attackers = new ArrayList<>();
        for (CombatSenses.Attacker attacker : senses.recentAttackers(context)) {
            attackers.add(attacker.uuid());
        }
        List<ThreatAssessment.Foe> ordered = new ArrayList<>();
        List<CombatSenses.Threat> threats = senses.threats(context, ThreatAssessment.VIGILANCE_RADIUS);
        for (CombatSenses.Threat threat : threats) {
            boolean chasing = attackers.contains(threat.uuid()) || threat.armed();
            ordered.add(new ThreatAssessment.Foe(threat.distance(), threat.kind(), chasing, threat.armed()));
        }
        ordered = ThreatAssessment.sortedByThreat(ordered);
        List<Tracked> tracked = new ArrayList<>();
        for (ThreatAssessment.Foe foe : ordered) {
            CombatSenses.Threat match = null;
            for (CombatSenses.Threat threat : threats) {
                if (threat.distance() == foe.distance() && threat.kind() == foe.kind()) {
                    match = threat;
                    break;
                }
            }
            if (match != null) {
                tracked.add(new Tracked(match.entityId(), match.type(),
                        match.x(), match.y(), match.z(), match.distance(), foe));
            }
        }
        return new ThreatSituation(mine, tracked);
    }

    /** 自卫的结果细节：位移与红心差。 */
    record DefenseDetails(double driftBlocks, double heartsLost) implements ResultDetails {}
}
