// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.navigation.calc.ThreatField;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.world.EndermanGaze;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 打龙时的脚和手：按龙的打法（{@link DragonTactic}）走位、出手、射、举盾，并记着这一仗的战况——
 * 放弃了哪些水晶、射掉了几颗、在不在回血、躲开了几次、龙倒没倒下——交给任务写结果。
 *
 * <p>躲龙息、躲冲撞、躲火球都是先跑后算：当刻在八个方向里挑一步疾跑，八个方向都迈不出去才交给寻路。
 * 等龙落、回血、收经验先走到一圈落点上再站着，镜头看着龙；这一眼对上末影人的眼睛就压低。
 * 射水晶先走到爆炸范围外射得到的站位，站上去算不出弹道就换下一个，都不行就放弃并写明为什么。
 */
public final class DragonCombat {

    /** 回血时血这么多刻一点没涨，就算回不来。 */
    public static final int REST_STALL_TICKS = 1200;
    /** 走到落点这么近就算到了（格）。 */
    static final double ARRIVED = 1.5;
    /** 够得着龙头却这么多刻都没砍出去（准星被龙的别的部件挡着），横挪一步换角度。 */
    static final int SWING_STALL_TICKS = 30;
    /** 换角度时绕开刚才站位多远（格）、绕多少刻。 */
    static final double SIDESTEP = 1.2;
    static final int SIDESTEP_TICKS = 15;
    /** 射飞着的龙连着射不出去后，这么多刻里先不射。 */
    static final int BOW_REST_TICKS = 200;
    /** 目标跑开这么远才重新寻路（格）。 */
    static final double REPLAN_AFTER = 3.0;
    /** 还立着、没放弃的水晶在战况里写的原因。 */
    public static final String NOT_YET = "还没打掉";

    private final BattleMoves moves;
    private final RangedCombat bow = new RangedCombat();
    private Action walk;
    private String walkKey;
    private double[] walkAnchor;
    private boolean lastWalkFailed;
    private Action strike;

    private final Map<Integer, String> givenUp = new LinkedHashMap<>();
    private final Map<Integer, Long> endermanWaitSince = new HashMap<>();
    private final Map<Integer, DragonArena.Crystal> seenCrystals = new LinkedHashMap<>();
    private final Set<Integer> shotAt = new HashSet<>();
    private final List<String> unseenCrystals = new ArrayList<>();
    private int crystalsDestroyed;
    private int crystalsGoneOtherwise;
    private boolean healingUnseen;
    private double lastDragonHealth = -1;

    private boolean resting;
    private double restBest;
    private long restGainAt;
    private boolean restStalled;
    private int rests;
    private int breathDodges;
    private int sweepDodges;
    private int fireballDodges;

    private DragonTactic.Move lastMove;
    private DragonArena.Dragon lastDragon;
    private DragonArena.Portal portal;
    private boolean seenArena;
    private boolean dyingSeen;
    private long dyingAt = -1;
    private boolean goneAfterDying;
    private boolean goneWithoutDying;
    private long tick;
    private long lastEngagement = -1;

    private int crystalTarget = -1;
    private BlockPos stance;
    private final Set<BlockPos> triedStances = new HashSet<>();
    private BlockPos spot;
    private String spotKind;
    private final Set<BlockPos> badSpots = new HashSet<>();
    private int swingStall;
    private double[] sidestepFrom;
    private int sidestepTicks;
    private long bowRestUntil;

    public DragonCombat(BattleMoves moves) {
        this.moves = moves;
    }

