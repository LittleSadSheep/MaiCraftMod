// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.combat.Battle;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.child.ChildTaskRunner;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.interrupt.KeepsCompany;
import org.maiwithu.maicraft.kernel.interrupt.ThreatResponder;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.Standing;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 跟随任务：像一个陪着走的玩家那样跟着一个人。常驻，不自己完成。
 *
 * <p>跟：以他脚下站得住的那一格为准，离它超过 distance+2 起步、走进 distance 就停；他在走时换目标点、路线不停下重算；
 * 远了跑、快到了走。停着：看着他，挡了他的道就让到一边。跟不过去：停下看着他等，他挪开再试，2 分钟都没跟上才结束。
 * 跟丢了：分清是过门、死了、下线还是走远了，过门就跟进去，走远就去最后看见他的地方找，30 秒找不回来才结束。
 * 他坐上有空座的载具就坐同一辆，他下来我也下来；他在跟敌对生物打就上去帮（许可只给自卫时不帮）。
 * 这些处境变化都发 follow_status 事件，角色不停下等回答。
 */
final class FollowTask extends PhasedTask<FollowTask.Phase> implements Standing, KeepsCompany, ThreatResponder {

    /** 跟随的阶段。 */
    enum Phase {
        /** 锁定要跟的人。 */
        LOCK,
        /** 跟着：保持距离、停着看他、挡道就让。 */
        KEEP,
        /** 跟不过去：停下看着他等，他挪开再试。 */
        BLOCKED,
        /** 跟丢了：去最后看见他的地方找，或原地等他回来。 */
        SEARCH,
        /** 跟着过传送门。 */
        PORTAL,
        /** 走过去坐上他那辆。 */
        BOARD,
        /** 和他坐同一辆。 */
        RIDE,
        /** 从载具上下来。 */
        GET_OFF,
        /** 帮他打敌对生物。 */
        HELP
    }

    /** 起步要超出设定距离多少格；迟滞防抖，避免在临界距离上一进一出地走停。 */
    static final double HYSTERESIS = 2.0;
    /** 他挪出原目标点这么远就换目标点（格）。 */
    static final double RETARGET_SLACK = 3.0;
    /** 离他脚下超过 distance 再加这么多才疾跑，近了只走，不冲过头撞人。 */
    static final double SPRINT_MARGIN = 4.0;
    /** 连续这么久没跟上就不等了（刻，2 分钟）。 */
    static final long BLOCKED_LIMIT_TICKS = 20L * 120;
    /** 跟不过去时，至少隔这么久才再试一次（刻）。 */
    static final long RETRY_GAP_TICKS = 20L * 3;
    /** 跟丢了找这么久（刻，30 秒）。 */
    static final long SEARCH_LIMIT_TICKS = 20L * 30;
    /** 让过一次路，这么久内不再让（刻）：免得两个人在门口来回躲。 */
    static final long YIELD_COOLDOWN_TICKS = 40;
    /** 一辆车没坐上，这么久内不再去坐它（刻）。 */
    static final long BOARD_RETRY_TICKS = 20L * 10;
    /** 帮打撤出来以后，这么久内不再上去（刻）。 */
    static final long HELP_COOLDOWN_TICKS = 20L * 5;
    /** 帮打一下都没打着就结束（怪够不着）以后，这么久内不再上去（刻）。 */
    static final long HELP_IDLE_COOLDOWN_TICKS = 20L * 30;
    /** 同一辆车没坐上几次就不再去坐它。 */
    static final int BOARD_TRIES = 2;
    /** 兜底：这么久既没跟上、也没在走近，算卡住（刻，3 分钟）；跟不过去与找人各有更短的时限。 */
    private static final long STUCK_AFTER_TICKS = 20L * 60 * 3;

    /** 跟丢的原因，决定怎么找和最后怎么说。 */
    enum Lost {
        /** 走出视野、传送走了。 */
        OUT_OF_SIGHT("走远了，没找到"),
        /** 他死了。 */
        DIED("他死了，没回来"),
        /** 他下线了。 */
        OFFLINE("他下线了，没回来"),
        /** 跟着过了传送门，那边没看见他。 */
        AFTER_PORTAL("跟着过了传送门，那边没找到他"),
        /** 他进了跟不过去的门。 */
        PORTAL_FAILED("他进了传送门，没跟过去");

