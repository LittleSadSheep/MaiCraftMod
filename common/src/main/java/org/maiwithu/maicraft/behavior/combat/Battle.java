// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.navigation.calc.ThreatField;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战斗引擎：每刻读战场 → 定打法 → 脚和手两层一起做。点名战斗与自卫用的是同一个，区别只在名单。
 *
 * <p>脚：近处（看得见、差不多同一层、离带子不远）不经寻路，按 {@link Footwork} 每刻挑一步——离目标落在距离带里、
 * 离别的追兵都在危险半径外，身后是墙就侧移，被夹住就往开口退；远处、隔墙、高低差大的交给寻路，目标是
 * "距离带 + 绕开每只怪"。手：够得着（眼睛到对方碰撞箱不超过交互距离）就出手，充能与无敌帧由原生攻击自己等；
 * 冷却中、对手近就举盾。两层互不排斥：退着也挥，拉弓也走。
 *
 * <p>躲爆炸先跑后算：当刻按背离苦力怕的方向疾跑，八个方向都迈不出去才交给寻路。撤离往没怪的方向跑、甩开才停，
 * 被追上就面朝它退着还手；连着三次走不通就背水一战。
 */
public final class Battle {

    /** 这一刻在做什么。 */
    public enum State { FIGHTING, SHOOTING, EVADING, RETREATING, HOLDING, ESCAPED, FINISHED, NEEDS_SHELTER }

    /**
     * 一刻的结果。
     *
     * @param progressed 这一刻有真实进展（出手、射出、迈了步、寻路走了）
     * @param struck     确认打中了一下
     * @param fired      射出去一箭
     * @param targetId   这一刻打的、躲的那只（-1 为没有）
     * @param retreatWhy 撤离的原因；没撤为 null
     * @param cornered   撤离走不通、退无可退
     */
    public record Moment(State state, String doing, boolean progressed, boolean struck, boolean fired,
                          int targetId, String retreatWhy, boolean cornered) {}

    /** 带子外沿再远这么多以内、看得见、差不多同一层：直接走位，不寻路。 */
    static final double NEAR_MARGIN = 3.0;
    /** 上下差这么多以内算同一层。 */
    static final double LEVEL_GAP = 1.5;
    /** 别的怪的危险半径外再留这么多。 */
    static final double SIDE_MARGIN = 0.35;
    /** 冷却中退到它够不着外最多这么远。 */
    static final double COOL_FAR = 1.2;
    /** 拉弓时离每一只都至少这么远：拉满一张弓要一秒。 */
    static final double BOW_KEEP = 5.0;
    /** 拉弓的距离带：内沿 6 到 10 格（对手冲过来越快留得越多），外沿再远 4 格。 */
    static final double BOW_INNER_MIN = 6.0;
    static final double BOW_INNER_MAX = 10.0;
    static final double BOW_BAND_WIDTH = 4.0;
    /** 寻路按格子走，人可能站在格子任何角落：危险半径加半个格子对角线再交给寻路。 */
    static final double CELL_SLACK = Math.sqrt(2.0) / 2.0;
    /** 目标跑开这么远才重新寻路。 */
    static final double REPLAN_AFTER = 3.0;
    /** 原生攻击连着被拒这么多次才当打不出去，换下一只。 */
    static final int MAX_REJECTS = 3;
    /** 被拒一次后这么多刻里绕开刚才那个站位，换个方位再出手。 */
    static final int REJECT_SIDESTEP_TICKS = 15;
    /** 绕开刚才站位多远（格）。 */
    static final double REJECT_SIDESTEP = 1.2;
    /** 补站位：每这么多刻外沿往里收一步。 */
    static final int TRIM_EVERY = 5;
    /** 补站位每步收多少格。 */
    static final double TRIM_STEP = 0.25;
    /** 外沿最多收到离它这么近（格）。 */
    static final double MIN_OUTER = 0.8;
    /** 补了这么久（刻）还够不着就当走不到。 */
    static final int TRIM_GIVE_UP = 60;
    /** 充能到这么多就起跳：落到能砍的时候正好充满，跳劈不比站着砍慢。 */
    static final float JUMP_AT = 0.5f;
    /** 连着这么多刻想跳却没离地（灵魂沙、蜂蜜块上）：这一阵不跳了，站着砍。 */
    static final int MAX_GROUNDED_JUMPS = 5;
    /** 跳不起来之后多久不再试跳（刻）。 */
    static final int NO_LEAP_TICKS = 100;

