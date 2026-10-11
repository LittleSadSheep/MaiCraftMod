// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleAssembler;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 在车站组车或拆车：车站切到组车模式、按"组装列车"一次（转向架、操纵台、外壳要先在站前的轨道上放好胶好）；
 * 拆车是停在站里的车按"拆解列车"拆回方块。走到车站跟前再按；按了只看结果、不按第二次：
 * 组车看站前出现新的车厢，没成就照车站组车界面上显示的那句原因说；拆车看车厢没了。三秒没见变化如实交代。
 */
final class StationAssembly implements VehicleAssembler {

    /** 在指的地方周围多远找车站（格）。 */
    static final int SEARCH = 8;
    /** 车站前多远以内的车厢算停在站里（格）。 */
    static final int STATION_REACH = 24;
    /** 切模式、按按钮后等多少刻。 */
    static final int MODE_TICKS = 40;
    static final int CONFIRM_TICKS = 60;

    private final CreateCompat compat;
    private final PlayerServices services;

    StationAssembly(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    // 指的地方附近 8 格内最近的车站；没有就不是这种办法管的。组车时站里已经停着车、拆车时站里没车，都是已经是要的样子。
    @Override public Optional<Run> start(Request request) {
        PlayerContext context = services.context().get();
        ClientLevel level = context == null ? null : context.level();
        LocalPlayer player = context == null ? null : context.localPlayer();
        if (level == null || player == null) return Optional.empty();
        Optional<CreateRailways.Station> station = compat.stations(level).stream()
                .filter(candidate -> candidate.pos().distSqr(request.near()) <= (double) SEARCH * SEARCH)
                .min(Comparator.comparingDouble(candidate -> candidate.pos().distSqr(request.near())));
        if (station.isEmpty() || compat.station(level, station.get().pos()) == null) return Optional.empty();
        Set<String> parked = trainsAt(level, player, station.get().pos());
        if (request.assemble() && !parked.isEmpty()) {
            return Optional.of(done(parked.iterator().next(), "车站「" + station.get().name() + "」里已经停着一趟车"));
        }
        if (!request.assemble() && parked.isEmpty()) {
            return Optional.of(done("", "车站「" + station.get().name() + "」里没有车，已经是方块了"));
        }
        return Optional.of(new Press(request, station.get(), parked));
    }

    // 停在这座车站前的车（按车厢归到车次）。
    private Set<String> trainsAt(ClientLevel level, LocalPlayer player, BlockPos station) {
        return compat.trainCars(level, player, new AABB(station).inflate(STATION_REACH)).stream()
                .map(CreateRailways.Car::trainId).filter(id -> !id.isEmpty()).collect(Collectors.toSet());
    }

    private static Run done(String train, String note) {
        Conversion conversion = new Conversion(true, false, train, -1, note);
        return new Run() {
            @Override public Conversion conversion() { return conversion; }
            @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
            @Override public String describe() { return "已经是要的样子"; }
        };
    }

    /** 走过去、切组车模式、按一次、看结果。 */
    private final class Press implements Run {

        private enum Step { APPROACH, MODE, PRESS, CONFIRM }

        private final Request request;
        private final CreateRailways.Station station;
        private final Set<String> before;
        private Step step = Step.APPROACH;
        private Action current;
        private long stepStart = Long.MIN_VALUE;
        private boolean sent;
        private String failureBefore = "";
        private Conversion conversion;

        Press(Request request, CreateRailways.Station station, Set<String> before) {
            this.request = request;
            this.station = station;
            this.before = Set.copyOf(before);
        }

        @Override public ActionStatus tick(TickContext tick) {
            PlayerContext context = services.context().get();
            ClientLevel level = context == null ? null : context.level();
            LocalPlayer player = context == null ? null : context.localPlayer();
            if (level == null || player == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在，按不了车站的按钮"));
            }
            long now = level.getGameTime();
            if (stepStart == Long.MIN_VALUE) stepStart = now;
            if (current != null) {
                ActionStatus status = current.tick(tick);
                if (status instanceof ActionStatus.Running) return status;
                current.close();
                current = null;
                if (status instanceof ActionStatus.Failed failed) return failed;
                return go(request.assemble() ? Step.MODE : Step.PRESS, now);
            }
            CreateRailways.StationState state = compat.station(level, station.pos());
            if (state == null) return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "车站读不到了"));
            return switch (step) {
                case APPROACH -> {
                    current = services.approach().toward(ApproachTarget.ofBlock(station.pos()), request.permissions());
                    yield ActionStatus.progressed();
                }
                case MODE -> mode(state, now);
                case PRESS -> press(state, now);
                case CONFIRM -> confirm(level, player, state, now);
            };
        }