    /**
     * 每刻先看一眼龙场记战况：龙倒没倒下、走没走；水晶少了哪颗（我射过的、区块还加载着才算我射掉的）；
     * 爆炸范围里的末影人从哪刻开始等；回血回到哪儿了；看不见水晶时龙在不在长血。
     */
    public void observe(TickContext context, Battlefield field) {
        tick = context.gameTick();
        if (lastEngagement < 0) lastEngagement = tick;
        DragonArena arena = field.arena();
        if (arena == null) {
            // 龙场没了：看到过死亡动画就是死后消失，没看到就是走远或卸载了，不算打死。
            if (seenArena && !goneAfterDying && !goneWithoutDying) {
                if (dyingSeen) goneAfterDying = true;
                else goneWithoutDying = true;
            }
            return;
        }
        seenArena = true;
        DragonArena.Dragon dragon = arena.dragon();
        lastDragon = dragon;
        portal = arena.portal();
        if (dragon.activity() == DragonArena.Activity.DYING && !dyingSeen) {
            dyingSeen = true;
            dyingAt = tick;
        }
        // 龙趴下了：这一仗在往前走，等它落地不算卡住。
        if (dragon.activity().perched()) lastEngagement = tick;
        noteCrystals(context, arena);
        double health = field.me().health();
        if (resting) {
            if (health >= Math.min(DragonTactic.RESUME_AT, arena.maxHealth())) {
                resting = false;
                spot = null;
            } else if (health > restBest + 1.0e-6) {
                restBest = health;
                restGainAt = tick;
            } else if (tick - restGainAt >= REST_STALL_TICKS) {
                restStalled = true;
            }
        }
        // 身边已经没有没放弃的水晶、也看不到光束，龙却在长血：多半还有看不见的水晶，写进结果。
        boolean openCrystal = arena.crystals().stream().anyMatch(crystal -> !givenUp.containsKey(crystal.id()));
        if (lastDragonHealth >= 0 && dragon.health() > lastDragonHealth + 0.25 && !openCrystal
                && dragon.healingCrystalId() < 0 && dragon.activity() != DragonArena.Activity.DYING) {
            healingUnseen = true;
        }
        lastDragonHealth = dragon.health();
    }

    // 水晶少了哪颗：区块没加载的是看不到了；我射过的是我射掉的；没射过的是别人打掉或被别的爆炸炸掉的（龙打不坏水晶）。
    private void noteCrystals(TickContext context, DragonArena arena) {
        Set<Integer> present = new HashSet<>();
        for (DragonArena.Crystal crystal : arena.crystals()) {
            present.add(crystal.id());
            seenCrystals.put(crystal.id(), crystal);
            if (crystal.endermanNear()) endermanWaitSince.putIfAbsent(crystal.id(), tick);
            else endermanWaitSince.remove(crystal.id());
        }
        for (Iterator<DragonArena.Crystal> it = seenCrystals.values().iterator(); it.hasNext(); ) {
            DragonArena.Crystal crystal = it.next();
            if (present.contains(crystal.id())) continue;
            it.remove();
            givenUp.remove(crystal.id());
            endermanWaitSince.remove(crystal.id());
            if (!moves.loaded(context, crystal.x(), crystal.z())) {
                unseenCrystals.add(where(crystal) + "：区块没加载，看不到了，可能还在");
            } else if (shotAt.contains(crystal.id())) {
                crystalsDestroyed++;
                lastEngagement = tick;
            } else {
                crystalsGoneOtherwise++;
            }
        }
    }

    /** 龙的打法要的这一仗记着的事。 */
    public DragonTactic.Memory memory(TickContext context) {
        return new DragonTactic.Memory(resting, givenUp, endermanWaitSince, moves.hasShield(context));
    }

    /** 按龙的打法做这一刻。 */
    public Battle.Moment tick(TickContext context, Battlefield field, DragonTactic.Move move) {
        if (lastMove != null && lastMove.getClass() != move.getClass()) {
            // 换了一件事：上一件的出手、寻路、拉了一半的弓都收尾。
            dropStrike();
            dropWalk();
            bow.close();
        }
        boolean episode = lastMove == null || lastMove.getClass() != move.getClass();
        lastMove = move;
        if (sidestepTicks > 0) sidestepTicks--;
        return switch (move) {
            case DragonTactic.AvoidCloud avoid -> avoidCloud(context, field, avoid, episode);
            case DragonTactic.DodgeSweep dodge -> dodgeSweep(context, field, dodge, episode);
            case DragonTactic.DodgeFireball dodge -> dodgeFireball(context, field, dodge, episode);
            case DragonTactic.Collect collect -> collect(context, field);
            case DragonTactic.Rest rest -> rest(context, field, rest.why());
            case DragonTactic.SkipCrystal skip -> skip(skip);
            case DragonTactic.ShootCrystal shoot -> shootCrystal(context, field, shoot.crystal());
            case DragonTactic.StrikeHead strikeHead -> strikeHead(context, field, strikeHead.backOff());
            case DragonTactic.ShootDragon shootDragon -> shootDragon(context, field);
            case DragonTactic.WaitForPerch wait -> waitForPerch(context, field, wait.why());
        };
    }