    private final CombatSenses senses;
    private final BattleMoves moves;
    private final Battlefield.Roster roster;
    private final double radius;

    private final RangedCombat bow = new RangedCombat();
    private final Retreat retreat = new Retreat();
    private Action strike;
    private int strikeTarget = -1;
    private int rejects;
    private Action walk;
    private String walkKey;
    private double[] walkAnchor;
    private int locked = -1;
    private boolean retreating;
    private boolean cornered;
    private final Set<Integer> unreachable = new HashSet<>();
    /** 上一刻每只的距离：估它冲过来多快，拉弓的距离带按它留。 */
    private final Map<Integer, Double> lastDistance = new HashMap<>();
    private String doing = "观察战场";
    /** 被拒的那个站位与还要绕开它几刻。 */
    private double[] rejectedAt;
    private int sidestepTicks;
    /** 调用方接得住"挖坑自保"（自卫交给挖三填一）；一仗只要一次。 */
    private boolean offersShelter;
    private boolean shelterAsked;
    /** 就地垫高：试过了没有（一仗只垫一次）、站没站上柱顶、柱顶多高。 */
    private boolean pillarTried;
    private boolean perched;
    private double perchY;
    private Action pillar;
    /** 补站位：在给哪只补、够不着几刻了。 */
    private int trimTarget = -1;
    private int trimTicks;
    /** 连着想跳却没离地的刻数，与这之后不再试跳还剩多少刻。 */
    private int groundedJumps;
    private int noLeapTicks;
    /** 上一段寻路走不通：逼近的那只记成走不到。 */
    private boolean lastWalkFailed;

    /** @param radius 看多远的战场：自卫用警戒半径，区域清扫用清扫半径；撤离时自动看三十二格 */
    public Battle(CombatSenses senses, BattleMoves moves, Battlefield.Roster roster, double radius) {
        this.senses = senses;
        this.moves = moves;
        this.roster = roster;
        this.radius = radius;
    }

    public Moment tick(TickContext context) {
        Battlefield field = Battlefield.read(senses, moves, context,
                retreating ? ThreatAssessment.FLEE_CLEAR_DISTANCE : radius, roster);
        if (retreating) retreat.noteChasers(field);
        // 被打下柱顶（击退、柱子被炸）：不再算站在高处。
        if (perched && field.me().y() < perchY - 0.5) perched = false;
        boolean mayPillar = !pillarTried && cornered && moves.canPillar(context);
        // 天黑才挖坑；拉开距离后就挖，不必等到退无可退（何时挖由打法按追兵远近定）。
        boolean mayDigIn = offersShelter && !shelterAsked && moves.night(context);
        Tactic.Plan plan = Tactic.decide(field, new Tactic.Memory(locked, retreating, cornered, unreachable,
                bow.givenUp(), bow.shooting(), mayPillar || pillar != null, perched, mayDigIn));
        if (pillar != null && !(plan instanceof Tactic.PillarUp)) {
            // 垫到一半处境变了（回了血、来了射手）：垫高收尾，按新打法来。
            pillar.close();
            pillar = null;
        }
        Moment moment = switch (plan) {
            case Tactic.EvadeBlast evade -> evade(context, field, evade.bomb());
            case Tactic.Withdraw withdraw -> withdraw(context, field, withdraw.why());
            case Tactic.PillarUp pillarUp -> pillarUp(context, field, pillarUp.why());
            case Tactic.DigIn digIn -> {
                // 挖坑熬夜是整夜的事，引擎做不了：收起手脚，交给调用方（自卫接挖三填一）。
                shelterAsked = true;
                close();
                yield new Moment(State.NEEDS_SHELTER, digIn.why(), false, false, false, -1, digIn.why(), true);
            }
            case Tactic.Melee melee -> melee(context, field, melee.target());
            case Tactic.Shoot shoot -> shoot(context, field, shoot.target());
            case Tactic.KeepClear keep -> keepClear(context, field, keep.why());
            case Tactic.Finished finished -> finish();
        };
        for (Battlefield.Foe foe : field.foes()) lastDistance.put(foe.id(), foe.distance());
        doing = moment.doing();
        return moment;
    }

