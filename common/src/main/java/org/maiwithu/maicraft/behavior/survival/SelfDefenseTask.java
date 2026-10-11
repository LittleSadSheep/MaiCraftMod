// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.Objects;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.combat.Battle;
import org.maiwithu.maicraft.behavior.combat.BattleMoves;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.interrupt.DisplacedTask;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 自卫插曲：谁威胁角色就打谁，打完回到开打的原地。怎么打全交给战斗引擎（{@link Battle}），和点名战斗是同一个；
 * 这里只记插曲的账：打点、开始与结束事件、危险消失后观察一阵、打完走回打点、回不去就暂停主任务说明停在哪。
 *
 * <p>名单无差别：正在追我的都打（会分裂的怪裂开后新个体天然进来）。不追出打点 16 格（只比水平距离）去打没在打我的；
 * 正压着我打的、远处射我的不受这个限制。危险消失后再观察两秒（跑得几乎一样快的怪会在边界上一进一出）；
 * 撤离甩开了就是这次处理好了，人已不在原地，主任务不在这里悄悄续上。
 */
final class SelfDefenseTask extends PhasedTask<SelfDefenseTask.Phase> implements DisplacedTask {

    /** 自卫的阶段：打 → 危险消失后的观察期 → 走回打点。撤离在打的阶段里由引擎做。 */
    enum Phase { ENGAGE, WATCH, RETURN }

    /** 打点圆心到追击上限（只比水平距离）。 */
    static final double CHASE_LIMIT = 16.0;

    /** 危险刚离开的观察期（刻）；太短会把边界上进一出的怪漏掉。 */
    static final int WATCH_TICKS = 40;

    /** 整场半分钟没有任何进展（没出手、没迈步、没射出一箭）才算卡住。 */
    private static final long STUCK_AFTER_TICKS = 20L * 30;

    private final CombatSenses senses;
    private final BattleMoves moves;
    private final TaskEventSink events;
    private final SelfDefenseNeed need;
    private final Battle battle;

    private final double[] anchor = new double[3];
    private double healthAtStart = -1;
    private int watchTicksLeft;
    private boolean reportedCornered;
    private Action walkBack;
    /** 撤离后人不在原地，或收尾走不回打点：主任务不能在新地点悄悄续上。 */
    private boolean displaced;