    // 躲龙息：当刻疾跑出所有危险区；八个方向都迈不出去才寻路绕出去。
    private Battle.Moment avoidCloud(TickContext context, Battlefield field, DragonTactic.AvoidCloud avoid,
                                     boolean episode) {
        dropStrike();
        bow.close();
        moves.lowerGuard();
        if (episode) breathDodges++;
        Battlefield.Me me = field.me();
        Footwork.Stance stance = new Footwork.Stance(null, cloudHazards(avoid.clouds()), true);
        Footwork.Step step = Footwork.choose(me.x(), me.z(), stance, moves.ground(context));
        if (step.standing() && Footwork.exposed(me.x(), me.z(), stance)) {
            boolean moved = follow(context, "躲龙息", me.x(), me.z(),
                    () -> GoalCompiler.awayFrom(cloudThreats(avoid.clouds(), me.y())));
            return moment(Battle.State.EVADING, "躲开龙息（寻路绕出去）", moved, false);
        }
        dropWalk();
        moves.stepAvertingGaze(context, -1, step);
        return moment(Battle.State.EVADING, "躲开龙息", !step.standing(), false);
    }

    // 躲冲撞：跑不开了、副手有盾就面朝它举盾；否则离开航线最近的那一处疾跑（正在航线上就往一侧让开）。
    private Battle.Moment dodgeSweep(TickContext context, Battlefield field, DragonTactic.DodgeSweep dodge,
                                     boolean episode) {
        dropStrike();
        bow.close();
        if (episode) sweepDodges++;
        DragonArena.Dragon dragon = field.arena().dragon();
        if (dodge.brace()) {
            dropWalk();
            moves.guard(context, 0, false);
            moves.stepAvertingGaze(context, dragon.id(), Footwork.Step.STAND);
            return moment(Battle.State.EVADING, "龙冲过来了，跑不开，面朝它举盾", false, false);
        }
        moves.lowerGuard();
        Battlefield.Me me = field.me();
        double hx = dodge.pass().x();
        double hz = dodge.pass().z();
        double speed = Math.hypot(dragon.vx(), dragon.vz());
        if (dodge.pass().miss() < 0.5 && speed > 1.0e-6) {
            // 正站在航线上：把要躲的中心往航线一侧挪一格，脚就往另一侧让开，不顺着航线跑。
            hx -= -dragon.vz() / speed;
            hz -= dragon.vx() / speed;
        }
        List<Footwork.Hazard> hazards = new ArrayList<>(cloudHazards(DragonGeometry.dangerClouds(field.arena())));
        hazards.add(new Footwork.Hazard(hx, hz, DragonGeometry.SWEEP_WIDTH));
        Footwork.Step step = Footwork.choose(me.x(), me.z(), new Footwork.Stance(null, hazards, true),
                moves.ground(context));
        dropWalk();
        moves.stepAvertingGaze(context, -1, step);
        return moment(Battle.State.EVADING, "龙冲过来了，横着跑开它的航线", !step.standing(), false);
    }

    // 躲火球：离开它落点附近疾跑；落地后的云下一刻按躲龙息处理。
    private Battle.Moment dodgeFireball(TickContext context, Battlefield field, DragonTactic.DodgeFireball dodge,
                                        boolean episode) {
        dropStrike();
        bow.close();
        moves.lowerGuard();
        if (episode) fireballDodges++;
        Battlefield.Me me = field.me();
        List<Footwork.Hazard> hazards = new ArrayList<>(cloudHazards(DragonGeometry.dangerClouds(field.arena())));
        hazards.add(new Footwork.Hazard(dodge.pass().x(), dodge.pass().z(), DragonGeometry.FIREBALL_MISS + 1.0));
        Footwork.Step step = Footwork.choose(me.x(), me.z(), new Footwork.Stance(null, hazards, true),
                moves.ground(context));
        dropWalk();
        moves.stepAvertingGaze(context, -1, step);
        return moment(Battle.State.EVADING, "龙火球朝我飞来，横着跑开", !step.standing(), false);
    }

    // 龙倒下了：停手，走到出口门边（门洞外）站着，经验球会自己飞过来。
    private Battle.Moment collect(TickContext context, Battlefield field) {
        dropStrike();
        bow.close();
        moves.lowerGuard();
        DragonArena arena = field.arena();
        Battlefield.Me me = field.me();
        List<DragonArena.Cloud> clouds = DragonGeometry.dangerClouds(arena);
        return goSpot(context, field, "收经验", () -> DragonGeometry.collectSpots(arena.portal(), clouds,
                moves.surface(context), me.x(), me.z()), "龙倒下了，到出口门边收经验");
    }