    // 躲爆炸：当刻疾跑背离它，出了熄火距离与爆炸波及再停；八个方向都迈不出去才交给寻路找别的路。
    private Moment evade(TickContext context, Battlefield field, Battlefield.Foe bomb) {
        dropStrike();
        bow.close();
        Battlefield.Me me = field.me();
        List<Footwork.Hazard> hazards = new ArrayList<>();
        hazards.add(new Footwork.Hazard(bomb.threat().x(), bomb.threat().z(), bomb.blastClearance()));
        hazards.addAll(sideHazards(field, bomb.id(), 0));
        Footwork.Stance stance = new Footwork.Stance(null, hazards, true);
        String what = "躲开点着引信的 " + bomb.threat().type();
        if (perched) {
            // 站在柱顶：跳下去更危险，原地举盾挡爆炸。
            moves.step(context, bomb.id(), Footwork.Step.STAND);
            moves.guard(context, bomb.distance(), false);
            return new Moment(State.EVADING, what + "（在柱顶上举盾）", false, false, false, bomb.id(), null, cornered);
        }
        Footwork.Step step = Footwork.choose(me.x(), me.z(), stance, moves.ground(context));
        if (step.standing() && Footwork.exposed(me.x(), me.z(), stance)) {
            List<ThreatField.Threat> threats = new ArrayList<>(pathThreats(field, -1));
            threats.add(new ThreatField.Threat(bomb.threat().x(), bomb.threat().y(), bomb.threat().z(),
                    bomb.blastClearance() + CELL_SLACK, bomb.blastClearance()));
            boolean moved = follow(context, "躲爆炸 " + bomb.id(), bomb.threat(), () -> GoalCompiler.awayFrom(threats));
            return new Moment(State.EVADING, what + "（寻路绕出去）", moved, false, false, bomb.id(), null, cornered);
        }
        dropWalk();
        moves.step(context, -1, step);
        return new Moment(State.EVADING, what, !step.standing(), false, false, bomb.id(), null, cornered);
    }

    // 撤离：甩开了就收；被追上就面朝它退着还手；否则往没怪的方向的落点跑，走不通换方向，连着三次走不通背水一战。
    private Moment withdraw(TickContext context, Battlefield field, String why) {
        if (!retreating) {
            retreating = true;
            retreat.begin(why, field);
            dropWalk();
            bow.close();
        }
        if (retreat.escaped(field)) {
            retreating = false;
            close();
            return new Moment(State.ESCAPED, "甩开了追兵", true, false, false, -1, retreat.why(), false);
        }
        Battlefield.Me me = field.me();
        Battlefield.Foe pursuer = nearestChaser(field);
        if (pursuer != null && pursuer.threat().visible() && pursuer.imminent()) {
            dropWalk();
            boolean inReach = pursuer.threat().withinReach(me.reach());
            boolean struck = inReach && swing(context, pursuer.id(), me);
            if (!inReach) dropStrike();
            moves.guard(context, pursuer.distance(), inReach && me.attackReady());
            Footwork.Step step = Footwork.choose(me.x(), me.z(),
                    new Footwork.Stance(null, sideHazards(field, -1, 1.0), false), moves.ground(context));
            moves.step(context, pursuer.id(), step);
            return new Moment(State.RETREATING, "撤离途中被追上，退着还手", struck || !step.standing(), struck,
                    false, pursuer.id(), retreat.why(), cornered);
        }
        dropStrike();
        if (pursuer != null) moves.guard(context, pursuer.distance(), false);
        if (walk == null) {
            double cx = 0;
            double cz = 0;
            List<Battlefield.Foe> chasers = field.chasers();
            for (Battlefield.Foe foe : chasers) {
                cx += foe.threat().x();
                cz += foe.threat().z();
            }
            cx /= Math.max(1, chasers.size());
            cz /= Math.max(1, chasers.size());
            // 这一刻一只追兵都看不见：按最后露面的位置背离着跑，不朝世界原点反方向乱跑。
            double[] remembered = chasers.isEmpty() ? retreat.pursuersCenter() : null;
            if (remembered != null) {
                cx = remembered[0];
                cz = remembered[1];
            }
            int[] haven = Retreat.haven(me.x(), me.y(), me.z(), cx, cz, retreat.turns(),
                    (x, y, z) -> moves.standable(context, x, y, z));
            List<ThreatField.Threat> threats = pathThreats(field, -1);
            GoalCompiler.Compiled goal = haven != null
                    ? GoalCompiler.approachAvoiding(GoalCompiler.near(new BlockPos(haven[0], haven[1], haven[2]), 2.0), threats)
                    : threats.isEmpty() ? null : GoalCompiler.awayFrom(threats.stream()
                            .map(threat -> threat.withClearance(Retreat.REACH_OUT)).toList());
            if (goal != null) {
                walk = moves.walk(goal);
                walkKey = "撤离";
            }
        }
        ActionStatus status = walk == null ? null : walk.tick(context);
        if (status == null || status instanceof ActionStatus.Failed) {
            dropWalk();
            if (retreat.routeFailed(me.x(), me.z())) {
                // 退无可退：回去就地迎战，血线不再触发撤离（没有地方可撤）。
                cornered = true;
                retreating = false;
                return new Moment(State.RETREATING, "撤离路线连着走不通，退无可退，就地迎战", false, false, false,
                        -1, retreat.why(), true);
            }
            return new Moment(State.RETREATING, "撤离走不通，换个方向再试", false, false, false, -1, retreat.why(), cornered);
        }
        if (status instanceof ActionStatus.Done) dropWalk();
        boolean moved = status instanceof ActionStatus.Running running && running.progressed();
        return new Moment(State.RETREATING, "撤离：" + retreat.why(), moved, false, false, -1, retreat.why(), cornered);
    }

