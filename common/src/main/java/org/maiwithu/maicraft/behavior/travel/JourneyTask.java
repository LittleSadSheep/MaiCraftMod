// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.lighting.TorchesAlongTheWay;
import org.maiwithu.maicraft.behavior.travel.spi.OfferSink;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.behavior.travel.spi.TravelMode;
import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelRequest;
import org.maiwithu.maicraft.behavior.travel.spi.TravelVia;
import org.maiwithu.maicraft.kernel.child.ChildTaskRunner;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
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
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 换交通的一趟出行，几段，每段一种方式：报价 → 挑 → 跑这一段 → 从当前位置接着规划，直到站在目的地。
 * 走路的一段交给出行任务（挖垫、泅渡、落地救援、脱困照旧）；别的方式交给它们自己的一段。
 *
 * <p>一段失败、人还好好地站着，不判整趟失败：这段记进 legs，从当前位置重新规划；试过的报价这一趟不再试。
 * 指定了方式只用它，给不出报价如实结束，不悄悄改走路。只走路的出行（没装任何联动的出行方式、也没给 via）
 * 不经过这里，直接是出行任务。
 */
public final class JourneyTask extends PhasedTask<JourneyTask.Phase> implements TorchesAlongTheWay {

    /** 规划这一段 → 走路 / 坐别的方式。 */
    public enum Phase { PLAN, WALK, RIDE }

    /** 问报价最多等这么多刻（3 秒）；还没答的当作这次答不上来。 */
    private static final int OFFER_WAIT_TICKS = 60;
    /** 身体挪了这么远才算这一段有进展。 */
    private static final double MOVED_BLOCKS = 0.25;
    /** 整趟多久没有真实进展算卡住：等车最多两分钟，留出余量；每段自己的进度跟踪先管着。 */
    private static final long STUCK_AFTER_TICKS = 20L * 60 * 3;

    /**
     * 这趟行程的给定输入。
     *
     * @param destination 已经解析好的目的地；只用指定的方式、不往别处去（只坐电梯换楼层）时为 null
     * @param via         指定的方式（walk 只走路）；没指定为 null，没有目的地时必须指定
     * @param options     travel 参数里给方式的话（floor、vehicle）；没给为空表
     * @param permissions 这次任务的许可：指定方式不放宽它
     * @param maxSeconds  整趟最多多久（秒）；不超过 0 表示不设时限
     */
    public record Journey(TravelDestination destination, String via, Map<String, String> options,
            Permissions permissions, int maxSeconds) {
        public Journey {
            if (destination == null && via == null) throw new IllegalArgumentException("没有目的地时要指定方式");
            options = options == null ? Map.of() : Map.copyOf(options);
            Objects.requireNonNull(permissions, "permissions");
        }
    }

    private final Journey journey;
    private final TravelWorldView world;
    private final List<TravelMode> modes;
    private final Function<TravelDestination, Task> walking;

    private final List<JourneyDetails.Leg> legs = new ArrayList<>();
    private final Set<String> tried = new HashSet<>();
    private final Map<TravelMode, Boolean> answered = new LinkedHashMap<>();
    private final List<TravelOffer> offers = new ArrayList<>();
    private final List<TravelPlanner.Declined> declined = new ArrayList<>();
    private final List<String> placedBlocks = new ArrayList<>();
    private final List<String> landings = new ArrayList<>();
    private final List<String> trapEscapes = new ArrayList<>();
    private boolean crossedWater;

    /** 第几轮问报价；方式留着旧回答口在别的轮里答的不算。 */
    private int round;
    /** 这一轮已经问了几刻；0 表示这一轮还没开始。 */
    private int askedTicks;
    private WalkingLeg walk;
    private RidingLeg ride;
    /** 从这里走路刚走不通：比报价时不再把走路算进去；身体挪了地方就清掉。 */
    private Problem walkProblem;
    /** 指定的方式已经送过一段：剩下的就是首尾的步行。 */
    private boolean rodeAlready;
    private BlockPos lastFeet;
    private WorldPosition arrivedAt;
    /** 这一轮问报价用的请求：开跑那一段时原样交给方式（许可、给方式的话）。 */
    private TravelRequest request;
    /** 这一轮说"非我不可"的方式（站在船上先下船、目的地在船上先上船）。 */
    private List<TravelMode> claiming = List.of();