    // 退开回血：走到离出口门 20～30 格的一圈上站着，吃饭这项生存需求趁这个空当插进来；躲闪照样做。
    private Battle.Moment rest(TickContext context, Battlefield field, String why) {
        if (!resting) {
            resting = true;
            rests++;
            restBest = field.me().health();
            restGainAt = tick;
            restStalled = false;
        }
        dropStrike();
        bow.close();
        moves.lowerGuard();
        DragonArena arena = field.arena();
        Battlefield.Me me = field.me();
        List<DragonArena.Crystal> standing = new ArrayList<>();
        for (DragonArena.Crystal crystal : arena.crystals()) {
            if (!givenUp.containsKey(crystal.id())) standing.add(crystal);
        }
        List<DragonArena.Cloud> clouds = DragonGeometry.dangerClouds(arena);
        return goSpot(context, field, "回血", () -> DragonGeometry.restSpots(arena.portal(), standing, clouds,
                moves.surface(context), me.x(), me.z()), why);
    }

    private Battle.Moment skip(DragonTactic.SkipCrystal skip) {
        givenUp.put(skip.crystal().id(), skip.why());
        return moment(Battle.State.HOLDING, "放弃一颗水晶：" + skip.why(), false, false);
    }

    // 射水晶：站在爆炸范围外、射得到的地方就射；不在就去一圈站位里最近的那个，站上去还射不到就换下一个，都不行就放弃。
    private Battle.Moment shootCrystal(TickContext context, Battlefield field, DragonArena.Crystal crystal) {
        dropStrike();
        moves.lowerGuard();
        if (crystal.id() != crystalTarget) {
            crystalTarget = crystal.id();
            triedStances.clear();
            stance = null;
            bow.close();
        }
        Battlefield.Me me = field.me();
        List<DragonArena.Cloud> clouds = DragonGeometry.dangerClouds(field.arena());
        double eye = DragonGeometry.distance(me.x(), me.y() + DragonGeometry.EYE_HEIGHT, me.z(),
                crystal.x(), crystal.y() + 1.0, crystal.z());
        boolean clear = eye >= DragonGeometry.CRYSTAL_CLEARANCE && eye <= DragonGeometry.CRYSTAL_SHOT_RANGE
                && !DragonGeometry.inAnyCloud(clouds, me.x(), me.y(), me.z());
        boolean atStance = stance != null && Math.hypot(me.x() - stance.getX() - 0.5, me.z() - stance.getZ() - 0.5) <= ARRIVED;
        if (clear && (bow.shooting() || moves.arcTo(context, crystal.id(), DragonGeometry.CRYSTAL_SHOT_RANGE))) {
            if (provokes(field, crystal.x(), crystal.y() + 1.0, crystal.z())) {
                bow.close();
                return sidestep(context, field, clouds, "射水晶的视线上站着末影人，挪一步换个角度");
            }
            dropWalk();
            boolean fired = bow.tick(context, moves, crystal.id(), DragonGeometry.CRYSTAL_SHOT_RANGE);
            if (fired) {
                shotAt.add(crystal.id());
                lastEngagement = tick;
            }
            if (bow.givenUp()) {
                // 连着射不出去：这个位置不行，换下一个站位。
                bow.reset();
                if (stance != null) triedStances.add(stance);
                triedStances.add(BlockPos.containing(me.x(), me.y(), me.z()));
                stance = null;
            }
            if (!bow.steady()) moves.stepAvertingGaze(context, crystal.id(), Footwork.Step.STAND);
            return moment(Battle.State.SHOOTING, "射末影水晶", true, false, fired);
        }
        bow.close();
        if (atStance) {
            // 站上去了还射不到（铁栏笼、别的方块挡着）：记下，换下一个站位。
            triedStances.add(stance);
            stance = null;
        }
        if (stance == null) {
            List<BlockPos> candidates = new ArrayList<>(DragonGeometry.crystalStances(crystal, clouds,
                    moves.surface(context), me.x(), me.z()));
            candidates.removeAll(triedStances);
            if (candidates.isEmpty()) {
                String why = crystal.caged() ? "铁栏笼挡着，射不进去" : "附近找不到站得住又射得到它的地方";
                givenUp.put(crystal.id(), why);
                crystalTarget = -1;
                dropWalk();
                return moment(Battle.State.HOLDING, "放弃这颗水晶：" + why, false, false);
            }
            stance = candidates.get(0);
            dropWalk();
        }
        BlockPos target = stance;
        boolean moved = follow(context, "去射水晶的站位 " + target.toShortString(), target.getX() + 0.5,
                target.getZ() + 0.5, () -> GoalCompiler.approachAvoiding(GoalCompiler.near(target, 1.0),
                        cloudThreats(clouds, me.y())));
        if (lastWalkFailed) {
            triedStances.add(target);
            stance = null;
        }
        return moment(Battle.State.FIGHTING, "走到能射末影水晶的地方", moved, false);
    }