    // 就地垫高：往上垫三格站到柱顶；垫不成就照旧背水一战，这一仗不再试。
    private Moment pillarUp(TickContext context, Battlefield field, String why) {
        dropStrike();
        dropWalk();
        bow.close();
        if (pillar == null) {
            pillarTried = true;
            pillar = moves.pillarUp(context);
            if (pillar == null) return new Moment(State.FIGHTING, "垫不了高，背水一战", false, false, false, -1, why, cornered);
        }
        ActionStatus status = pillar.tick(context);
        if (status instanceof ActionStatus.Running running) {
            return new Moment(State.FIGHTING, "就地往上垫", running.progressed(), false, false, -1, why, cornered);
        }
        pillar.close();
        pillar = null;
        if (status instanceof ActionStatus.Done) {
            perched = true;
            perchY = moves.selfPosition(context)[1];
            return new Moment(State.FIGHTING, "垫高了三格，站在柱顶上打够得着的", true, false, false, -1, why, cornered);
        }
        return new Moment(State.FIGHTING, "垫不上去，背水一战", false, false, false, -1, why, cornered);
    }

    // 近身打：手够得着就挥；脚近处按距离带挑步，远处交给寻路（带子 + 绕开每只怪）。
    private Moment melee(TickContext context, Battlefield field, Battlefield.Foe target) {
        if (bow.shooting()) bow.close();
        retarget(target.id());
        Battlefield.Me me = field.me();
        CombatSenses.Threat threat = target.threat();
        boolean inReach = threat.visible() && threat.withinReach(me.reach());
        // 跳劈：够得着、头顶有空、不是苦力怕（跳起来落不回原地躲不了），充能快好时起跳，往下落时才砍，原版算暴击；
        // 跳不了（水里、梯子上、头顶有方块）就站着砍。
        BattleMoves.Posture posture = moves.posture(context);
        if (noLeapTicks > 0) noLeapTicks--;
        boolean leaping = inReach && noLeapTicks == 0 && threat.kind() != ThreatAssessment.Kind.EXPLOSIVE
                && (posture.canJump() || !posture.onGround());
        boolean jumpNow = leaping && posture.onGround() && posture.charge() >= JUMP_AT;
        groundedJumps = jumpNow ? groundedJumps + 1 : posture.onGround() ? groundedJumps : 0;
        if (groundedJumps > MAX_GROUNDED_JUMPS) {
            // 按了跳却一直没离地：这一阵站着砍，别卡在"等跳起来"上一下都不出手。
            groundedJumps = 0;
            noLeapTicks = NO_LEAP_TICKS;
            leaping = false;
            jumpNow = false;
        }
        boolean holdSwing = leaping && (posture.onGround() || !posture.falling());
        boolean swinging = inReach && !holdSwing;
        boolean struck = swinging && swing(context, target.id(), me);
        if (!inReach) dropStrike();
        if (threat.dangerRadius() > 0) moves.guard(context, threat.distance(), inReach && me.attackReady());
        else moves.lowerGuard();
        Footwork.Band band = trimmed(meleeBand(me, threat, me.attackReady()), target.id(), inReach, threat.visible());
        boolean moved;
        if (sidestepTicks > 0) sidestepTicks--;
        if (perched) {
            // 站在柱顶：脚不动（一挪就掉下去），只砍够得着的。
            dropWalk();
            moves.step(context, target.id(), Footwork.Step.STAND);
            moved = false;
        } else if (near(me, threat, band.outer())) {
            dropWalk();
            List<Footwork.Hazard> hazards = sideHazards(field, target.id(), SIDE_MARGIN);
            if (sidestepTicks > 0 && rejectedAt != null) {
                // 刚被拒了一下：在带子里绕开刚才那个站位，换个方位再出手（方块边角、栅栏缝常挡着那一个角度）。
                hazards.add(new Footwork.Hazard(rejectedAt[0], rejectedAt[1], REJECT_SIDESTEP));
            }
            Footwork.Step step = Footwork.choose(me.x(), me.z(), new Footwork.Stance(band, hazards, false),
                    moves.ground(context));
            if (jumpNow) step = new Footwork.Step(step.dx(), step.dz(), true, false);
            // 这一刻手上正瞄着出手：脚照迈，镜头留给出手的瞄点（它跟着对方横移、夹在碰撞箱里）。
            if (swinging) moves.stepKeepingAim(context, step);
            else moves.step(context, target.id(), step);
            moved = !step.standing() || jumpNow;
        } else {
            List<ThreatField.Threat> threats = pathThreats(field, -1);
            Vec3 focus = new Vec3(threat.x(), threat.y(), threat.z());
            moved = follow(context, "逼近 " + target.id(), threat,
                    () -> GoalCompiler.approachAvoiding(GoalCompiler.band(focus, 0, band.outer()), threats));
            if (lastWalkFailed) unreachable.add(target.id());
        }
        String what = (inReach ? "打 " : "逼近 ") + threat.type();
        return new Moment(State.FIGHTING, what, struck || moved, struck, false, target.id(), null, cornered);
    }

