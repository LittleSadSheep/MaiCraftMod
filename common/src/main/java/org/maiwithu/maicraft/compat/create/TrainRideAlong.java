// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.ability.follow.spi.RideAlong;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 跟着的人坐上了机械动力的列车：他坐的那节车厢里还有空座位，就走过去坐上。
 *
 * <p>只坐他那节车厢：坐到别的车厢，跟随认不出我和他在同一辆上。车在动不上（不跳上开着的车），
 * 走到离座位 3.5 格内看着座位右键，等 2 秒没坐上就换一个座位，换满三个如实说；坐没坐上以角色真坐在那节车厢上为准。
 */
final class TrainRideAlong implements RideAlong {

    private final CreateCompat compat;
    private final PlayerServices services;

    TrainRideAlong(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override
    public Optional<Action> board(PlayerContext context, Entity vehicle, Permissions permissions) {
        Optional<CreateRailways.Car> car = carOf(compat, context.level(), context.localPlayer(), vehicle);
        if (car.isEmpty() || car.get().freeSeats().isEmpty()) return Optional.empty();
        return Optional.of(new SitInCar(vehicle, permissions));
    }

    // 他坐的那节车厢：按实体编号在它周围读到的车厢里找；不是列车车厢为空。
    private static Optional<CreateRailways.Car> carOf(CreateCompat compat, ClientLevel level, LocalPlayer viewer,
                                                       Entity vehicle) {
        return compat.trainCars(level, viewer, vehicle.getBoundingBox().inflate(2)).stream()
                .filter(car -> car.entityId() == vehicle.getId()).findFirst();
    }

    /** 走到那节车厢的空座位旁坐上去。 */
    private final class SitInCar implements Action {
        /** 离座位这么近（格）才伸手坐。 */
        private static final double SEAT_REACH = 3.5;
        /** 点了座位等这么多刻没坐上就换一个。 */
        private static final int SIT_WAIT_TICKS = 40;
        /** 最多试几个座位。 */
        private static final int MAX_SEATS = 3;

        private final Entity vehicle;
        private final Permissions permissions;
        private Action approach;
        private long sitAt = Long.MIN_VALUE / 2;
        private int seatsTried;

        SitInCar(Entity vehicle, Permissions permissions) {
            this.vehicle = vehicle;
            this.permissions = permissions;
        }

        // 每刻：坐上了就完；车厢没了、车在动、没空座如实失败；够不着座位先走过去；够得着就看着座位右键，等它坐上。
        @Override
        public ActionStatus tick(TickContext tick) {
            PlayerContext context = services.context().get();
            LocalPlayer player = context == null ? null : context.localPlayer();
            if (player == null || context.input() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在，坐不了列车"));
            }
            Optional<CreateRailways.Car> found = carOf(compat, context.level(), player, vehicle);
            if (found.isEmpty()) {
                return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "他坐的那节车厢不见了"));
            }
            CreateRailways.Car car = found.get();
            if (car.carriesPlayer()) return ActionStatus.done();
            if (!car.still()) {
                return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "「" + car.trainName() + "」在开，不跳上开着的车"));
            }
            if (approach != null) {
                ActionStatus status = approach.tick(tick);
                if (status instanceof ActionStatus.Running) return status;
                approach.close();
                approach = null;
                if (status instanceof ActionStatus.Failed failed) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                            "走不到「" + car.trainName() + "」的座位旁：" + failed.problem().message()));
                }
            }
            long now = context.level().getGameTime();
            if (now - sitAt < SIT_WAIT_TICKS) return ActionStatus.running();
            if (seatsTried >= MAX_SEATS) {
                return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "对着「" + car.trainName() + "」的座位右键了 " + MAX_SEATS + " 次，都没坐上"));
            }
            Optional<CreateRailways.Seat> seat = car.freeSeats().stream()
                    .min(Comparator.comparingDouble(free -> free.world().distanceToSqr(player.getEyePosition())));
            if (seat.isEmpty()) {
                return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "他那节车厢已经没有空座位了"));
            }
            if (player.getEyePosition().distanceTo(seat.get().world()) > SEAT_REACH) {
                approach = services.approach().toward(ApproachTarget.ofBlock(BlockPos.containing(seat.get().world())),
                        permissions);
                return ActionStatus.progressed();
            }
            context.input().lookAt(player, seat.get().world());
            compat.sitOnTrain(car.entityId(), seat.get().local());
            seatsTried++;
            sitAt = now;
            return ActionStatus.progressed();
        }

        @Override
        public void pause() {
            if (approach != null) approach.pause();
        }

        @Override
        public void close() {
            if (approach != null) {
                approach.close();
                approach = null;
            }
        }

        @Override
        public Interruptibility interruptibility() {
            return approach != null ? approach.interruptibility() : Interruptibility.WORKING;
        }

        @Override
        public String describe() {
            return approach != null ? "走到列车座位旁" : "坐上他那节车厢的座位";
        }
    }
}