    // 砍龙头：够得着、不碰龙头龙脖子、不在龙息里、视线不对上末影人就砍；脚守在够得着龙头的带子里，绕开撞人区与龙息。
    // 龙要喷时预先算出的那一口也算龙息，脚就退到云外离龙头最近的地方。
    private Battle.Moment strikeHead(TickContext context, Battlefield field, boolean backOff) {
        bow.close();
        DragonArena.Dragon dragon = field.arena().dragon();
        Battlefield.Me me = field.me();
        AABB head = dragon.head();
        Vec3 headCenter = head.getCenter();
        Vec3 neckCenter = dragon.neck().getCenter();
        List<DragonArena.Cloud> clouds = DragonGeometry.dangerClouds(field.arena());
        boolean inReach = DragonGeometry.reachesHead(me.x(), me.y(), me.z(), head, me.reach());
        boolean safeHere = !DragonGeometry.inStrikeZone(me.x(), me.y(), me.z(), head, dragon.neck())
                && !DragonGeometry.inAnyCloud(clouds, me.x(), me.y(), me.z());
        boolean gazeClear = !provokes(field, headCenter.x, headCenter.y, headCenter.z);
        boolean swinging = inReach && safeHere && gazeClear;
        boolean struck = false;
        if (swinging) {
            struck = swing(context, dragon.id(), me);
            swingStall = struck ? 0 : swingStall + 1;
            if (struck) lastEngagement = tick;
            if (swingStall > SWING_STALL_TICKS) {
                // 准星一直被龙的身子、翅膀挡着：换个角度。
                dropStrike();
                swingStall = 0;
                markSidestep(me);
            }
        } else {
            dropStrike();
            swingStall = 0;
            if (inReach && safeHere) markSidestep(me);
        }
        List<Footwork.Hazard> hazards = new ArrayList<>(cloudHazards(clouds));
        hazards.add(new Footwork.Hazard(headCenter.x, headCenter.z, DragonGeometry.strikeZoneReach(head)));
        hazards.add(new Footwork.Hazard(neckCenter.x, neckCenter.z, DragonGeometry.strikeZoneReach(dragon.neck())));
        if (sidestepTicks > 0 && sidestepFrom != null) {
            hazards.add(new Footwork.Hazard(sidestepFrom[0], sidestepFrom[1], SIDESTEP));
        }
        double outer = CombatGeometry.meleeStandOff(me.reach(), head.getXsize() / 2);
        boolean near = Math.hypot(me.x() - headCenter.x, me.z() - headCenter.z) <= outer + Battle.NEAR_MARGIN
                && Math.abs(head.minY - me.y()) <= Battle.NEAR_MARGIN;
        if (near) {
            dropWalk();
            Footwork.Step step = Footwork.choose(me.x(), me.z(),
                    new Footwork.Stance(new Footwork.Band(headCenter.x, headCenter.z, 0, outer), hazards, false),
                    moves.ground(context));
            if (swinging) moves.stepKeepingAim(context, step);
            else moves.stepAvertingGaze(context, dragon.id(), step);
        } else {
            // 远处：寻路到龙头边一个够得着、不挨撞、不在龙息里的站位；一个都没有（龙头在高处）就走到离龙头最近的带子里。
            List<ThreatField.Threat> threats = new ArrayList<>(cloudThreats(clouds, me.y()));
            double headZone = DragonGeometry.strikeZoneReach(head);
            double neckZone = DragonGeometry.strikeZoneReach(dragon.neck());
            threats.add(new ThreatField.Threat(headCenter.x, headCenter.y, headCenter.z, headZone, headZone));
            threats.add(new ThreatField.Threat(neckCenter.x, neckCenter.y, neckCenter.z, neckZone, neckZone));
            follow(context, "走到龙头边", headCenter.x, headCenter.z, () -> {
                List<BlockPos> stances = DragonGeometry.headStances(dragon, field.arena().portal(), clouds, me.reach(),
                        moves.surface(context), me.x(), me.z());
                GoalCompiler.Compiled approach = stances.isEmpty() ? GoalCompiler.band(headCenter, 0, outer)
                        : GoalCompiler.near(stances.get(0), 0.8);
                return GoalCompiler.approachAvoiding(approach, threats);
            });
        }
        String doing = backOff
                ? swinging ? "龙在喷龙息，站在云外砍龙头" : "龙要喷龙息了，退出龙头前面"
                : swinging ? "砍龙头" : "走到够得着龙头的地方";
        // 龙趴着时守在它头边就是在打：站着等它转头、等这一口喷完都算有动静。
        return moment(Battle.State.FIGHTING, doing, true, struck);
    }