        final String ending;

        Lost(String ending) {
            this.ending = ending;
        }
    }

    private final FollowInput input;
    private final FollowView view;
    private final FollowMoves moves;
    private final WalkTo walks;
    private final TaskEventSink events;
    private final TerrainPermit permit;

    private FollowView.Locked target;
    private FollowView.Sighting last;
    private long lastSeenTick;
    private String lastDirection;
    private long now;

    /** 他往哪走：最近几刻挪动的平滑值（格/刻）。 */
    private double velocityX;
    private double velocityZ;
    private Vec3 previousFeet;

    private WalkRun walk;
    /** 这一趟走的目标点；他挪远过它才换。 */
    private BlockPos walkAnchor;
    /** 这一趟是让路，不是跟。 */
    private boolean yielding;
    private long lastYieldTick = Long.MIN_VALUE / 2;

    /** 跟不过去从哪一刻起算；真跟上了才清零，两次跟不过去之间短暂走几步不算跟上。 */
    private long blockedSince = -1;
    private Problem blockedBecause;
    private BlockPos blockedAnchor;
    private long lastTryTick = Long.MIN_VALUE / 2;

    private Lost lost;
    private long lostSince;
    private Vec3 lookOut;

    private ChildTaskRunner portalRun;
    private String portalTarget;

    private Action boarding;
    private Integer boardingVehicle;
    private final Map<Integer, Long> refusedVehicles = new HashMap<>();
    private final Map<Integer, Integer> boardFailures = new HashMap<>();
    private Integer toldNoSeat;
    private Action gettingOff;

    private FollowMoves.HelpFight help;
    private long helpEndedTick = Long.MIN_VALUE / 2;
    /** 上一场帮打之后要歇多久才再上（刻）：撤出来了、或一下都没打着时歇一阵，免得来回冲。 */
    private long helpCooldown;
    /** 这一场帮打真打着了、或真挪了步。 */
    private boolean helpLanded;

    FollowTask(FollowInput input, FollowView view, FollowMoves moves, WalkTo walks, TaskEventSink events) {
        super("跟随", Phase.LOCK, new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.input = Objects.requireNonNull(input, "input");
        this.view = Objects.requireNonNull(view, "view");
        this.moves = Objects.requireNonNull(moves, "moves");
        this.walks = Objects.requireNonNull(walks, "walks");
        this.events = Objects.requireNonNull(events, "events");
        // 跟随的走到许可直接沿用任务许可的档位：只垫不挖时跟人开路也只垫不拆；落地办法随动土一起放开。
        this.permit = TerrainPermit.of(input.permissions());
    }

    /** 手上正在做的那一样（走路、上车、下车、帮打、过门）接进基类的暂停与收尾：基类持有的是这个外壳。 */
    private final class Driver implements Action {
        @Override
        public ActionStatus tick(TickContext context) {
            return ActionStatus.running();
        }

        // 被生存需求打断：走路松键撤路线、上下车松手、帮打收手、过门的那一段跟着暂停；恢复后按当时的处境重新起步。
        @Override
        public void pause() {
            if (walk != null) walk.pause();
            if (boarding != null) boarding.pause();
            if (gettingOff != null) gettingOff.pause();
            if (help != null) help.pause();
            if (portalRun != null && !portalRun.finished()) portalRun.pause();
        }

        // 换阶段时基类也会叫收尾：手上的路、车、仗要带进下一阶段接着做，不收。
        // 真正结束（完成、失败、被取消、被替换）才把每一样都结清，不留一趟没人推进的路、一场没人管的仗悬着。
        @Override
        public void close() {
            if (changingPhase) {
                changingPhase = false;
                return;
            }
            closeWalk();
            if (boarding != null) {
                boarding.close();
                boarding = null;
            }
            if (gettingOff != null) {
                gettingOff.close();
                gettingOff = null;
            }
            endHelp();
            if (portalRun != null && !portalRun.finished()) {
                portalRun.close(CloseReason.CANCELLED);
            }
        }

        @Override
        public String describe() {
            if (portalRun != null && !portalRun.finished()) return portalRun.describe();
            if (help != null) return "帮他打：" + help.describe();
            if (boarding != null) return boarding.describe();
            if (gettingOff != null) return gettingOff.describe();
            if (walk != null) return yielding ? "让路：" + walk.describe() : walk.describe();
            return "看着他";
        }
    }

    private final Driver driver = new Driver();
    /** 这一刻的走向是换阶段：基类紧接着叫的收尾只是换阶段，不是任务结束。 */
    private boolean changingPhase;

    @Override protected Action enter(Phase phase) { return driver; }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        now = context.gameTick();
        Next<Phase> next = switch (phase) {
            case LOCK -> lock(context);
            case KEEP -> keep(context);
            case BLOCKED -> blocked(context);
            case SEARCH -> search(context);
            case PORTAL -> portal(context);
            case BOARD -> board(context);
            case RIDE -> ride(context);
            case GET_OFF -> getOff(context);
            case HELP -> help(context);
        };
        changingPhase = next instanceof Next.Go;
        return next;
    }