    // 射：推进手上这一箭；快放手对准时站住，其余时候按拉弓的距离带走位，离每一只都至少五格。
    private Moment shoot(TickContext context, Battlefield field, Battlefield.Foe target) {
        dropStrike();
        dropWalk();
        moves.lowerGuard();
        retarget(target.id());
        CombatSenses.Threat threat = target.threat();
        boolean fired = bow.tick(context, moves, threat.entityId());
        boolean moved = false;
        if (!bow.steady() && !perched) {
            double inner = bowInner(target);
            Footwork.Band band = new Footwork.Band(threat.x(), threat.z(), inner, inner + BOW_BAND_WIDTH);
            List<Footwork.Hazard> hazards = new ArrayList<>();
            for (Battlefield.Foe foe : field.chasers()) {
                if (foe.id() == target.id()) continue;
                hazards.add(new Footwork.Hazard(foe.threat().x(), foe.threat().z(),
                        Math.max(foe.threat().dangerRadius() + SIDE_MARGIN, BOW_KEEP)));
            }
            Footwork.Step step = Footwork.choose(field.me().x(), field.me().z(),
                    new Footwork.Stance(band, hazards, false), moves.ground(context));
            moves.step(context, target.id(), step);
            moved = !step.standing();
        }
        return new Moment(State.SHOOTING, "射 " + threat.type(), fired || moved, false, fired, target.id(), null, cornered);
    }

    // 没有能打的：离开所有追兵的危险半径（点着引信的按爆炸范围），看着最近的那只，不跑远。
    private Moment keepClear(TickContext context, Battlefield field, String why) {
        dropStrike();
        dropWalk();
        bow.close();
        Battlefield.Me me = field.me();
        Battlefield.Foe nearest = nearestChaser(field);
        Footwork.Step step = perched ? Footwork.Step.STAND : Footwork.choose(me.x(), me.z(),
                new Footwork.Stance(null, sideHazards(field, -1, SIDE_MARGIN), false), moves.ground(context));
        moves.step(context, nearest == null ? -1 : nearest.id(), step);
        if (nearest != null) moves.guard(context, nearest.distance(), false);
        return new Moment(State.HOLDING, why, !step.standing(), false, false,
                nearest == null ? -1 : nearest.id(), null, cornered);
    }