    SelfDefenseTask(CombatSenses senses, BattleMoves moves, TaskEventSink events, SelfDefenseNeed need) {
        super("自卫", Phase.ENGAGE, new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.senses = Objects.requireNonNull(senses, "senses");
        this.moves = Objects.requireNonNull(moves, "moves");
        this.events = Objects.requireNonNull(events, "events");
        this.need = Objects.requireNonNull(need, "need");
        this.battle = new Battle(senses, moves, this::wanted, ThreatAssessment.VIGILANCE_RADIUS);
    }

    // 名单：正在追我的都打；出打点 16 格、又没在打我的不追（守在打点附近），正压着我打的、远处射我的照打；
    // 前一次自卫记下走不到、又没打我的不为它再插进来。
    private boolean wanted(CombatSenses.Threat threat, boolean attacker, boolean chasing) {
        if (!chasing) return false;
        if (!attacker && need.ignored(threat.entityId())) return false;
        boolean shooting = threat.kind() == ThreatAssessment.Kind.RANGED && threat.aggressive() && threat.visible();
        return attacker || shooting || Math.hypot(threat.x() - anchor[0], threat.z() - anchor[2]) <= CHASE_LIMIT;
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
        if (healthAtStart < 0) {
            healthAtStart = senses.profile(context).health();
            double[] self = moves.selfPosition(context);
            System.arraycopy(self, 0, anchor, 0, 3);
            events.publish(TaskEvent.Kind.TEMPORARY_TASK_STARTED,
                    "被威胁，插入自卫：打点在 " + Math.round(self[0]) + ", " + Math.round(self[1]) + ", " + Math.round(self[2]));
        }
        Battle.Moment moment = battle.tick(context);
        if (moment.progressed()) recordProgress(moment.doing());
        if (moment.cornered() && !reportedCornered) {
            reportedCornered = true;
            events.publish(TaskEvent.Kind.NEED_UNHANDLED, "想撤也走不动：撤离路线连着走不通，退无可退，就地迎战");
        }
        return switch (moment.state()) {
            case ESCAPED -> escaped(context, moment.retreatWhy());
            case FINISHED -> {
                watchTicksLeft = WATCH_TICKS;
                yield Next.go(Phase.WATCH, "威胁暂时消失，观察一阵");
            }
            default -> Next.stay();
        };
    }

    // 观察期：引擎照样每刻看一眼，又有要打的就回去打；走不到又不打我的引擎自己不理。
    private Next<Phase> watch(TickContext context) {
        Battle.Moment moment = battle.tick(context);
        if (moment.state() != Battle.State.FINISHED) {
            return Next.go(Phase.ENGAGE, "又有威胁出现，继续打");
        }
        if (--watchTicksLeft <= 0) {
            return Next.go(Phase.RETURN, "观察期内没有新威胁，走回打点");
        }
        recordProgress("观察期还剩 " + watchTicksLeft + " 刻");
        return Next.stay();
    }

    private Next<Phase> back(TickContext context) {
        double drift = distanceFromAnchor(context);
        double heartsLost = heartsLost(context);
        if (drift < 1.5) {
            need.ignoreUnreachable(battle.unreachable(), context.gameTick());
            events.publish(TaskEvent.Kind.TEMPORARY_TASK_FINISHED,
                    "自卫结束，回到打点；位移 " + Math.round(drift) + " 格，掉了约 " + Math.round(heartsLost) + " 颗心");
            return Next.done(TaskResult.builder(TaskResult.Status.DONE, "自卫结束，回到打点继续干活")
                    .details(new DefenseDetails(drift, heartsLost)).build());
        }
        if (walkBack == null) {
            BlockPos cell = BlockPos.containing(anchor[0], anchor[1], anchor[2]);
            walkBack = moves.walk(GoalCompiler.near(cell, 1.0));
        }
        ActionStatus status = walkBack.tick(context);
        if (status instanceof ActionStatus.Failed) {
            // 走不回去：不冒充回到了岗位。主任务停在半路，事件里说明停在了哪里。
            displaced = true;
            need.ignoreUnreachable(battle.unreachable(), context.gameTick());
            double[] self = moves.selfPosition(context);
            String where = Math.round(self[0]) + ", " + Math.round(self[1]) + ", " + Math.round(self[2]);
            events.publish(TaskEvent.Kind.NEED_UNHANDLED,
                    "自卫结束但走不回打点（还差 " + Math.round(drift) + " 格），停在了 " + where
                            + "；主任务已暂停，等下一步指示");
            return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                            "自卫结束；人停在了 " + where + "，回不去原来的打点")
                    .details(new DefenseDetails(drift, heartsLost)).build());
        }
        if (status instanceof ActionStatus.Running running && running.progressed()) {
            recordProgress(walkBack.describe());
        }
        if (status instanceof ActionStatus.Done) {
            walkBack.close();
            walkBack = null;
        }
        return Next.stay();
    }

    // 撤离成功：甩开了追兵，这次被攻击就算处理好了（记完成）。记成失败会让生存需求缓一阵，
    // 缓着的那几秒再被追上就是站着挨打。人已经不在开打的原地，主任务不能在这里悄悄续上。
    private Next<Phase> escaped(TickContext context, String why) {
        displaced = true;
        double drift = distanceFromAnchor(context);
        String summary = "已撤离，甩开了追兵（" + (why == null ? "打不过" : why) + "），离打点 " + Math.round(drift) + " 格";
        events.publish(TaskEvent.Kind.TEMPORARY_TASK_FINISHED, summary + "；主任务停在半路等下一步指示");
        return Next.done(TaskResult.builder(TaskResult.Status.DONE, summary)
                .details(new DefenseDetails(drift, heartsLost(context))).build());
    }

    private double heartsLost(TickContext context) {
        return Math.max(0, (healthAtStart - senses.profile(context).health()) / 2.0);
    }

    /** 角色离打点多远（水平，格）。 */
    private double distanceFromAnchor(TickContext context) {
        double[] self = moves.selfPosition(context);
        return Math.hypot(self[0] - anchor[0], self[2] - anchor[2]);
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

    @Override
    public Interruptibility interruptibility(TickContext context) {
        return walkBack != null ? walkBack.interruptibility() : battle.interruptibility();
    }

    /** 引擎与走回打点接进基类的暂停与收尾：基类持有的是这个外壳。 */
    private final class Driver implements Action {
        @Override
        public ActionStatus tick(TickContext context) {
            return ActionStatus.running();
        }

        @Override
        public void pause() {
            battle.pause();
            if (walkBack != null) walkBack.pause();
        }

        @Override
        public void close() {
            // 换阶段或任务结束时基类收尾外壳：出手、寻路、拉了一半的弓收尾，盾放下。
            battle.close();
            if (walkBack != null) walkBack.close();
            walkBack = null;
        }

        @Override
        public String describe() {
            return walkBack != null ? walkBack.describe() : battle.describe();
        }
    }

    /** 自卫的结果细节：位移与红心差。 */
    record DefenseDetails(double driftBlocks, double heartsLost) implements ResultDetails {}
}