    // 射飞着的龙：推进手上这一箭，脚避开龙息；连着射不出去就先不射，等它落下来。
    private Battle.Moment shootDragon(TickContext context, Battlefield field) {
        dropStrike();
        moves.lowerGuard();
        if (bow.givenUp()) {
            bow.reset();
            bowRestUntil = tick + BOW_REST_TICKS;
        }
        if (tick < bowRestUntil) return waitForPerch(context, field, "箭连着射不出去，先等龙落下来");
        DragonArena.Dragon dragon = field.arena().dragon();
        if (provokes(field, dragon.x(), dragon.y() + 2.0, dragon.z())) {
            bow.close();
            return sidestep(context, field, DragonGeometry.dangerClouds(field.arena()), "射龙的视线上站着末影人，挪一步换个角度");
        }
        dropWalk();
        boolean fired = bow.tick(context, moves, dragon.id());
        if (fired) lastEngagement = tick;
        if (!bow.steady()) {
            Battlefield.Me me = field.me();
            Footwork.Step step = Footwork.choose(me.x(), me.z(), new Footwork.Stance(null,
                    cloudHazards(DragonGeometry.dangerClouds(field.arena())), false), moves.ground(context));
            moves.stepAvertingGaze(context, dragon.id(), step);
        }
        return moment(Battle.State.SHOOTING, "射飞着的龙", true, false, fired);
    }

    // 等龙落：走到出口门旁 8～14 格的一圈上站着，看着龙。
    private Battle.Moment waitForPerch(TickContext context, Battlefield field, String why) {
        dropStrike();
        bow.close();
        moves.lowerGuard();
        DragonArena arena = field.arena();
        Battlefield.Me me = field.me();
        List<DragonArena.Cloud> clouds = DragonGeometry.dangerClouds(arena);
        return goSpot(context, field, "等龙落", () -> DragonGeometry.waitSpots(arena.portal(), clouds,
                moves.surface(context), me.x(), me.z()), why);
    }

    // 走到一圈落点里的一个再站着、看着龙：落点进了龙息、走不过去就换一个；附近找不到就原地站着。
    // 等龙落、回血、收经验本来就是在等，站着也算有动静。
    private Battle.Moment goSpot(TickContext context, Battlefield field, String kind, Supplier<List<BlockPos>> candidates,
                                 String doing) {
        DragonArena arena = field.arena();
        Battlefield.Me me = field.me();
        List<DragonArena.Cloud> clouds = DragonGeometry.dangerClouds(arena);
        if (!kind.equals(spotKind) || spot == null
                || DragonGeometry.inAnyCloud(clouds, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5)) {
            spotKind = kind;
            spot = null;
            dropWalk();
            for (BlockPos candidate : candidates.get()) {
                if (!badSpots.contains(candidate)) {
                    spot = candidate;
                    break;
                }
            }
        }
        int dragonId = arena.dragon().id();
        if (spot == null) {
            moves.stepAvertingGaze(context, dragonId, Footwork.Step.STAND);
            return moment(Battle.State.HOLDING, doing + "（附近找不到合适的落点，原地等）", true, false);
        }
        BlockPos target = spot;
        if (Math.hypot(me.x() - target.getX() - 0.5, me.z() - target.getZ() - 0.5) > ARRIVED) {
            follow(context, kind + " " + target.toShortString(), target.getX() + 0.5, target.getZ() + 0.5,
                    () -> GoalCompiler.approachAvoiding(GoalCompiler.near(target, 1.0), cloudThreats(clouds, me.y())));
            if (lastWalkFailed) {
                badSpots.add(target);
                spot = null;
            }
            return moment(Battle.State.HOLDING, doing, true, false);
        }
        dropWalk();
        moves.stepAvertingGaze(context, dragonId, Footwork.Step.STAND);
        return moment(Battle.State.HOLDING, doing, true, false);
    }