    private Moment finish() {
        close();
        return new Moment(State.FINISHED, "打完了", false, false, false, -1, null, cornered);
    }

    /**
     * 近身的距离带：冷却好了内沿是它够得着我、外沿是我稳稳够得着它；冷却中退到它够不着外 0.35 到 1.2 格。
     * 两边手一样长（打玩家）没有"我够得着它够不着"的缝，冷却好了才短暂凑进去出手；苦力怕守在点火距离外。
     */
    static Footwork.Band meleeBand(Battlefield.Me me, CombatSenses.Threat threat, boolean attackReady) {
        double standoff = CombatGeometry.meleeStandOff(me.reach(), threat.halfWidth());
        double danger = threat.dangerRadius();
        if (!attackReady && danger > 0) {
            return new Footwork.Band(threat.x(), threat.z(), danger + SIDE_MARGIN, Math.max(danger + COOL_FAR, standoff));
        }
        double inner;
        if (threat.kind() == ThreatAssessment.Kind.EXPLOSIVE) inner = danger + 0.1;
        else inner = danger < standoff - 0.1 ? danger : Math.max(0, standoff - 0.85);
        return new Footwork.Band(threat.x(), threat.z(), inner, Math.max(inner + 0.1, standoff));
    }

    /**
     * 补站位：站在带里却够不着（被方块边角挡着、它在坑里低一截）时，外沿每过几刻往里收一点，凑近到真够得着；
     * 收到底还够不着就当这只走不到——有弓去射，没弓换下一只，不原地空转。
     */
    private Footwork.Band trimmed(Footwork.Band band, int targetId, boolean inReach, boolean visible) {
        if (targetId != trimTarget || inReach || !visible) {
            trimTarget = targetId;
            trimTicks = 0;
            return band;
        }
        trimTicks++;
        double trim = Math.min(band.outer() - Math.max(band.inner() + 0.1, MIN_OUTER), (trimTicks / TRIM_EVERY) * TRIM_STEP);
        if (trimTicks > TRIM_GIVE_UP) {
            unreachable.add(targetId);
            trimTicks = 0;
        }
        return trim <= 0 ? band : new Footwork.Band(band.x(), band.z(), band.inner(), band.outer() - trim);
    }

    // 拉弓的内沿：对手冲过来越快留得越多，拉满一张弓的一秒半里它能走多远就多留多远。
    private double bowInner(Battlefield.Foe target) {
        Double before = lastDistance.get(target.id());
        double closing = before == null ? 0 : Math.max(0, before - target.distance());
        return Math.min(BOW_INNER_MAX, BOW_INNER_MIN + closing * 20 * 1.5);
    }

    // 近处：看得见、差不多同一层、离带子外沿不远。
    private static boolean near(Battlefield.Me me, CombatSenses.Threat threat, double outer) {
        return threat.visible() && Math.abs(threat.y() - me.y()) <= LEVEL_GAP && threat.distance() <= outer + NEAR_MARGIN;
    }

    // 走位要躲的别的怪：除了要打的那只，每只追兵在它危险半径外再留一点；点着引信的按爆炸范围。
    private static List<Footwork.Hazard> sideHazards(Battlefield field, int exceptId, double margin) {
        List<Footwork.Hazard> hazards = new ArrayList<>();
        for (Battlefield.Foe foe : field.chasers()) {
            if (foe.id() == exceptId) continue;
            double radius = foe.fusing() ? foe.blastClearance() : foe.threat().dangerRadius() + margin;
            if (radius > 0) hazards.add(new Footwork.Hazard(foe.threat().x(), foe.threat().z(), radius));
        }
        return hazards;
    }

    // 交给寻路的威胁场：每只追兵（含要打的那只）按危险半径，寻路按格子走再加半个格子对角线。
    private static List<ThreatField.Threat> pathThreats(Battlefield field, int exceptId) {
        List<ThreatField.Threat> threats = new ArrayList<>();
        for (Battlefield.Foe foe : field.chasers()) {
            if (foe.id() == exceptId) continue;
            double danger = foe.fusing() ? foe.blastClearance() : foe.threat().dangerRadius();
            if (danger <= 0) continue;
            threats.add(new ThreatField.Threat(foe.threat().x(), foe.threat().y(), foe.threat().z(),
                    danger + CELL_SLACK, danger));
        }
        return threats;
    }

