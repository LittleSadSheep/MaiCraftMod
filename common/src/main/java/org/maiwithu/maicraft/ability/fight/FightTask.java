// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.maiwithu.maicraft.behavior.survival.CombatSenses;
import org.maiwithu.maicraft.behavior.survival.ThreatAssessment;
import org.maiwithu.maicraft.behavior.survival.WeaponChoice;
import org.maiwithu.maicraft.kernel.interrupt.ThreatResponder;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战斗任务：打点名目标，或清一片区域的敌对生物。
 *
 * <p>先评估再动手：打不过不开打，说清差在血、武器还是数量；血量跌破拒战线就撤，
 * 甩开所有追击的才算撤成，撤离了不冒充打赢。一场战斗内盯住同一个目标，不每刻重选。
 * 击败确认后就地捡一下掉落物，捡不到写进结果，不影响击败判定。
 */
final class FightTask extends PhasedTask<FightTask.Phase> implements ThreatResponder {

    /** 战斗的阶段：核实目标 → 评估 → 打 → 撤 → 捡。 */
    enum Phase { CHECK, ASSESS, FIGHT, RETREAT, LOOT }

    /** 视为贴脸、可以出手的距离（格）。 */
    private static final double STRIKE_RANGE = 3.0;

    /** 撤离寻路连续失败这么多次，才算退无可退（背水一战）。 */
    private static final int LAST_STAND_AFTER_FAILURES = ThreatAssessment.ESCAPE_FAILURES_BEFORE_LAST_STAND;

    /** 连续半分钟没打中也没走成才算卡住。 */
    private static final long STUCK_AFTER_TICKS = 20L * 30;

    private final FightInput input;
    private final CombatSenses senses;
    private final SeenTargets seenTargets;
    private final FightMoves moves;

    /** 点名目标：观察编号、实体编号、类型；区域清扫时为空。 */
    private final List<SeenTargets.Locked> named = new ArrayList<>();
    /** 已确认击败的目标。 */
    private final List<String> defeated = new ArrayList<>();
    /** 拾荒捡到的：物品与数量。 */
    private final List<String> lootGained = new ArrayList<>();

    private ThreatAssessment.Verdict verdict;
    private String weaponUsed = "空手";
    /** 打斗中盯住的目标；死亡或消失后换下一个。 */
    private Tracked engaged;
    /** 最后一个目标倒下的位置：拾荒以它为中心。 */
    private double[] lastKillSpot;
    /** 撤离寻路连续失败的次数；有真实移动就清零。 */
    private int retreatFailures;
    private Action current;
    private boolean fled;
    private int carriedBeforeLoot = -1;
    private List<FightMoves.Drop> pendingDrops = List.of();

    FightTask(FightInput input, CombatSenses senses, SeenTargets seenTargets, FightMoves moves) {
        super("战斗", Phase.CHECK, new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.input = Objects.requireNonNull(input, "input");
        this.senses = Objects.requireNonNull(senses, "senses");
        this.seenTargets = Objects.requireNonNull(seenTargets, "seenTargets");
        this.moves = Objects.requireNonNull(moves, "moves");
    }

    private final Driver driver = new Driver();

    @Override protected Action enter(Phase phase) { return driver; }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        currentContext = context;
        return switch (phase) {
            case CHECK -> check();
            case ASSESS -> assess(context);
            case FIGHT -> fight(context);
            case RETREAT -> retreat(context);
            case LOOT -> loot(context);
        };
    }

