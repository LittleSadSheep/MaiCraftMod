// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 坐一段火车：走到上车站 → 等那趟车进站停稳 → 走到空座位旁坐上去 → 坐着别动 → 到下车站停稳 → 潜行下车。
 *
 * <p>等车最多等两分钟（所有者 10-11 定），没来如实说哪趟车没来；车上没空座如实说。坐着时车在中途停很久、
 * 离下车站长时间不近，就在它停靠的那一站下车，行程从那里接着规划。换乘的下一段由行程再报一次价。
 */
final class TrainRide implements TravelLeg {

    /** 坐火车的几步。 */
    enum Step { WALK, WAIT, BOARD, SIT, RIDE, ALIGHT }

    /** 等车最多等多少刻（2 分钟）。 */
    static final int MAX_WAIT_TICKS = 2400;
    /** 车停稳这么多刻才算进站停好。 */
    static final int STOPPED_TICKS = 10;
    /** 车厢离车站这么近（格）才算停在这一站。 */
    static final double AT_STATION = 24;
    /** 坐座位等这么多刻没坐上就换一个座位，最多换三个。 */
    static final int SIT_WAIT_TICKS = 40;
    static final int MAX_SEATS = 3;
    /** 坐着这么多刻离下车站一点没近就在下一个停靠站下车。 */
    static final int NO_GAIN_TICKS = 2400;
    /** 下车最多潜行多少刻。 */
    static final int ALIGHT_TICKS = 40;
    /** 离座位这么近（格）才伸手坐。 */
    static final double SEAT_REACH = 3.5;

    private final CreateCompat compat;
    private final PlayerServices services;
    private final Permissions permissions;
    private final String trainId;
    private final CreateRailways.Station board;
    private final CreateRailways.Station alight;

    private Step step = Step.WALK;
    private Action current;
    private long stepStart = Long.MIN_VALUE;
    private long stillSince = Long.MIN_VALUE;
    private int seatsTried;
    private long sitAt;
    private double bestDistance = Double.MAX_VALUE;
    private long lastGain;
    private boolean gaveUp;
    private String trainName = "";