    private Next<Phase> lock(TickContext context) {
        FollowView.Locked locked = view.lock(context, input.target());
        if (locked == null) {
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                    "找不到要跟的目标：它现在不在视野或观察登记里，先 observe 确认它还在",
                    "重新观察后用新的观察编号再跟"));
        }
        target = locked;
        lastDirection = locked.direction();
        return Next.go(Phase.KEEP, "锁定了目标 " + locked.name());
    }

    // 看他一眼：记下最后看见的样子与他往哪走（平滑掉一刻一刻的抖动；一下子挪很远是传送或上下车，不算走）。
    private FollowView.Sighting see(TickContext context) {
        FollowView.Sighting him = view.observe(context);
        if (him == null) return null;
        if (previousFeet != null) {
            double dx = him.feet().x - previousFeet.x;
            double dz = him.feet().z - previousFeet.z;
            if (Math.hypot(dx, dz) > 8.0) {
                velocityX = 0;
                velocityZ = 0;
            } else {
                velocityX = velocityX * 0.5 + dx * 0.5;
                velocityZ = velocityZ * 0.5 + dz * 0.5;
            }
        }
        previousFeet = him.feet();
        last = him;
        lastSeenTick = now;
        lastDirection = him.direction();
        return him;
    }

    // 跟着：先看他在不在、坐没坐车、在不在打架，再按距离走或停。
    private Next<Phase> keep(TickContext context) {
        FollowView.Sighting him = see(context);
        if (him == null || him.dead()) {
            return lose(context, him);
        }
        Next<Phase> turn = turnFor(context, him);
        if (turn != null) return turn;
        return keepPace(context, him);
    }

    // 他坐车、我坐着别的、他在打架：这几样先于走路处理。
    private Next<Phase> turnFor(TickContext context, FollowView.Sighting him) {
        if (him.sameVehicle()) {
            closeWalk();
            return Next.go(Phase.RIDE, "和他坐在同一辆上");
        }
        if (him.meRiding()) {
            closeWalk();
            return Next.go(Phase.GET_OFF, "我坐在别的东西上，先下来再跟");
        }
        if (him.vehicleId() != null) {
            Next<Phase> boardTurn = tryBoard(context, him);
            if (boardTurn != null) return boardTurn;
        }
        if (him.inFight() && mayHelp() && now - helpEndedTick >= helpCooldown) {
            closeWalk();
            return Next.go(Phase.HELP, "他在跟敌对生物打，上去帮他");
        }
        return null;
    }

    // 他坐上了车：同一辆上有空座就去坐；没空座、刚没坐上的，走路跟并说一声。
    private Next<Phase> tryBoard(TickContext context, FollowView.Sighting him) {
        int vehicle = him.vehicleId();
        Long refused = refusedVehicles.get(vehicle);
        if (refused != null && now - refused < BOARD_RETRY_TICKS) return null;
        // 同一辆连着没坐上两次：不再去坐，走路跟。
        if (boardFailures.getOrDefault(vehicle, 0) >= BOARD_TRIES) return null;
        Optional<Action> board = moves.rideAlong(context, vehicle, input.permissions());
        if (board.isPresent()) {
            closeWalk();
            boarding = board.get();
            boardingVehicle = vehicle;
            tell("他坐上了 " + him.vehicleType() + "，上面还有空座，走过去坐上");
            return Next.go(Phase.BOARD, "走过去坐上他那辆");
        }
        if (!Integer.valueOf(vehicle).equals(toldNoSeat)) {
            toldNoSeat = vehicle;
            tell("他坐上了 " + him.vehicleType() + "，上面没有我的座，走路跟着；跟不上会去找他");
        }
        return null;
    }

    // 按距离走或停：离他脚下那一格超过 distance+2 起步，进 distance 叫停；路上他接着走就换目标点，远了跑、近了走。
    private Next<Phase> keepPace(TickContext context, FollowView.Sighting him) {
        double gap = him.groundGap();
        if (gap <= input.distance() + HYSTERESIS) {
            // 跟上了：之前跟不过去的话说一声，计时清零，算一次真实进展。
            if (blockedSince >= 0) tell("又跟上了，接着跟");
            blockedSince = -1;
            recordProgress("跟着他，相距 " + round(him.distance()) + " 格");
        }
        if (walk != null) {
            if (!yielding) {
                if (gap <= input.distance()) {
                    // 进了设定距离：叫停，落稳了就停住，不走到他跟前去。
                    walk.stop();
                } else {
                    walk.allowSprint(gap > input.distance() + SPRINT_MARGIN);
                    retargetIfMoved(him);
                }
            }
            Next<Phase> settled = stepWalk(context);
            if (settled != null) return settled;
            if (walk != null) return Next.stay();
        }
        if (gap > input.distance() + HYSTERESIS) {
            startWalk(him);
            return Next.stay();
        }
        // 停着：看着他；挡了他的道就让到一边。
        moves.lookAt(context, him.eye());
        maybeYield(context, him);
        return Next.stay();
    }

    // 推进一次走路；还在走返回 null，走完（到了或停下）清掉，走不通转去跟不过去。
    private Next<Phase> stepWalk(TickContext context) {
        ActionStatus status = walk.tick(context);
        if (status instanceof ActionStatus.Running running && running.progressed()) {
            recordProgress(walk.describe());
        }
        if (status instanceof ActionStatus.Failed failed) {
            boolean wasYield = yielding;
            closeWalk();
            // 让路走不过去：原地站着就是了，不算跟不过去。
            return wasYield ? null : startBlocked(context, failed.problem());
        }
        WalkReport report = walk.report();
        if (status instanceof ActionStatus.Done || report.state() == WalkReport.State.ARRIVED
                || report.state() == WalkReport.State.STOPPED) {
            closeWalk();
        }
        return null;
    }

    private void startWalk(FollowView.Sighting him) {
        closeWalk();
        walkAnchor = him.ground();
        yielding = false;
        // 走到他脚下那一格的 distance 以内就算跟上；路上进了 distance 也会叫停。
        walk = walks.start(goalNear(him.ground()), permit);
        walk.allowSprint(him.groundGap() > input.distance() + SPRINT_MARGIN);
        lastTryTick = now;
    }

    private GoalCompiler.Compiled goalNear(BlockPos ground) {
        return GoalCompiler.near(ground, Math.max(1.0, input.distance()));
    }

    // 他挪出原目标点 3 格：换目标点，路线不停下重算；这种走法换不了就另开一趟。
    private void retargetIfMoved(FollowView.Sighting him) {
        if (walkAnchor != null && walkAnchor.distSqr(him.ground()) < RETARGET_SLACK * RETARGET_SLACK) return;
        if (walk.retarget(goalNear(him.ground()))) {
            walkAnchor = him.ground();
        } else {
            startWalk(him);
        }
    }

    // 让路：挡了他的道、又过了让路的冷却，在他行进方向两侧挑一格走过去；挑不出就原地不动。
    private void maybeYield(TickContext context, FollowView.Sighting him) {
        if (now - lastYieldTick < YIELD_COOLDOWN_TICKS) return;
        if (!AsideSpot.inTheWay(him.me(), him.feet(), velocityX, velocityZ)) return;
        double[] heading = Math.hypot(velocityX, velocityZ) >= AsideSpot.MOVING
                ? new double[] {velocityX, velocityZ} : AsideSpot.facing(him.yaw());
        lastYieldTick = now;
        Optional<BlockPos> spot = view.asideSpot(context, him, heading[0], heading[1], input.distance());
        if (spot.isEmpty()) return;
        walk = walks.start(GoalCompiler.standOn(spot.get()), permit);
        walk.allowSprint(false);
        walkAnchor = null;
        yielding = true;
        recordProgress("挡了他的道，让到 " + spot.get().toShortString());
    }

    // 跟不过去：停下看着他，发一条事件说清他在哪、我在哪、为什么过不去；连着跟不过去时不重复说。
    private Next<Phase> startBlocked(TickContext context, Problem cause) {
        blockedBecause = cause;
        blockedAnchor = last == null ? null : last.ground();
        lastTryTick = now;
        boolean fresh = blockedSince < 0;
        if (fresh) {
            blockedSince = now;
            tell("跟不过去：" + cause.message() + "；他在 " + spot(last) + "，我停在 " + mine(last)
                    + "；" + openingHint() + "；他挪到我走得到的地方就接着跟");
        }
        recordAttempt("跟过去", "走不通：" + cause.message());
        return Next.go(Phase.BLOCKED, "跟不过去，停下等他");
    }

    // 跟不过去时：看着他；他回到身边就接着跟；他挪开 3 格、离上次试过了 3 秒就再试；2 分钟都没跟上就结束。
    private Next<Phase> blocked(TickContext context) {
        FollowView.Sighting him = see(context);
        if (him == null || him.dead()) {
            closeWalk();
            return lose(context, him);
        }
        Next<Phase> turn = turnFor(context, him);
        if (turn != null) return turn;
        if (walk != null) {
            Next<Phase> settled = stepWalk(context);
            if (settled != null) return Next.stay();
            if (walk != null && walk.report().state() == WalkReport.State.ON_THE_WAY) {
                // 再试的这一趟走起来了：回到跟着，路上他再挪就换目标点。引擎可能先走一段半截路线，
                // 真走到他跟前才算跟上、才说"又跟上了"；半路又走不通就回来接着等，跟不过去的计时不清零。
                return Next.go(Phase.KEEP, "路走起来了，接着跟");
            }
            if (walk != null) return Next.stay();
        }
        if (him.groundGap() <= input.distance() + HYSTERESIS) {
            blockedSince = -1;
            tell("他回到我身边了，接着跟");
            return Next.go(Phase.KEEP, "他回来了");
        }
        if (now - blockedSince >= BLOCKED_LIMIT_TICKS) {
            return Next.fail(unreachable());
        }
        moves.lookAt(context, him.eye());
        boolean moved = blockedAnchor == null
                || blockedAnchor.distSqr(him.ground()) >= RETARGET_SLACK * RETARGET_SLACK;
        if (moved && now - lastTryTick >= RETRY_GAP_TICKS) {
            blockedAnchor = him.ground();
            startWalk(him);
        }
        return Next.stay();
    }

    private Problem unreachable() {
        String cause = blockedBecause == null ? "路走不通" : blockedBecause.message();
        return Problem.of(Problem.Kind.UNREACHABLE,
                "跟不上目标：连续 " + BLOCKED_LIMIT_TICKS / 20 / 60 + " 分钟都过不去——" + cause,
                openingHint());
    }

    // 跟不过去时给 LLM 的出路：许可还不让动地形就说放开哪项，已经放开了就说等他回来或取消。
    private String openingHint() {
        return permit.mayChangeTerrain()
                ? "允许的地形改动已经用上还是过不去；等它走回来，或取消跟随"
                : "这次任务不允许改地形（change_blocks=none）；把 change_blocks 提到 temporary 或更高，也许就能跟过去";
    }

    // 看不见他了：分清是过门、死了、下线还是走远了，各说一声；过门就跟进去，走远就去最后看见他的地方找。
    private Next<Phase> lose(TickContext context, FollowView.Sighting dead) {
        closeWalk();
        endHelp();
        lostSince = now;
        if (dead != null || last != null && last.dead()) {
            lost = Lost.DIED;
            tell("他死了（最后在 " + spot(dead != null ? dead : last) + "），在原地等他回来");
            return Next.go(Phase.SEARCH, "他死了，等他回来");
        }
        if (last != null && last.portal() != null) {
            String destination = PortalTrail.destination(last.position().dimension(), last.portal());
            if (destination != null) {
                portalTarget = destination;
                tell("他走进了 " + last.portal() + "（" + spot(last) + "），跟着进去");
                return Next.go(Phase.PORTAL, "他进了传送门，跟进去");
            }
            lost = Lost.PORTAL_FAILED;
            // 出口门把每个人送回各自的重生点：跟进去也落不到他身边，就在原地等。
            tell("他走进了末地的出口门，这扇门把人送回各自的重生点，跟进去也找不到他；在原地等他回来");
            return Next.go(Phase.SEARCH, "他进了跟不过去的门");
        }
        if (view.presence(context) == FollowView.Presence.OFFLINE) {
            lost = Lost.OFFLINE;
            tell("他下线了（最后在 " + spot(last) + "），在原地等他上线");
            return Next.go(Phase.SEARCH, "他下线了，等他回来");
        }
        lost = Lost.OUT_OF_SIGHT;
        String where = last == null ? "看不见了" : "最后看见他在 " + spot(last) + "（" + lastDirection + "）";
        tell("跟丢了：" + where + "，去那儿找");
        if (last != null) {
            walk = walks.start(GoalCompiler.near(last.ground(), 1.5), permit);
            walkAnchor = null;
            yielding = false;
            // 找人时朝他离开的方向看：从最后的位置顺着他走的方向往前 8 格。
            double speed = Math.hypot(velocityX, velocityZ);
            lookOut = speed < AsideSpot.MOVING ? last.eye()
                    : last.eye().add(velocityX / speed * 8, 0, velocityZ / speed * 8);
        }
        return Next.go(Phase.SEARCH, "跟丢了，去找");
    }

    // 找人：重新看见他就接着跟；走远了的先走到最后看见他的地方，再朝他离开的方向看；30 秒找不回来就结束。
    private Next<Phase> search(TickContext context) {
        FollowView.Sighting him = see(context);
        if (him != null && !him.dead()) {
            closeWalk();
            blockedSince = -1;
            tell("又看见他了（" + spot(him) + "），接着跟");
            return Next.go(Phase.KEEP, "找回来了");
        }
        if (walk != null) {
            ActionStatus status = walk.tick(context);
            if (status instanceof ActionStatus.Running running && running.progressed()) {
                recordProgress("去最后看见他的地方找");
            } else if (!(status instanceof ActionStatus.Running)) {
                closeWalk();
            }
        } else if (lookOut != null) {
            moves.lookAt(context, lookOut);
        }
        if (now - lostSince >= SEARCH_LIMIT_TICKS) {
            String where = last == null ? "" : "，最后看见他在 " + spot(last) + "（" + lastDirection + "）";
            return Next.fail(Problem.of(Problem.Kind.TARGET_GONE,
                    "跟丢了目标：" + lost.ending + where,
                    "再 observe 一次，找到它后用新的观察编号或名字重新跟随"));
        }
        return Next.stay();
    }

    // 过门：把出行的跨维度段当子任务跑；过去了就在那边找他，没过去也按跟丢找满 30 秒再说。
    private Next<Phase> portal(TickContext context) {
        if (portalRun == null) {
            portalRun = new ChildTaskRunner(ChildTaskRunner.NO_LIMIT);
            portalRun.begin(moves.crossPortal(portalTarget, input.permissions()), context);
        }
        if (!portalRun.finished()) {
            TickResult result = portalRun.tick(context);
            if (result instanceof TickResult.Running) {
                if (portalRun.progressedNow().isPresent()) recordProgress("跟着过传送门");
                return Next.stay();
            }
        }
        TaskResult crossed = portalRun.result();
        portalRun = null;
        lostSince = now;
        lookOut = null;
        if (crossed.status() == TaskResult.Status.DONE) {
            lost = Lost.AFTER_PORTAL;
            tell("跟着过了传送门，到了 " + portalTarget + "，在这边找他");
            return Next.go(Phase.SEARCH, "过了门，找他");
        }
        lost = Lost.PORTAL_FAILED;
        String why = crossed.problem() == null ? crossed.summary() : crossed.problem().message();
        recordAttempt("跟着过传送门", why);
        tell("没跟着过去传送门：" + why + "；在原地等他回来");
        return Next.go(Phase.SEARCH, "没过去，等他回来");
    }

    // 上车：坐上了就和他同乘；没坐上记下这辆，一阵子内不再去坐，接着走路跟。
    private Next<Phase> board(TickContext context) {
        FollowView.Sighting him = see(context);
        if (him == null || him.vehicleId() == null || !him.vehicleId().equals(boardingVehicle)) {
            // 他已经下来或换了车：不坐了，接着跟。
            endBoarding();
            return him == null ? lose(context, null) : Next.go(Phase.KEEP, "他不在那辆上了");
        }
        ActionStatus status = boarding.tick(context);
        if (status instanceof ActionStatus.Running running) {
            if (running.progressed()) recordProgress(boarding.describe());
            return Next.stay();
        }
        if (status instanceof ActionStatus.Failed failed) {
            refusedVehicles.put(boardingVehicle, now);
            boardFailures.merge(boardingVehicle, 1, Integer::sum);
            recordAttempt("坐上他那辆 " + him.vehicleType(), failed.problem().message());
            tell("没坐上他那辆 " + him.vehicleType() + "：" + failed.problem().message() + "；走路跟着");
            endBoarding();
            return Next.go(Phase.KEEP, "没坐上，走路跟");
        }
        endBoarding();
        recordProgress("坐上了他那辆 " + him.vehicleType());
        tell("坐上了他那辆 " + him.vehicleType());
        return Next.go(Phase.RIDE, "坐上了");
    }

    // 同乘：他还在这辆上就坐着；他下来了我也下来；我被甩下来了就接着走路跟。
    private Next<Phase> ride(TickContext context) {
        FollowView.Sighting him = see(context);
        if (him == null) return lose(context, null);
        if (him.sameVehicle()) {
            recordProgress("和他坐同一辆");
            return Next.stay();
        }
        if (!him.meRiding()) {
            return Next.go(Phase.KEEP, "我已经不在车上了");
        }
        tell(him.vehicleId() == null ? "他下来了，我也下来" : "他换了一辆，我先下来");
        return Next.go(Phase.GET_OFF, "他不在这辆上了，下来");
    }

    private Next<Phase> getOff(TickContext context) {
        if (gettingOff == null) gettingOff = moves.getOff();
        ActionStatus status = gettingOff.tick(context);
        if (status instanceof ActionStatus.Running) return Next.stay();
        gettingOff.close();
        gettingOff = null;
        if (status instanceof ActionStatus.Failed failed) {
            recordAttempt("从载具上下来", failed.problem().message());
            return Next.fail(Problem.of(Problem.Kind.STUCK, "跟随要下车接着走，" + failed.problem().message(),
                    "看看是不是被什么卡在座位上了"));
        }
        recordProgress("从载具上下来了");
        return Next.go(Phase.KEEP, "下来了，接着跟");
    }

    // 帮打：战斗引擎打他身边的敌对生物和打我的；打完、撤出来都回去跟；他不见了先把这场打完再去找。
    private Next<Phase> help(TickContext context) {
        FollowView.Sighting him = see(context);
        if (help == null) {
            help = moves.helpFight(() -> last == null ? null : last.feet());
            helpLanded = false;
            tell("他在跟敌对生物打（" + spot(last) + "），上去帮他");
        }
        Battle.Moment moment = help.tick(context);
        if (moment.progressed()) {
            helpLanded = true;
            recordProgress("帮他打：" + moment.doing());
        }
        switch (moment.state()) {
            case FINISHED, NEEDS_SHELTER -> {
                boolean landed = helpLanded;
                endHelp();
                // 一下没打着就结束（怪在够不着的地方）：歇半分钟再看，免得一直在"上去帮—帮完了"之间打转。
                helpCooldown = landed ? 0 : HELP_IDLE_COOLDOWN_TICKS;
                tell(landed ? "帮他打完了，接着跟" : "他身边的敌对生物我够不着，接着跟");
                return him == null ? lose(context, null) : Next.go(Phase.KEEP, "打完了");
            }
            case ESCAPED -> {
                endHelp();
                helpCooldown = HELP_COOLDOWN_TICKS;
                tell("帮他打不过，撤出来了（" + moment.retreatWhy() + "），接着跟");
                return him == null ? lose(context, null) : Next.go(Phase.KEEP, "撤出来了");
            }
            default -> {
                return Next.stay();
            }
        }
    }

    private void endHelp() {
        if (help == null) return;
        help.close();
        help = null;
        helpEndedTick = now;
    }

    private void endBoarding() {
        if (boarding != null) {
            boarding.close();
            boarding = null;
        }
        boardingVehicle = null;
    }

    // 许可只给自卫（fight=self_defense）时不帮他打。
    private boolean mayHelp() {
        return input.permissions().fight() != Permissions.Fight.SELF_DEFENSE;
    }

    private void closeWalk() {
        if (walk != null) {
            walk.close();
            walk = null;
        }
        walkAnchor = null;
        yielding = false;
    }

    private void tell(String what) {
        String who = target == null ? "" : "跟随 " + target.name() + "：";
        events.publish(TaskEvent.Kind.FOLLOW_STATUS, who + what);
    }

    private static String spot(FollowView.Sighting him) {
        if (him == null) return "未知位置";
        WorldPosition p = him.position();
        return p.x() + ", " + p.y() + ", " + p.z();
    }

    private static String mine(FollowView.Sighting him) {
        if (him == null) return "原地";
        Vec3 me = him.me();
        return (int) Math.floor(me.x) + ", " + (int) Math.floor(me.y) + ", " + (int) Math.floor(me.z);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /** 陪着的人睡没睡：最近一刻看见他躺在床上。夜晚需求据此决定去不去睡。 */
    @Override
    public boolean companionAsleep() {
        return last != null && last.sleeping() && now - lastSeenTick <= 20;
    }

    /** 正在帮他打、引擎没在撤：被攻击的生存需求让位，这场仗归帮打管。 */
    @Override
    public boolean confidentAgainstCurrentThreats() {
        return phase() == Phase.HELP && help != null && !help.retreating();
    }

    /** 被带离原地后从哪接着做都行：跟随本来就跟着人挪地方，回来先看他在哪。 */
    @Override
    public boolean resumesAnywhere() {
        return true;
    }

    @Override
    public Interruptibility interruptibility(TickContext context) {
        if (portalRun != null && !portalRun.finished()) return portalRun.interruptibility(context);
        if (help != null) return help.interruptibility();
        if (boarding != null) return boarding.interruptibility();
        if (gettingOff != null) return gettingOff.interruptibility();
        // 坐在车上：吃口东西这类事插得进来，夜里不会被拽下车去睡。
        if (phase() == Phase.RIDE) return Interruptibility.WORKING;
        // 走路途中按走路说（在空中不能停）；停着就是两个动作之间，不急的事（他睡了我也去睡）能插进来。
        if (walk != null) return walk.interruptibility();
        return Interruptibility.BETWEEN_ACTIONS;
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case LOCK -> "锁定目标";
            case KEEP -> "跟着";
            case BLOCKED -> "跟不过去，等他";
            case SEARCH -> "跟丢了，找他";
            case PORTAL -> "跟着过传送门";
            case BOARD -> "坐上他那辆";
            case RIDE -> "和他同乘";
            case GET_OFF -> "下车";
            case HELP -> "帮他打";
        };
    }

    @Override
    protected ResultDetails details() {
        return target == null ? ResultDetails.NONE
                : new FollowDetails(target.type() + "（实体 " + target.entityId() + "）",
                        last == null ? null : round(last.distance()), lastDirection);
    }

    /** 跟随任务的结果细节：跟的是谁、结束时的距离与最后一次看到目标在哪个方位。 */
    record FollowDetails(String targetId, Double keptDistance, String lastDirection) implements ResultDetails {}
}