    // 横挪一步换个角度：绕开刚才站的地方，也绕开龙息。
    private Battle.Moment sidestep(TickContext context, Battlefield field, List<DragonArena.Cloud> clouds, String doing) {
        Battlefield.Me me = field.me();
        if (sidestepTicks == 0) markSidestep(me);
        List<Footwork.Hazard> hazards = new ArrayList<>(cloudHazards(clouds));
        hazards.add(new Footwork.Hazard(sidestepFrom[0], sidestepFrom[1], SIDESTEP));
        Footwork.Step step = Footwork.choose(me.x(), me.z(), new Footwork.Stance(null, hazards, false),
                moves.ground(context));
        dropWalk();
        moves.stepAvertingGaze(context, -1, step);
        return moment(Battle.State.FIGHTING, doing, !step.standing(), false);
    }

    private void markSidestep(Battlefield.Me me) {
        sidestepFrom = new double[] {me.x(), me.z()};
        sidestepTicks = SIDESTEP_TICKS;
    }

    // 这一眼（从眼睛看向这一点）会不会对上没惹毛的末影人的眼睛；戴着雕刻南瓜不会。
    private static boolean provokes(Battlefield field, double x, double y, double z) {
        DragonArena arena = field.arena();
        Battlefield.Me me = field.me();
        Vec3 eye = new Vec3(me.x(), me.y() + DragonGeometry.EYE_HEIGHT, me.z());
        List<EndermanGaze.Eyes> eyes = new ArrayList<>();
        for (DragonArena.Watcher watcher : arena.endermen()) {
            if (!watcher.angry()) eyes.add(new EndermanGaze.Eyes(watcher.eyeX(), watcher.eyeY(), watcher.eyeZ(),
                    watcher.inSight()));
        }
        return EndermanGaze.provokesAny(eye, new Vec3(x - eye.x, y - eye.y, z - eye.z), eyes, arena.pumpkin());
    }

    // 砍一下龙头：同一下沿用进行中的出手；砍中了清掉等下一下；被拒就换个角度。
    private boolean swing(TickContext context, int dragonId, Battlefield.Me me) {
        if (strike == null) strike = moves.strikeHead(context, dragonId);
        ActionStatus status;
        try {
            status = strike.tick(context);
        } catch (IllegalStateException busy) {
            // 上一下举盾还在等确认，交互入口这一刻不收这一刀：下一刻再挥。
            return false;
        }
        if (status instanceof ActionStatus.Done) {
            dropStrike();
            return true;
        }
        if (status instanceof ActionStatus.Failed) {
            dropStrike();
            markSidestep(me);
        }
        return false;
    }