    // 跟着一段寻路走：同一件事、目标没跑开就沿用，跑开三格以上或换了事才重新算路；走不通记下来。
    private boolean follow(TickContext context, String key, CombatSenses.Threat anchor,
                           Supplier<GoalCompiler.Compiled> goal) {
        lastWalkFailed = false;
        if (walk != null && (!key.equals(walkKey)
                || Math.hypot(anchor.x() - walkAnchor[0], anchor.z() - walkAnchor[2]) > REPLAN_AFTER)) {
            dropWalk();
        }
        if (walk == null) {
            walk = moves.walk(goal.get());
            walkKey = key;
            walkAnchor = new double[] {anchor.x(), anchor.y(), anchor.z()};
        }
        ActionStatus status = walk.tick(context);
        if (status instanceof ActionStatus.Failed) {
            lastWalkFailed = true;
            dropWalk();
            return false;
        }
        if (status instanceof ActionStatus.Done) dropWalk();
        return status instanceof ActionStatus.Running running && running.progressed();
    }

    // 出手一下：同一只沿用进行中的出手；打中了清掉等下一下。被拒一次记下站位、接下来绕开它换个方位再出手，
    // 连着被拒三次就当这只打不出去，换下一只。
    private boolean swing(TickContext context, int targetId, Battlefield.Me me) {
        if (strike != null && strikeTarget != targetId) dropStrike();
        if (strike == null) {
            strike = moves.strike(context, targetId);
            strikeTarget = targetId;
        }
        ActionStatus status = strike.tick(context);
        if (status instanceof ActionStatus.Done) {
            dropStrike();
            rejects = 0;
            return true;
        }
        if (status instanceof ActionStatus.Failed) {
            dropStrike();
            rejectedAt = new double[] {me.x(), me.z()};
            sidestepTicks = REJECT_SIDESTEP_TICKS;
            if (++rejects >= MAX_REJECTS) {
                unreachable.add(targetId);
                rejects = 0;
                sidestepTicks = 0;
            }
        }
        return false;
    }

    private void retarget(int id) {
        if (locked != id) {
            locked = id;
            rejects = 0;
        }
    }

    private static Battlefield.Foe nearestChaser(Battlefield field) {
        Battlefield.Foe nearest = null;
        for (Battlefield.Foe foe : field.chasers()) {
            if (nearest == null || foe.distance() < nearest.distance()) nearest = foe;
        }
        return nearest;
    }

    private void dropStrike() {
        if (strike != null) strike.close();
        strike = null;
        strikeTarget = -1;
    }

    private void dropWalk() {
        if (walk != null) walk.close();
        walk = null;
        walkKey = null;
    }

    /** 不打了：出手、寻路、垫高、拉了一半的弓都收尾，盾放下。 */
    public void close() {
        dropStrike();
        dropWalk();
        if (pillar != null) pillar.close();
        pillar = null;
        bow.close();
        moves.lowerGuard();
    }

    /** 暂停：寻路与出手停在原处，弓取消。 */
    public void pause() {
        if (strike != null) strike.pause();
        if (walk != null) walk.pause();
        bow.close();
    }

    /** 调用方接得住挖坑自保：夜里退无可退、打不过时引擎报 {@link State#NEEDS_SHELTER} 交给它。 */
    public void offerShelter() {
        offersShelter = true;
    }

    /** 已在撤离（甩开前不回头）。 */
    public boolean retreating() {
        return retreating;
    }

    /** 寻路说走不到、或出手连着被拒的那些：结果里写够不着谁，自卫记一阵不再为它插进来。 */
    public Set<Integer> unreachable() {
        return Set.copyOf(unreachable);
    }

    /** 撤离连着走不通，正在背水一战。 */
    public boolean cornered() {
        return cornered;
    }

    public Interruptibility interruptibility() {
        if (walk != null) return walk.interruptibility();
        if (strike != null) return strike.interruptibility();
        return Interruptibility.WORKING;
    }

    public String describe() {
        return doing;
    }
}