    TrainRide(CreateCompat compat, PlayerServices services, Permissions permissions, String trainId,
            CreateRailways.Station board, CreateRailways.Station alight) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.trainId = Objects.requireNonNull(trainId, "trainId");
        this.board = Objects.requireNonNull(board, "board");
        this.alight = Objects.requireNonNull(alight, "alight");
    }

    @Override public ActionStatus tick(TickContext tick) {
        PlayerContext context = services.context().get();
        ClientLevel level = context == null ? null : context.level();
        LocalPlayer player = context == null ? null : context.localPlayer();
        if (level == null || player == null || context.input() == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在，坐不了火车"));
        }
        long now = level.getGameTime();
        if (stepStart == Long.MIN_VALUE) stepStart = now;
        if (current != null) {
            ActionStatus status = current.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            current.close();
            current = null;
            if (status instanceof ActionStatus.Failed failed) return ActionStatus.failed(failed.problem());
            return go(step == Step.WALK ? Step.WAIT : Step.SIT, now);
        }
        return switch (step) {
            case WALK -> walk(level);
            case WAIT -> waitFor(level, player, now);
            case BOARD -> boardTrain(level, player, now);
            case SIT -> sitDown(level, player, now);
            case RIDE -> ride(context, level, player, now);
            case ALIGHT -> alightHere(context, player, now);
        };
    }

    // 走到上车站：站台就在车站方块旁边。
    private ActionStatus walk(ClientLevel level) {
        if (board.pos().equals(BlockPos.ZERO) && board.dimension().isEmpty()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "车站「" + board.name() + "」不在轨道图上了"));
        }
        current = services.approach().toward(ApproachTarget.ofBlock(board.pos()), permissions);
        return ActionStatus.progressed();
    }

    // 等车：那趟车有车厢停在这站附近、停稳了就去上车；等满两分钟没来如实说。
    private ActionStatus waitFor(ClientLevel level, LocalPlayer player, long now) {
        input().halt(player);
        if (stoppedAt(level, player, board, now)) return go(Step.BOARD, now);
        if (now - stepStart >= MAX_WAIT_TICKS) {
            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "在「" + board.name() + "」站等了 "
                    + MAX_WAIT_TICKS / 20 / 60 + " 分钟，" + (trainName.isEmpty() ? "要坐的那趟车" : "「" + trainName + "」")
                    + "没进站停下", "换一趟车，或走过去"));
        }
        return ActionStatus.running();
    }

    // 上车：挑那趟车停着的车厢里离自己最近的空座位，走到它旁边。
    private ActionStatus boardTrain(ClientLevel level, LocalPlayer player, long now) {
        Optional<Choice> seat = nearestSeat(level, player);
        if (seat.isEmpty()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "「" + trainName + "」车上没有空座位"));
        }
        Vec3 at = seat.get().seat().world();
        if (player.getEyePosition().distanceTo(at) <= SEAT_REACH) return go(Step.SIT, now);
        current = services.approach().toward(ApproachTarget.ofBlock(BlockPos.containing(at)), permissions);
        return ActionStatus.progressed();
    }

    // 坐下：看着座位右键坐上去，等坐上；没坐上换一个座位，换满三个如实说。
    private ActionStatus sitDown(ClientLevel level, LocalPlayer player, long now) {
        if (riding(level, player)) {
            lastGain = now;
            return go(Step.RIDE, now);
        }
        if (sitAt != 0 && now - sitAt < SIT_WAIT_TICKS) return ActionStatus.running();
        if (seatsTried >= MAX_SEATS) {
            return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME, "对着「" + trainName + "」的座位右键了 "
                    + MAX_SEATS + " 次，都没坐上"));
        }
        Optional<Choice> seat = nearestSeat(level, player);
        if (seat.isEmpty()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "「" + trainName + "」车上没有空座位"));
        }
        if (player.getEyePosition().distanceTo(seat.get().seat().world()) > SEAT_REACH) return go(Step.BOARD, now);
        input().lookAt(player, seat.get().seat().world());
        compat.sitOnTrain(seat.get().car().entityId(), seat.get().seat().local());
        seatsTried++;
        sitAt = now;
        return ActionStatus.progressed();
    }

    // 坐着：别动；车停在下车站就下；一直不近下车站，就在它停靠的下一站下车。
    private ActionStatus ride(PlayerContext context, ClientLevel level, LocalPlayer player, long now) {
        input().halt(player);
        if (!riding(level, player)) {
            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "坐在车上时被弄下了车，停在 "
                    + player.blockPosition().toShortString()));
        }
        if (stoppedAt(level, player, alight, now)) return go(Step.ALIGHT, now);
        double distance = Math.sqrt(player.blockPosition().distSqr(alight.pos()));
        if (distance < bestDistance - 1) {
            bestDistance = distance;
            lastGain = now;
        }
        if (now - lastGain >= NO_GAIN_TICKS && stoppedAnywhere(level, player, now)) {
            gaveUp = true;
            return go(Step.ALIGHT, now);
        }
        return ActionStatus.running();
    }

    // 下车：潜行，直到不坐在车上。
    private ActionStatus alightHere(PlayerContext context, LocalPlayer player, long now) {
        if (!player.isPassenger()) {
            input().halt(player);
            if (gaveUp) {
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "坐了很久「" + trainName + "」离「"
                        + alight.name() + "」站一点没近，在 " + player.blockPosition().toShortString() + " 下了车"));
            }
            return ActionStatus.done();
        }
        if (now - stepStart >= ALIGHT_TICKS) {
            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "到站后潜行了 " + ALIGHT_TICKS / 20 + " 秒还没下车"));
        }
        input().applyMovement(new PlayerInput.Movement(0.0f, 0.0f, false, true, false), now);
        return ActionStatus.running();
    }

    /** 一个空座位：在哪节车厢、哪个座位。 */
    private record Choice(CreateRailways.Car car, CreateRailways.Seat seat) {}

    private Optional<Choice> nearestSeat(ClientLevel level, LocalPlayer player) {
        return trainCars(level, player, board.pos()).stream().filter(CreateRailways.Car::still)
                .flatMap(car -> car.freeSeats().stream().map(seat -> new Choice(car, seat)))
                .min(Comparator.comparingDouble(choice -> choice.seat().world().distanceToSqr(player.getEyePosition())));
    }

    // 那趟车有车厢停在这一站附近、停稳了一会儿。
    private boolean stoppedAt(ClientLevel level, LocalPlayer player, CreateRailways.Station station, long now) {
        boolean stopped = trainCars(level, player, station.pos()).stream()
                .anyMatch(car -> car.still() && car.box().getCenter().distanceTo(Vec3.atCenterOf(station.pos())) <= AT_STATION);
        return held(stopped, now);
    }

    // 车停在哪都行（中途下车时用）：坐的那节车厢停稳了一会儿。
    private boolean stoppedAnywhere(ClientLevel level, LocalPlayer player, long now) {
        return held(trainCars(level, player, player.blockPosition()).stream()
                .anyMatch(car -> car.carriesPlayer() && car.still()), now);
    }

    private boolean held(boolean stopped, long now) {
        if (!stopped) {
            stillSince = Long.MIN_VALUE;
            return false;
        }
        if (stillSince == Long.MIN_VALUE) stillSince = now;
        return now - stillSince >= STOPPED_TICKS;
    }

    private boolean riding(ClientLevel level, LocalPlayer player) {
        return trainCars(level, player, player.blockPosition()).stream().anyMatch(CreateRailways.Car::carriesPlayer);
    }

    private List<CreateRailways.Car> trainCars(ClientLevel level, LocalPlayer player, BlockPos around) {
        List<CreateRailways.Car> cars = compat.trainCars(level, player, new AABB(around).inflate(AT_STATION * 2))
                .stream().filter(car -> car.trainId().equals(trainId)).toList();
        if (!cars.isEmpty() && trainName.isEmpty()) trainName = cars.getFirst().trainName();
        return cars;
    }

    private PlayerInput input() {
        return services.context().get().input();
    }

    private ActionStatus go(Step next, long now) {
        step = next;
        stepStart = now;
        stillSince = Long.MIN_VALUE;
        if (next == Step.BOARD) sitAt = 0;
        return ActionStatus.progressed();
    }

    @Override public void requestSafeStop() {
        // 车上的等停车下车：放弃往下车站坐，下一次停稳就下。
        lastGain = Long.MIN_VALUE / 2;
    }

    @Override public void pause() {
        if (current != null) current.pause();
        PlayerContext context = services.context().get();
        if (context != null && context.input() != null && context.localPlayer() != null) context.input().halt(context.localPlayer());
    }

    @Override public void close() {
        if (current != null) {
            current.close();
            current = null;
        }
        PlayerContext context = services.context().get();
        if (context != null && context.input() != null && context.localPlayer() != null) context.input().halt(context.localPlayer());
    }

    @Override public Interruptibility interruptibility() {
        if (current != null) return current.interruptibility();
        return step == Step.ALIGHT || step == Step.SIT ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.BETWEEN_ACTIONS;
    }

    @Override public String summary() {
        return "坐「" + (trainName.isEmpty() ? "列车" : trainName) + "」从「" + board.name() + "」到「" + alight.name() + "」";
    }

    @Override public String describe() {
        String doing = switch (step) {
            case WALK -> "走到车站";
            case WAIT -> "等车";
            case BOARD -> "走到座位旁";
            case SIT -> "坐上去";
            case RIDE -> "坐着";
            case ALIGHT -> "下车";
        };
        return summary() + "：" + doing + (current == null ? "" : "，" + current.describe());
    }
}