    private Next<Phase> check() {
        for (String observedId : input.seenTargets()) {
            SeenTargets.Locked locked = seenTargets.lock(currentContext, observedId);
            if (locked == null) {
                return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                        "观察编号 " + observedId + " 对不上任何东西：先 observe 确认它在不在",
                        "重新观察后用新的观察编号再打"));
            }
            named.add(locked);
        }
        return Next.go(Phase.ASSESS, named.isEmpty()
                ? "清扫 " + input.radius() + " 格内的敌对生物" : "核实了 " + named.size() + " 个点名目标");
    }

    private Next<Phase> assess(TickContext context) {
        ThreatAssessment.MySide mine = mySide(context);
        List<ThreatAssessment.Foe> foes = foesOf(context, mine);
        ThreatAssessment.Assessment assessment = ThreatAssessment.assess(mine, foes);
        verdict = assessment.verdict();
        if (verdict == ThreatAssessment.Verdict.OUTMATCHED) {
            // 点名目标已经死了就不必打：开始时已被击败，直接完成。
            if (!named.isEmpty() && namedDeadSoFar() == named.size() && threatsUnarmedOnly(foes)) {
                return Next.done(alreadyDone());
            }
            String gaps = assessment.gaps().isEmpty() ? "整体劣势" : String.join("、", assessment.gaps());
            return Next.fail(Problem.of(Problem.Kind.DANGER,
                    "打不过，先不动手：差在" + gaps
                            + "；吃点东西回血、换把更好的武器，或减少同时面对的敌人再来",
                    "先解决血量与武器的问题再开战"));
        }
        return Next.go(Phase.FIGHT, verdict == ThreatAssessment.Verdict.RISKY
                ? "有风险，保持在能进的距离" : "评估能赢，开打");
    }

    private Next<Phase> fight(TickContext context) {
        // 血线破了先撤：护甲折算后的有效血量跌破拒战线，继续打就是送死。
        if (ThreatAssessment.belowRetreatLine(mySide(context))) {
            current = null;
            return Next.go(Phase.RETREAT, "血量跌破拒战线，先撤离");
        }
        Tracked target = pickTarget(context);
        if (target == null) {
            if (named.isEmpty()) {
                // 区域清扫：没有可打的了。
                return Next.go(Phase.LOOT, lastKillSpot == null ? "附近没有敌对生物" : "清扫完毕，就地捡一下");
            }
            // 点名目标全部确认死亡：完成。
            return Next.done(settled(context));
        }
        engaged = target;
        double distance = target.distance;
        if (distance > STRIKE_RANGE) {
            current = moves.walkTo(target.x, target.y, target.z);
            stepCurrent(context, "逼近 " + target.type);
            return Next.stay();
        }
        current = moves.strike(context, target.entityId);
        stepCurrent(context, "攻击 " + target.type);
        SeenTargets.Observed after = seenTargets.observe(context, target.entityId);
        if (after != null && after.dead()) {
            defeated.add(target.type + "（实体 " + target.entityId + "）");
            recordChange(Change.of(Change.Kind.ENTITY_AFFECTED, target.type, 1));
            lastKillSpot = new double[] {target.x, target.y, target.z};
            engaged = null;
            recordProgress("击败了 " + target.type);
        }
        return Next.stay();
    }

    private Next<Phase> retreat(TickContext context) {
        double[] self = moves.selfPosition(context);
        List<CombatSenses.Threat> threats = senses.threats(context, ThreatAssessment.FLEE_CLEAR_DISTANCE);
        if (threats.isEmpty()) {
            // 甩开了：撤离成功不等于任务成功，目标没死就以失败收场，写明拒战线与出路。
            fled = true;
            return Next.fail(Problem.of(Problem.Kind.DANGER,
                    "撤离成功，甩开了追兵，但目标没有击败——这一仗算没打赢",
                    "吃点东西回血到拒战线以上，或换把武器再来"));
        }
        // 朝威胁的反方向撤：取威胁的重心，往相反方向跑。
        double cx = 0;
        double cz = 0;
        for (CombatSenses.Threat threat : threats) {
            cx += threat.x();
            cz += threat.z();
        }
        cx /= threats.size();
        cz /= threats.size();
        double dx = self[0] - cx;
        double dz = self[2] - cz;
        double norm = Math.hypot(dx, dz);
        if (norm < 0.1) {
            dx = 1;
            dz = 0;
        } else {
            dx /= norm;
            dz /= norm;
        }
        double runX = self[0] + dx * ThreatAssessment.FLEE_CLEAR_DISTANCE;
        double runZ = self[2] + dz * ThreatAssessment.FLEE_CLEAR_DISTANCE;
        current = moves.walkTo(runX, self[1], runZ);
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Failed) {
            retreatFailures++;
            current = null;
            if (retreatFailures >= LAST_STAND_AFTER_FAILURES) {
                // 退无可退：背水一战，回去打。
                recordProgress("撤离路线连续失败，退无可退");
                return Next.go(Phase.FIGHT, "退无可退，背水一战");
            }
            return Next.stay();
        }
        if (status instanceof ActionStatus.Running running && running.progressed()) {
            retreatFailures = 0;
            recordProgress(current.describe());
        }
        if (status instanceof ActionStatus.Done) {
            current = null;
        }
        return Next.stay();
    }

    private Next<Phase> loot(TickContext context) {
        if (lastKillSpot == null) {
            return Next.done(settled(context));
        }
        if (pendingDrops.isEmpty()) {
            pendingDrops = moves.dropsNear(context, lastKillSpot[0], lastKillSpot[1], lastKillSpot[2], 4.0);
            carriedBeforeLoot = moves.carriedItemCount(context);
            if (pendingDrops.isEmpty()) {
                recordProgress("身边没有掉落物可捡");
                return Next.done(settled(context));
            }
        }
        FightMoves.Drop drop = pendingDrops.get(0);
        SeenTargets.Observed observed = dropObservation(context, drop);
        double[] self = moves.selfPosition(context);
        double distance = Math.hypot(self[0] - drop.x(), self[2] - drop.z());
        if (observed == null || distance <= 1.2) {
            // 走到掉落物上或它消失了：背包多了就是捡到，没多就是被抢或没捡着。
            settleOneDrop(drop, context);
            pendingDrops.remove(0);
            return pendingDrops.isEmpty() ? Next.done(settled(context)) : Next.stay();
        }
        current = moves.walkTo(drop.x(), drop.y(), drop.z());
        stepCurrent(context, "捡 " + drop.item());
        return Next.stay();
    }

    private void settleOneDrop(FightMoves.Drop drop, TickContext context) {
        int carriedNow = moves.carriedItemCount(context);
        if (carriedBeforeLoot >= 0 && carriedNow > carriedBeforeLoot) {
            lootGained.add(drop.item());
            recordChange(Change.of(Change.Kind.ITEM_GAINED, drop.item(), carriedNow - carriedBeforeLoot));
            carriedBeforeLoot = carriedNow;
        } else {
            recordUnconfirmed(Change.of(Change.Kind.ITEM_GAINED, drop.item(), 1));
        }
    }

    // 拾荒阶段观察掉落物还在不在：观察编号体系之外，直接按实体编号查。
    private SeenTargets.Observed dropObservation(TickContext context, FightMoves.Drop drop) {
        double[] self = moves.selfPosition(context);
        boolean stillThere = !moves.dropsNear(context, drop.x(), drop.y(), drop.z(), 0.5).isEmpty()
                || Math.hypot(self[0] - drop.x(), self[2] - drop.z()) < 2.0;
        return stillThere ? new SeenTargets.Observed(0, false) : null;
    }

    /** 挑这一刻该打谁：点名目标优先（按给的顺序），区域清扫按威胁排序。 */
    private Tracked pickTarget(TickContext context) {
        if (!named.isEmpty()) {
            for (SeenTargets.Locked locked : named) {
                if (defeated.contains(describeOf(locked))) {
                    continue;
                }
                SeenTargets.Observed observed = seenTargets.observe(currentContext, locked.entityId());
                if (observed == null) {
                    // 消失的点名目标：不算击败，按没打成交代。
                    defeated.add(describeOf(locked) + "（目标消失，未确认击败）");
                    continue;
                }
                if (observed.dead()) {
                    if (!defeated.contains(describeOf(locked))) {
                        defeated.add(describeOf(locked));
                        recordChange(Change.of(Change.Kind.ENTITY_AFFECTED, locked.type(), 1));
                        lastKillSpot = moves.selfPosition(currentContext);
                    }
                    continue;
                }
                double[] self = moves.selfPosition(currentContext);
                return new Tracked(locked.entityId(), locked.type(), self[0], self[1], self[2], observed.distance());
            }
            return null;
        }
        // 区域清扫：半径内的敌对威胁按威胁排序，盯住一个打完再换。
        if (engaged != null) {
            SeenTargets.Observed observed = seenTargets.observe(currentContext, engaged.entityId);
            if (observed != null && !observed.dead()) {
                return engaged;
            }
            if (observed != null && observed.dead()) {
                defeated.add(engaged.type + "（实体 " + engaged.entityId + "）");
                recordChange(Change.of(Change.Kind.ENTITY_AFFECTED, engaged.type, 1));
                lastKillSpot = new double[] {engaged.x, engaged.y, engaged.z};
                engaged = null;
            }
        }
        List<CombatSenses.Threat> threats = senses.threats(currentContext, input.radius());
        List<ThreatAssessment.Foe> ordered = new ArrayList<>();
        for (CombatSenses.Threat threat : threats) {
            if (input.entityType() != null && !threat.type().equals(input.entityType())) {
                continue;
            }
            ordered.add(new ThreatAssessment.Foe(threat.distance(), threat.kind(), threat.armed(), threat.armed()));
        }
        ordered = ThreatAssessment.sortedByThreat(ordered);
        int limit = input.count() == null ? ordered.size() : Math.min(input.count(), ordered.size());
        if (defeated.size() >= limit) {
            return null;
        }
        for (ThreatAssessment.Foe foe : ordered) {
            CombatSenses.Threat match = findThreat(threats, foe);
            if (match == null || defeated.size() >= limit) {
                continue;
            }
            return new Tracked(match.entityId(), match.type(), match.x(), match.y(), match.z(), match.distance());
        }
        return null;
    }

    private static CombatSenses.Threat findThreat(List<CombatSenses.Threat> threats, ThreatAssessment.Foe foe) {
        for (CombatSenses.Threat threat : threats) {
            if (threat.distance() == foe.distance() && threat.kind() == foe.kind()) {
                return threat;
            }
        }
        return null;
    }

    private ThreatAssessment.MySide mySide(TickContext context) {
        CombatSenses.CombatProfile profile = senses.profile(context);
        int weaponScore = profile.weapon().map(picked -> WeaponChoice.scoreOf(picked.weapon())).orElse(0);
        if (profile.weapon().isPresent()) {
            WeaponChoice.Picked picked = profile.weapon().get();
            weaponUsed = picked.weapon() == WeaponChoice.Weapon.BARE_HANDS ? "空手" : picked.itemId();
        }
        return new ThreatAssessment.MySide(profile.health(), profile.armorPoints(),
                weaponScore, profile.foodCount(), retreatFailures < LAST_STAND_AFTER_FAILURES);
    }

    private List<ThreatAssessment.Foe> foesOf(TickContext context, ThreatAssessment.MySide ignored) {
        List<ThreatAssessment.Foe> foes = new ArrayList<>();
        double radius = named.isEmpty() ? input.radius() : ThreatAssessment.VIGILANCE_RADIUS;
        for (CombatSenses.Threat threat : senses.threats(context, radius)) {
            foes.add(new ThreatAssessment.Foe(threat.distance(), threat.kind(), threat.armed(), threat.armed()));
        }
        return ThreatAssessment.sortedByThreat(foes);
    }

    private boolean threatsUnarmedOnly(List<ThreatAssessment.Foe> foes) {
        return foes.isEmpty();
    }

    private int namedDeadSoFar() {
        int dead = 0;
        for (SeenTargets.Locked locked : named) {
            if (defeated.contains(describeOf(locked))) {
                dead++;
            }
        }
        return dead;
    }

    private TaskResult alreadyDone() {
        return TaskResult.builder(TaskResult.Status.DONE, "开始时点名目标已被击败，不用打")
                .details(new FightDetails(defeated, false, verdictName(), weaponUsed, lootGained)).build();
    }

    private TaskResult settled(TickContext context) {
        String summary = defeated.isEmpty() ? "打完了，没有确认击败的目标"
                : "击败了 " + defeated.size() + " 个目标" + (lootGained.isEmpty() ? "，没捡到掉落物" : "，捡到 " + lootGained.size() + " 组掉落物");
        return TaskResult.builder(TaskResult.Status.DONE, summary)
                .details(new FightDetails(defeated, false, verdictName(), weaponUsed, lootGained)).build();
    }

    private String verdictName() {
        return verdict == null ? "未评估" : verdict.name().toLowerCase(Locale.ROOT);
    }

    private static String describeOf(SeenTargets.Locked locked) {
        return locked.type() + "（实体 " + locked.entityId() + "）";
    }

    // 推进当前动作：做完或失败都清掉，下一刻重新决定走还是打。
    private void stepCurrent(TickContext context, String what) {
        if (current == null) {
            return;
        }
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Done || status instanceof ActionStatus.Failed) {
            if (status instanceof ActionStatus.Failed failed) {
                recordProgress(what + "没成：" + failed.problem().message());
            } else {
                recordProgress(what + "：一轮结束");
            }
            current = null;
        } else if (status instanceof ActionStatus.Running running && running.progressed()) {
            recordProgress(what);
        }
    }

    @Override
    protected ResultDetails details() {
        return new FightDetails(defeated, fled, verdictName(), weaponUsed, lootGained);
    }

    /** 正在打的仗自己接得住吗：评估不是"打不过"、血线也没破，被攻击的生存需求就让位。 */
    @Override
    public boolean confidentAgainstCurrentThreats() {
        return verdict != ThreatAssessment.Verdict.OUTMATCHED && !fled;
    }

    @Override
    public Interruptibility interruptibility(TickContext context) {
        return current != null ? current.interruptibility() : Interruptibility.WORKING;
    }

    /** 把每刻在换的当前动作接进基类的暂停与收尾：基类持有的是这个外壳。 */
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
            return current == null ? "观察战场" : current.describe();
        }
    }

    /** 战斗的结果细节：击败、撤离、评估结论、用的武器与捡到的战利品。 */
    record FightDetails(List<String> defeated, boolean fled, String threatVerdict,
                        String weaponUsed, List<String> lootGained) implements ResultDetails {
        FightDetails {
            defeated = List.copyOf(defeated);
            lootGained = List.copyOf(lootGained);
        }
    }

    /** 打斗中盯住的一个目标：编号、类型、位置、距离。 */
    private record Tracked(int entityId, String type, double x, double y, double z, double distance) {}

    private TickContext currentContext;
}