        // 组车先切到组车模式（车站界面上那个按钮），等车站真的进了组车模式。
        private ActionStatus mode(CreateRailways.StationState state, long now) {
            if (state.assembling()) return go(Step.PRESS, now);
            if (!sent) {
                compat.assemblyMode(station.pos(), true);
                sent = true;
            }
            if (now - stepStart >= MODE_TICKS) {
                return finish(new Conversion(false, false, "", -1, "车站「" + station.name() + "」没进组车模式"
                        + (state.failure().isBlank() ? "（站里或站前的轨道上也许有车）" : "：" + state.failure())));
            }
            return ActionStatus.running();
        }

        private ActionStatus press(CreateRailways.StationState state, long now) {
            // 按之前车站上记着的旧原因：按了之后它没变就不当成这一次的结果。
            failureBefore = state.failure();
            if (request.assemble()) compat.assembleTrain(station.pos());
            else compat.disassembleTrain(station.pos());
            return go(Step.CONFIRM, now);
        }

        // 看结果：组车出现新的车次就成了，没成照组车界面上那句原因说；拆车看车没了。三秒没见变化照实说。
        private ActionStatus confirm(ClientLevel level, LocalPlayer player, CreateRailways.StationState state, long now) {
            Set<String> trains = trainsAt(level, player, station.pos());
            if (request.assemble()) {
                Optional<String> fresh = trains.stream().filter(train -> !before.contains(train)).findFirst();
                if (fresh.isPresent()) {
                    int cars = (int) compat.trainCars(level, player, new AABB(station.pos()).inflate(STATION_REACH)).stream()
                            .filter(car -> car.trainId().equals(fresh.get())).count();
                    return finish(new Conversion(false, true, fresh.get(), cars,
                            "在车站「" + station.name() + "」组成了一趟车，" + cars + " 节车厢"));
                }
                if (!state.failure().isBlank() && (!state.failure().equals(failureBefore) || now - stepStart >= 40)) {
                    return finish(new Conversion(false, false, "", -1, "按了组车，没组成：" + state.failure()));
                }
            } else if (trains.isEmpty()) {
                return finish(new Conversion(false, true, before.iterator().next(), -1,
                        "在车站「" + station.name() + "」把车拆回了方块"));
            }
            if (now - stepStart >= CONFIRM_TICKS) {
                return finish(new Conversion(false, false, "", -1, (request.assemble() ? "按了组车" : "按了拆车") + "，"
                        + CONFIRM_TICKS / 20 + " 秒内没见变化" + (state.failure().isBlank() ? "" : "：" + state.failure())));
            }
            return ActionStatus.running();
        }

        private ActionStatus go(Step next, long now) {
            step = next;
            stepStart = now;
            sent = false;
            return ActionStatus.progressed();
        }

        private ActionStatus finish(Conversion result) {
            conversion = result;
            return ActionStatus.done();
        }

        @Override public Conversion conversion() {
            return conversion;
        }

        @Override public void pause() {
            if (current != null) current.pause();
        }

        @Override public void close() {
            if (current != null) {
                current.close();
                current = null;
            }
        }

        @Override public Interruptibility interruptibility() {
            // 按了按钮在等结果：这时候走开看不到结局，不让不急的事插进来。
            if (step == Step.CONFIRM) return Interruptibility.UNSAFE_TO_STOP;
            return current == null ? Interruptibility.BETWEEN_ACTIONS : current.interruptibility();
        }

        @Override public String describe() {
            return switch (step) {
                case APPROACH -> "走到车站跟前";
                case MODE -> "切到组车模式";
                case PRESS -> request.assemble() ? "按组车" : "按拆车";
                case CONFIRM -> "看组成了没有";
            };
        }
    }
}