    // 跟着一段寻路走：同一件事、目标没跑开就沿用，跑开三格以上或换了事才重新算路；走不通记下来。
    private boolean follow(TickContext context, String key, double anchorX, double anchorZ,
                           Supplier<GoalCompiler.Compiled> goal) {
        lastWalkFailed = false;
        if (walk != null && (!key.equals(walkKey)
                || Math.hypot(anchorX - walkAnchor[0], anchorZ - walkAnchor[1]) > REPLAN_AFTER)) {
            dropWalk();
        }
        if (walk == null) {
            walk = moves.walk(goal.get());
            walkKey = key;
            walkAnchor = new double[] {anchorX, anchorZ};
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

    // 走位要躲的龙息：每团云的半径、涨幅与余量。
    private static List<Footwork.Hazard> cloudHazards(List<DragonArena.Cloud> clouds) {
        List<Footwork.Hazard> hazards = new ArrayList<>();
        for (DragonArena.Cloud cloud : clouds) {
            hazards.add(new Footwork.Hazard(cloud.x(), cloud.z(), DragonGeometry.cloudReach(cloud)));
        }
        return hazards;
    }

    // 交给寻路的龙息：按格子走再加半个格子对角线；不知道落在哪一层的按我这一层算。
    private static List<ThreatField.Threat> cloudThreats(List<DragonArena.Cloud> clouds, double y) {
        List<ThreatField.Threat> threats = new ArrayList<>();
        for (DragonArena.Cloud cloud : clouds) {
            double reach = DragonGeometry.cloudReach(cloud);
            threats.add(new ThreatField.Threat(cloud.x(), Double.isNaN(cloud.y()) ? y : cloud.y(), cloud.z(),
                    reach + Battle.CELL_SLACK, reach));
        }
        return threats;
    }

    private static Battle.Moment moment(Battle.State state, String doing, boolean progressed, boolean struck) {
        return moment(state, doing, progressed, struck, false);
    }

    // 打龙这边的一刻：对着的东西不写编号（龙与水晶的结算由任务看战况），不撤离、不背水一战。
    private static Battle.Moment moment(Battle.State state, String doing, boolean progressed, boolean struck,
                                        boolean fired) {
        return new Battle.Moment(state, doing, progressed, struck, fired, -1, null, false);
    }

    private void dropStrike() {
        if (strike != null) strike.close();
        strike = null;
    }

    private void dropWalk() {
        if (walk != null) walk.close();
        walk = null;
        walkKey = null;
    }

    /** 普通打法接手了（先对付贴上来的别的怪）：打龙这边的出手、寻路、拉了一半的弓都收尾。 */
    public void stand() {
        dropStrike();
        dropWalk();
        bow.close();
        lastMove = null;
    }

    /** 不打了：都收尾，盾放下。 */
    public void close() {
        stand();
        moves.lowerGuard();
    }

    /** 暂停：寻路与出手停在原处，弓取消。 */
    public void pause() {
        if (strike != null) strike.pause();
        if (walk != null) walk.pause();
        bow.close();
    }

    /**
     * 此刻能不能被打断：躲闪中停下不安全；回血时站到了落点上是两个动作之间（吃饭趁这个空当插进来）；
     * 其余按手上的寻路、出手。
     */
    public Interruptibility interruptibility() {
        if (lastMove instanceof DragonTactic.AvoidCloud || lastMove instanceof DragonTactic.DodgeSweep
                || lastMove instanceof DragonTactic.DodgeFireball) {
            return Interruptibility.UNSAFE_TO_STOP;
        }
        if (walk != null) return walk.interruptibility();
        if (strike != null) return strike.interruptibility();
        if (lastMove instanceof DragonTactic.Rest) return Interruptibility.BETWEEN_ACTIONS;
        return Interruptibility.WORKING;
    }

    /**
     * 这一仗的战况。
     *
     * @param crystalsDestroyed     确认是我射掉的水晶数
     * @param crystalsLeft          没打掉的水晶：在哪、为什么（还没打、放弃了、看不到了），以及看不见水晶龙却在回血
     * @param crystalsGoneOtherwise 不是我射掉却没了的水晶数（别人打掉、被别的爆炸炸掉）
     * @param standingCrystals      还立着、没放弃的水晶数（缺弓箭时要说）
     * @param dragon                最后看到的龙；从没看到为 null
     * @param dyingSeen             看到了死亡动画
     * @param dyingAt               看到死亡动画的那一刻；没看到为 -1
     * @param goneAfterDying        死亡动画之后龙消失了
     * @param goneWithoutDying      没看到死亡动画龙就不在了（走远或卸载）
     * @param portal                出口门；没看到为 null
     * @param restStalled           回血时血一直不涨
     * @param lastEngagement        最近一次真在打的那一刻（射出、砍中、水晶没了、龙趴着）
     */
    public record Report(int crystalsDestroyed, List<String> crystalsLeft, int crystalsGoneOtherwise,
                         int standingCrystals, DragonArena.Dragon dragon, boolean dyingSeen, long dyingAt,
                         boolean goneAfterDying, boolean goneWithoutDying, DragonArena.Portal portal, int rests,
                         int breathDodges, int sweepDodges, int fireballDodges, boolean restStalled,
                         long lastEngagement) {
        public Report {
            crystalsLeft = List.copyOf(crystalsLeft);
        }
    }

    /** 这一仗到此刻的战况。 */
    public Report report() {
        List<String> left = new ArrayList<>();
        int standing = 0;
        for (DragonArena.Crystal crystal : seenCrystals.values()) {
            String reason = givenUp.get(crystal.id());
            if (reason == null) standing++;
            left.add(where(crystal) + "：" + (reason == null ? NOT_YET : reason));
        }
        left.addAll(unseenCrystals);
        if (healingUnseen) left.add("身边看不到水晶时龙还在长血：附近可能还有看不见的水晶");
        return new Report(crystalsDestroyed, left, crystalsGoneOtherwise, standing, lastDragon, dyingSeen, dyingAt,
                goneAfterDying, goneWithoutDying, portal, rests, breathDodges, sweepDodges, fireballDodges,
                restStalled, lastEngagement);
    }

    private static String where(DragonArena.Crystal crystal) {
        return "水晶（" + Math.round(Math.floor(crystal.x())) + ", " + Math.round(Math.floor(crystal.y())) + ", "
                + Math.round(Math.floor(crystal.z())) + "）";
    }
}
