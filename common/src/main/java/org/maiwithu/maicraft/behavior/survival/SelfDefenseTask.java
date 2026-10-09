// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.Objects;

import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.interrupt.DisplacedTask;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.Problem;
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

    /** 自卫的阶段：打 → 危险消失后的观察期 → 走回打点；打不动就转撤离。 */
    enum Phase { ENGAGE, WATCH, RETURN, RETREAT }

    /** 打点圆心到追击上限（只比水平距离）。 */
    static final double CHASE_LIMIT = 16.0;

    /** 危险刚离开的观察期（刻）；太短会把边界上进一出的怪漏掉。 */
    static final int WATCH_TICKS = 40;

    /** 视为贴脸、可以出手的距离（格）。 */
    private static final double STRIKE_RANGE = 3.0;

    /** 连续半分钟没打到也没走成才算卡住。 */
    private static final long STUCK_AFTER_TICKS = 20L * 30;
    /** 撤离时朝远离最近威胁的方向走这么远（格）。 */
    private static final double FLEE_DISTANCE = 12.0;

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
    /** 插这个任务的自卫需求：攻击空转的账记在它那里，跨两次任务运行也算数。 */
    private final SelfDefenseNeed need;
    /** 打击推进距上一次真实进展过了多少刻；与基类的停滞判定同一刻数，但第二次空转转撤离。 */
    private long ticksSinceProgress;
    /** 为什么转的撤离（攻击通道失灵还是血线见底）：撤离成功时进结果的问题。 */
    private Problem retreatProblem;

    private final double[] anchor = new double[3];
    private double healthAtStart = -1;
    private Action current;
    /** 当前动作在做什么、冲着谁：同一种做法、同一个目标时沿用，不每刻重建。 */
    private Mode currentMode;
    private int currentTarget = -1;
    /** 追击动作出发时目标在哪：目标跑开超过几格才重新规划，不每刻重新寻路。 */
    private final double[] chaseGoal = new double[3];

    /** 当前动作的做法：出手打，或追过去。 */
    private enum Mode { STRIKE, CHASE }

    /** 目标离追击出发时的位置超过这么远（格）才重新规划追击路线。 */
    private static final double RECHASE_AFTER = 3.0;
    private int watchTicksLeft;
    /** 收尾时走不回打点：主任务不能在新地点悄悄续上。 */
    private boolean displaced;

    SelfDefenseTask(CombatSenses senses, CombatMoves moves, TaskEventSink events, SelfDefenseNeed need) {
        super("自卫", Phase.ENGAGE, new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.senses = Objects.requireNonNull(senses, "senses");
        this.moves = Objects.requireNonNull(moves, "moves");
        this.events = Objects.requireNonNull(events, "events");
        this.need = Objects.requireNonNull(need, "need");
    }

    private final Driver driver = new Driver();

    @Override protected Action enter(Phase phase) { return driver; }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case ENGAGE -> engage(context);
            case WATCH -> watch(context);
            case RETURN -> back(context);
            case RETREAT -> retreat(context);
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
        // 血快见底：本能优先，先撤离再说，不拿剩下的两颗心去换"再打一下"。
        if (ThreatAssessment.belowRetreatLine(situation.mine())) {
            return goRetreat(Problem.of(Problem.Kind.DANGER, "血快见底，先撤离保命"));
        }
        if (situation.foes().isEmpty()) {
            watchTicksLeft = WATCH_TICKS;
            dropCurrent();
            ticksSinceProgress = 0;
            return Next.go(Phase.WATCH, "威胁暂时消失，观察一阵");
        }
        // 自卫只会近战：没点引信的会炸的不凑上去打，一拳可能把它点着；躲它归撤离与避险。
        Tracked target = situation.foes().stream()
                .filter(foe -> !(foe.foe().kind() == ThreatAssessment.Kind.EXPLOSIVE && !foe.foe().armed()))
                .findFirst().orElse(null);
        if (target == null) {
            dropCurrent();
            recordProgress("只剩没点引信的会炸的，不凑上去打，守在原地");
            return Next.stay();
        }
        // 追击上限只比水平距离：出打点 16 格的敌人不再追，守在打点附近迎击。
        double horizontalFromAnchor = Math.hypot(target.x() - anchor[0], target.z() - anchor[2]);
        Mode want = horizontalFromAnchor > CHASE_LIMIT ? null
                : target.distance() <= STRIKE_RANGE ? Mode.STRIKE : Mode.CHASE;
        if (want == null) {
            dropCurrent();
            recordProgress("敌人在追击圈外，守在打点附近");
            return Next.stay();
        }
        // 换了做法、换了目标、或追的目标已跑开：收尾旧动作再起新的；否则沿用，让出手与寻路做完。
        boolean ranOff = want == Mode.CHASE
                && Math.hypot(target.x() - chaseGoal[0], target.z() - chaseGoal[2]) > RECHASE_AFTER;
        if (current != null && (want != currentMode || target.entityId() != currentTarget || ranOff)) {
            dropCurrent();
        }
        if (current == null) {
            current = want == Mode.STRIKE ? moves.strike(context, target.entityId())
                    : moves.walkTo(target.x(), target.y(), target.z());
            currentMode = want;
            currentTarget = target.entityId();
            chaseGoal[0] = target.x();
            chaseGoal[1] = target.y();
            chaseGoal[2] = target.z();
        }
        // 打击推进的账自己记一份：连续两次空转满 600 刻说明攻击通道本身失灵，转撤离；
        // 有真实进展就把这笔账清零（连自卫需求里跨任务记的那份一起清）。
        boolean progressed = stepCurrent(context, (want == Mode.STRIKE ? "出手打 " : "追向 ") + target.type());
        if (progressed) {
            ticksSinceProgress = 0;
            need.noteAttackProgress();
        } else if (++ticksSinceProgress >= STUCK_AFTER_TICKS) {
            if (need.noteAttackStalled()) {
                return goRetreat(Problem.of(Problem.Kind.STUCK,
                        "攻击一直打不出去，连续两次 " + STUCK_AFTER_TICKS + " 刻没有新进展"));
            }
            ticksSinceProgress = 0;
            recordProgress("攻击空转了一轮，重新评估战场再试");
        }
        return Next.stay();
    }

    // 收尾并放下当前动作：换做法、换目标或威胁消失时用，旧的出手与寻路不能悬着。
    private void dropCurrent() {
        if (current != null) {
            current.close();
            current = null;
        }
        currentMode = null;
        currentTarget = -1;
    }

    /** 从"再打"升级为"撤离"：丢掉手上的动作，换到撤离阶段；记一笔进展，免得基类的卡住判定抢在撤离前把任务判死。 */
    private Next<Phase> goRetreat(Problem why) {
        dropCurrent();
        ticksSinceProgress = 0;
        recordProgress(why.message());
        retreatProblem = why;
        return Next.go(Phase.RETREAT, why.message());
    }

    /**
     * 撤离：朝远离最近威胁的方向走，走到警戒圈里没有威胁就算甩掉了。
     * 以"打不出去，已撤离"如实收场（FAILED），撤离也走不动就带着问题结束。
     */
    private Next<Phase> retreat(TickContext context) {
        ThreatSituation situation = situationOf(senses, context);
        double heartsLost = Math.max(0, (healthAtStart - situation.mine().health()) / 2.0);
        if (situation.foes().isEmpty()) {
            // 撤离成功：人已经不在开打的原地，主任务不能在这里悄悄续上。
            displaced = true;
            double drift = distanceFromAnchor(context);
            events.publish(TaskEvent.Kind.TEMPORARY_TASK_FINISHED,
                    "打不出去，已撤离；主任务停在半路等下一步指示");
            return Next.done(TaskResult.builder(TaskResult.Status.FAILED, "打不出去，已撤离")
                    .problem(retreatProblem == null
                            ? Problem.of(Problem.Kind.STUCK, "打不出去")
                            : retreatProblem)
                    .details(new DefenseDetails(drift, heartsLost)).build());
        }
        double[] self = moves.selfPosition(context);
        if (current == null) {
            // 朝背对最近威胁的方向直线走：走位只走不改，绕路交给走到。
            var nearest = situation.foes().get(0);
            double awayX = self[0] - nearest.x();
            double awayZ = self[2] - nearest.z();
            double length = Math.hypot(awayX, awayZ);
            if (length < 0.5) {
                awayX = 1;
                awayZ = 0;
                length = 1;
            }
            current = moves.walkTo(self[0] + awayX / length * FLEE_DISTANCE, self[1],
                    self[2] + awayZ / length * FLEE_DISTANCE);
        }
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Failed failed) {
            // 撤也撤不掉：如实把问题带回去，不硬撑也不装撤成了。
            events.publish(TaskEvent.Kind.NEED_UNHANDLED,
                    "想撤也走不动：" + failed.problem().message() + "；还留在威胁边上");
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE,
                    "撤离走不动：" + failed.problem().message()));
        }
        if (status instanceof ActionStatus.Running running && running.progressed()) {
            recordProgress(current.describe());
        }
        if (status instanceof ActionStatus.Done) {
            dropCurrent();
        }
        return Next.stay();
    }

    /** 角色离打点多远（水平，格）。 */
    private double distanceFromAnchor(TickContext context) {
        double[] self = moves.selfPosition(context);
        return Math.hypot(self[0] - anchor[0], self[2] - anchor[2]);
    }
    private Next<Phase> watch(TickContext context) {
        ThreatSituation situation = situationOf(senses, context);
        if (!situation.foes().isEmpty()) {
            return Next.go(Phase.ENGAGE, "又有威胁出现，继续打");
        }
        if (--watchTicksLeft <= 0) {
            dropCurrent();
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
            dropCurrent();
        }
        return Next.stay();
    }

    // 推进当前动作一刻；做完了清掉等下一轮，失败也清掉（下一轮重新决定走还是打）。
    // 回答这一刻有没有真实进展，撤离升级的空转账按它记。
    private boolean stepCurrent(TickContext context, String what) {
        if (current == null) {
            return false;
        }
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Done || status instanceof ActionStatus.Failed) {
            dropCurrent();
            recordProgress(what + "：一轮结束");
            return true;
        }
        if (status instanceof ActionStatus.Running running && running.progressed()) {
            recordProgress(what);
            return true;
        }
        return false;
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
            // 换阶段或任务结束时基类收尾外壳：当前动作一并收尾放下，换阶段后不会沿用已收尾的动作。
            dropCurrent();
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
        // 只认正在威胁角色的怪；每只连同它的评估输入一起排序，同种同距离的两只不会被认成同一只。
        List<Tracked> tracked = new ArrayList<>();
        for (CombatSenses.Threat threat : senses.threats(context, ThreatAssessment.VIGILANCE_RADIUS)) {
            if (!threatening(threat, attackers)) continue;
            boolean chasing = attackers.contains(threat.uuid()) || threat.armed();
            ThreatAssessment.Foe foe = new ThreatAssessment.Foe(threat.distance(), threat.kind(), chasing, threat.armed());
            tracked.add(new Tracked(threat.entityId(), threat.type(),
                    threat.x(), threat.y(), threat.z(), threat.distance(), foe));
        }
        tracked.sort(Comparator.comparing(Tracked::foe, ThreatAssessment.BY_THREAT));
        return new ThreatSituation(mine, tracked);
    }

    /**
     * 警戒半径里的这只敌对生物此刻是否正在威胁角色：十秒内真打过她的、引信点着的苦力怕、
     * 看得见她又亮着攻击标记的，以及看得见的近处苦力怕。只在附近游荡、隔着地面或墙壁的怪
     * 既看不见也没动手，不算——自卫去打它们只会走不过去、原地干耗，还白白打断手上的活。
     */
    static boolean threatening(CombatSenses.Threat threat, List<UUID> attackers) {
        if (attackers.contains(threat.uuid()) || threat.armed()) {
            return true;
        }
        if (!threat.visible()) {
            return false;
        }
        boolean closeCreeper = threat.kind() == ThreatAssessment.Kind.EXPLOSIVE
                && threat.distance() <= ThreatAssessment.CREEPER_ALERT_RADIUS;
        return threat.aggressive() || closeCreeper;
    }

    /** 自卫的结果细节：位移与红心差。 */
    record DefenseDetails(double driftBlocks, double heartsLost) implements ResultDetails {}
}