    /**
     * @param world   读角色此刻在哪（带维度）
     * @param modes   已登记的出行方式（联动的，包过一层）
     * @param walking 走路一段的出行任务怎么建：挖垫、泅渡、落地救援照出行能力的那一套
     */
    public JourneyTask(Journey journey, TravelWorldView world, List<TravelMode> modes,
            Function<TravelDestination, Task> walking) {
        super("出行", Phase.PLAN, new ProgressTracker(STUCK_AFTER_TICKS,
                journey.maxSeconds() > 0 ? journey.maxSeconds() * 20L : Long.MAX_VALUE));
        this.journey = journey;
        this.world = Objects.requireNonNull(world, "world");
        this.modes = List.copyOf(modes);
        this.walking = Objects.requireNonNull(walking, "walking");
    }

    @Override protected Action enter(Phase phase) {
        return switch (phase) {
            case PLAN -> null;
            case WALK -> walk;
            case RIDE -> ride;
        };
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case PLAN -> plan(context);
            case WALK -> walkOn(context);
            case RIDE -> rideOn(context);
        };
    }

    // 规划这一段：站到了就结束；要比报价就问各方式（可以分几刻答完，最多等 3 秒）；挑走路、坐哪种，或者哪都去不了。
    private Next<Phase> plan(TickContext context) {
        WorldPosition spot = world.currentSpot();
        if (spot == null) return Next.stay();
        BlockPos feet = new BlockPos(spot.x(), spot.y(), spot.z());
        lastFeet = feet;
        TravelDestination destination = journey.destination();
        if (destination != null && destination.navGoal().isAt(feet)) {
            arrivedAt = spot;
            return Next.done(TaskResult.done("出行：到了" + destination.describe() + "，实测位置 "
                    + feet.toShortString()));
        }
        if (destination == null && rodeAlready) {
            // 只坐指定的方式、不往别处去（只坐电梯换楼层）：送完这一段就是到了。
            arrivedAt = spot;
            return Next.done(TaskResult.done("出行：" + journey.via() + " 送到了，实测位置 " + feet.toShortString()));
        }
        // 被叫先到安全的地方再停（暂停、取消、不急的需求）：坐的那一段已经停下，这里就站着等，不再开下一段。
        if (stopping()) return Next.stay();
        BlockPos aim = aim(feet);
        String via = journey.via();
        request = new TravelRequest(feet, aim, spot.dimension() == null ? "" : spot.dimension(),
                destination == null ? 0 : destination.radius(), journey.permissions(), journey.options(),
                rodeAlready ? null : via);
        if (askedTicks == 0) {
            // 新一轮：上一轮的回答作废。有方式说这一段非它不可（人站在船上要先下船、目的地在船上要先上船），
            // 就只问它；否则只问指定的那种（没指定问全部）。
            round++;
            answered.clear();
            offers.clear();
            declined.clear();
            claiming = modes.stream().filter(mode -> mode.claims(request)).toList();
        }
        boolean compare = !claiming.isEmpty() || !TravelVia.WALK.name().equals(via)
                && !(via == null && walkProblem == null && TravelPlanner.near(feet, aim));
        if (askedTicks == 0 && compare) {
            for (TravelMode mode : claiming.isEmpty() ? modes : claiming) {
                if (!claiming.isEmpty() || via == null || mode.via().name().equals(via)) answered.put(mode, false);
            }
        }
        if (compare) {
            for (Map.Entry<TravelMode, Boolean> entry : answered.entrySet()) {
                if (!entry.getValue()) entry.getKey().offer(request, sinkFor(entry, round));
            }
            if (answered.containsValue(false) && ++askedTicks < OFFER_WAIT_TICKS) return Next.stay();
            answered.forEach((mode, done) -> {
                if (!done) declined.add(new TravelPlanner.Declined(mode.via(), Problem.Kind.UNREACHABLE,
                        "看了 " + OFFER_WAIT_TICKS / 20 + " 秒现场还没答上来"));
            });
        }
        askedTicks = 0;
        TravelPlanner.Plan plan = claiming.isEmpty()
                ? TravelPlanner.choose(via, feet, aim, walkProblem != null, rodeAlready, offers, declined, tried)
                : claimed();
        return switch (plan) {
            case TravelPlanner.Plan.Walk ignored -> {
                walk = new WalkingLeg(walking.apply(destination), feet, context.gameTick());
                yield Next.go(Phase.WALK, "这一段走路");
            }
            case TravelPlanner.Plan.Ride chosen -> {
                TravelOffer offer = chosen.offer();
                TravelMode mode = modes.stream().filter(candidate -> candidate.via().equals(offer.via()))
                        .findFirst().orElseThrow();
                ride = new RidingLeg(offer, mode.start(request, offer), feet, context.gameTick());
                recordProgress("这一段用 " + offer.via().name() + "：" + offer.from().toShortString()
                        + " → " + offer.to().toShortString());
                yield Next.go(Phase.RIDE, "这一段用 " + offer.via().name());
            }
            case TravelPlanner.Plan.DeadEnd dead -> Next.fail(deadEnd(dead.problem()));
        };
    }

    // 非它不可的那一段：挑它没试过、最快的那份；它答不出就照它的原因结束，不改走路（走路过不去船上）。
    private TravelPlanner.Plan claimed() {
        return offers.stream().filter(offer -> !tried.contains(TravelPlanner.key(offer)))
                .min(Comparator.comparingLong(TravelOffer::estimatedTicks))
                .<TravelPlanner.Plan>map(TravelPlanner.Plan.Ride::new)
                .orElseGet(() -> new TravelPlanner.Plan.DeadEnd(declined.isEmpty()
                        ? Problem.of(Problem.Kind.UNREACHABLE, claiming.getFirst().via().name() + " 这一趟能试的都试过了，没走成")
                        : Problem.of(declined.getFirst().kind(), declined.getFirst().via().name() + " 走不了："
                                + declined.getFirst().reason())));
    }

    // 回答口只认这一轮：方式留着旧口在下一轮才答的，算不到这一轮里。
    private OfferSink sinkFor(Map.Entry<TravelMode, Boolean> entry, int askedRound) {
        return new OfferSink() {
            @Override public void offer(TravelOffer offer) {
                if (askedRound != round) return;
                offers.add(offer);
                entry.setValue(true);
            }

            @Override public void decline(Problem.Kind kind, String reason) {
                if (askedRound != round) return;
                declined.add(new TravelPlanner.Declined(entry.getKey().via(), kind, reason));
                entry.setValue(true);
            }
        };
    }

    // 走路这一段：到了就结束；走不通（无路、要拆别人的东西、困住、缺垫料）而有别的方式可比时，从这里比报价。
    private Next<Phase> walkOn(TickContext context) {
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) return Next.stay();
        TaskResult result = walk.settle(context);
        walk = null;
        if (result.status() == TaskResult.Status.DONE) {
            // 出行任务确认走到了：实测位置以它的结算为准，没给就读此刻站的地方。
            if (arrivedAt == null) arrivedAt = world.currentSpot();
            return Next.done(TaskResult.done(result.summary()));
        }
        if (result.status() == TaskResult.Status.CANCELLED) {
            return Next.done(TaskResult.cancelled(result.summary()));
        }
        Problem problem = result.problem();
        if (journey.via() == null && !modes.isEmpty() && problem != null && switch (problem.kind()) {
            case UNREACHABLE, NEED_APPROVAL, STUCK, NEED_ITEM -> true;
            default -> false;
        }) {
            walkProblem = problem;
            recordAttempt("走路", problem.headline());
            return Next.go(Phase.PLAN, "走不通，从这里比别的方式的报价");
        }
        // 走不通又没有别的方式可比（或指定了只走路）：照出行任务的结论结束，事实已经并进来了。
        return Next.done(TaskResult.builder(result.status(), result.summary()).problem(problem).build());
    }

    // 坐别的方式这一段：结束了就记一段，从当前位置接着规划；失败也只记一段、这份报价这一趟不再试。
    private Next<Phase> rideOn(TickContext context) {
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) return Next.stay();
        Problem failure = status instanceof ActionStatus.Failed failed ? failed.problem() : null;
        TravelOffer offer = ride.offer;
        boolean moved = ride.settle(context, failure);
        ride = null;
        tried.add(TravelPlanner.key(offer));
        if (moved) {
            recordProgress(offer.via().name() + " 送到 " + shortSpot(lastFeet));
            walkProblem = null;
        }
        if (failure == null) {
            // 指定的方式送过一段，剩下的就是首尾的步行；先下船、先上船这种不算。
            if (offer.via().name().equals(journey.via())) rodeAlready = true;
        } else {
            recordAttempt(offer.via().name() + " 从 " + offer.from().toShortString() + " 到 " + offer.to().toShortString(),
                    failure.headline());
        }
        return Next.go(Phase.PLAN, "这一段结束，从当前位置接着规划");
    }

    // 哪都去不了：刚才是走路走不通，就以走路的问题结束，并写上别的方式为什么送不了。
    private Problem deadEnd(Problem planned) {
        if (walkProblem == null) return planned;
        return Problem.of(walkProblem.kind(), walkProblem.message() + "；也没有别的方式能送"
                + TravelPlanner.reasons(declined), walkProblem.suggestion());
    }

    // 量距离、问报价用的终点：高度没核实时按脚下这层算，只比水平远近；没有目的地为 null。
    private BlockPos aim(BlockPos feet) {
        TravelDestination destination = journey.destination();
        if (destination == null) return null;
        BlockPos center = destination.center();
        return destination.heightConfirmed() ? center : new BlockPos(center.getX(), feet.getY(), center.getZ());
    }

    private BlockPos feetNow() {
        WorldPosition spot = world.currentSpot();
        if (spot != null) lastFeet = new BlockPos(spot.x(), spot.y(), spot.z());
        return lastFeet;
    }

    // 走路这一段的结算并进整趟：垫的方块、落地救援、脱困、泅渡合在一起，到了记实测位置。
    private void absorb(ResultDetails details) {
        if (!(details instanceof TravelSettlement settlement)) return;
        placedBlocks.addAll(settlement.placedBlocks());
        landings.addAll(settlement.landings());
        trapEscapes.addAll(settlement.trapEscapes());
        crossedWater |= settlement.crossedWater();
        if (settlement.arrivedAt() != null) arrivedAt = settlement.arrivedAt();
    }

    private void copyFacts(TaskResult result) {
        result.changes().forEach(this::recordChange);
        result.unconfirmed().forEach(this::recordUnconfirmed);
        result.attempts().forEach(attempt -> recordAttempt(attempt.tried(), attempt.result()));
    }

    private static String shortSpot(BlockPos pos) {
        return pos == null ? "未知" : pos.toShortString();
    }

    @Override public Interruptibility interruptibility(TickContext context) {
        // 走路这一段能不能停看出行任务自己的（悬空、正在搭桥时不能停）。
        if (phase() == Phase.WALK && walk != null) return walk.interruptibility(context);
        return super.interruptibility(context);
    }

    @Override protected ResultDetails details() {
        BlockPos aim = lastFeet == null ? null : aim(lastFeet);
        return new JourneyDetails(arrivedAt,
                arrivedAt == null && aim != null ? RemainingDistance.measure(lastFeet, aim) : null,
                placedBlocks, landings, trapEscapes, crossedWater, legs);
    }

    @Override protected List<String> remaining() {
        if (arrivedAt != null || lastFeet == null) return List.of();
        BlockPos aim = aim(lastFeet);
        if (aim == null) return List.of("停在 " + shortSpot(lastFeet) + "，" + journey.via() + " 还没送到");
        return List.of("停在 " + shortSpot(lastFeet) + "，还没到目的地：差 "
                + RemainingDistance.measure(lastFeet, aim).describe());
    }

    @Override protected String describePhase(Phase value) {
        String where = journey.destination() == null ? "坐 " + journey.via() : "去" + journey.destination().describe();
        return switch (value) {
            case PLAN -> "规划下一段：" + where;
            case WALK -> "走路：" + where;
            case RIDE -> ride == null ? "换交通" : "坐 " + ride.offer.via().name();
        };
    }

    /**
     * 走路这一段包成动作：出行任务作为子任务逐刻推进；整趟被暂停、收尾时连着它一起暂停、收尾，
     * 它途中垫的方块、试过的办法照样记进这趟行程。
     */
    private final class WalkingLeg implements Action {
        private final ChildTaskRunner runner = new ChildTaskRunner(ChildTaskRunner.NO_LIMIT);
        private final Task task;
        private final BlockPos from;
        private final long startTick;
        private long lastTick;
        private boolean started;
        private boolean settled;

        WalkingLeg(Task task, BlockPos from, long startTick) {
            this.task = task;
            this.from = from;
            this.startTick = startTick;
            this.lastTick = startTick;
        }

        @Override public ActionStatus tick(TickContext context) {
            lastTick = context.gameTick();
            if (!started) {
                started = true;
                runner.begin(task, context);
                if (runner.finished()) return ActionStatus.done();
            }
            if (runner.tick(context) instanceof TickResult.Finished) return ActionStatus.done();
            // 出行任务这一刻有真实进展（离目的地更近、垫下一格），整趟也算有进展。
            boolean progressed = runner.currentProgress().map(progress -> progress.ticksSinceProgress() == 0)
                    .orElse(false);
            return progressed ? ActionStatus.progressed() : ActionStatus.running();
        }

        /** 这段走完了：事实并进整趟，记一段；交回出行任务的结果给规划看。 */
        TaskResult settle(TickContext context) {
            settled = true;
            TaskResult result = runner.result();
            record(result, context.gameTick());
            return result;
        }

        private void record(TaskResult result, long now) {
            copyFacts(result);
            absorb(result.details());
            String outcome = switch (result.status()) {
                case DONE -> "到了";
                case CANCELLED -> "中途停下";
                default -> result.problem() == null ? result.summary() : result.problem().headline();
            };
            legs.add(new JourneyDetails.Leg(TravelVia.WALK.name(), shortSpot(from), shortSpot(feetNow()),
                    now - startTick, outcome));
        }

        Interruptibility interruptibility(TickContext context) {
            return runner.interruptibility(context);
        }

        @Override public void pause() {
            if (started && !runner.finished()) runner.pause();
        }

        // 整趟提前结束（被替换、取消、角色没了）：出行任务按取消收尾，它交代的事实照样记进这趟。
        @Override public void close() {
            if (settled) return;
            settled = true;
            if (started && !runner.finished()) runner.close(CloseReason.CANCELLED);
            if (runner.finished()) record(runner.result(), lastTick);
        }

        @Override public String describe() {
            return started ? runner.describe() : "准备走路";
        }
    }

    /** 坐别的方式这一段包成动作：结束、取消时把这段确认的变化与说不清的那一笔记进整趟，记一段。 */
    private final class RidingLeg implements Action {
        private final TravelOffer offer;
        private final TravelLeg leg;
        private final BlockPos from;
        private final long startTick;
        private long lastTick;
        private boolean settled;

        RidingLeg(TravelOffer offer, TravelLeg leg, BlockPos from, long startTick) {
            this.offer = offer;
            this.leg = Objects.requireNonNull(leg, "leg");
            this.from = from;
            this.startTick = startTick;
            this.lastTick = startTick;
        }

        @Override public ActionStatus tick(TickContext context) {
            lastTick = context.gameTick();
            return leg.tick(context);
        }

        /** 这段结束了：记一段；返回身体挪没挪地方。 */
        boolean settle(TickContext context, Problem failure) {
            settled = true;
            BlockPos feet = feetNow();
            record(context.gameTick(), failure == null ? "到了" : "停在 " + shortSpot(feet) + "：" + failure.headline());
            return from != null && feet != null && Math.sqrt(from.distSqr(feet)) >= MOVED_BLOCKS;
        }

        private void record(long now, String outcome) {
            leg.changes().forEach(JourneyTask.this::recordChange);
            leg.unconfirmed().forEach(JourneyTask.this::recordUnconfirmed);
            String summary = leg.summary();
            legs.add(new JourneyDetails.Leg(offer.via().name(), shortSpot(from), shortSpot(feetNow()), now - startTick,
                    summary == null || summary.isBlank() ? outcome : summary + "；" + outcome));
        }

        @Override public void pause() {
            leg.pause();
        }

        // 先到安全的地方再停：这一段此刻停下不安全（飞着、在轿厢里、开着载具）才叫它就近落地、等停靠、刹停，
        // 停下了这一段就结束，结束就是停稳了；坐着的火车这类此刻停得下的，原地停就行。
        @Override public boolean stopSafely() {
            if (leg.interruptibility() != Interruptibility.UNSAFE_TO_STOP) return false;
            leg.requestSafeStop();
            return true;
        }

        @Override public boolean stoppedSafely() {
            return false;
        }

        // 整趟提前结束：这段按它自己的收尾松键、交出身体，确认的变化照样记进这趟。
        @Override public void close() {
            leg.close();
            if (settled) return;
            settled = true;
            record(lastTick, "中途停下，停在 " + shortSpot(feetNow()));
        }

        @Override public Interruptibility interruptibility() {
            return leg.interruptibility();
        }

        @Override public String describe() {
            return leg.describe();
        }
    }

    /** 本来就在挪地方：被临时任务带离原地后从新地点接着走。 */
    @Override
    public boolean resumesAnywhere() {
        return true;
    }
}
