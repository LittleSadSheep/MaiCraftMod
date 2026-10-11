// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.behavior.travel.spi.OfferSink;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.behavior.travel.spi.TravelMode;
import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelRequest;
import org.maiwithu.maicraft.behavior.travel.spi.TravelVia;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.serverlink.ClientRequest;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 坐火车当一种出行方式（via=train）：远的地方有铁路就坐车。起点附近走得到的车站、终点附近的车站、
 * 有车把两站连起来（直达或一次换乘，按服务端 create.trains 回答的各车时刻表站序），就报"走到站 + 等车 + 坐车"；
 * 换乘的一段只送到换乘站，下车后再报一次价接着坐。等车加坐车比直接走还慢，这份报价自然排在走路后面。
 */
final class TrainMode implements TravelMode {

    static final TravelVia VIA = new TravelVia("train", "坐 Create 列车：走到车站等车、找空座坐上去、到站下车");
    /** 车站离起点、终点这么远以内才算附近（格）。 */
    static final int STATION_RADIUS = 96;
    /** 估时：等车按这么多刻算（不知道几点来）；列车每刻跑多少格；走路每格多少刻。 */
    static final int WAIT_GUESS_TICKS = 600;
    static final double TRAIN_BLOCKS_PER_TICK = 0.8;
    static final double WALK_TICKS_PER_BLOCK = 20 / 4.3;
    /** 时刻表的回答这么多刻内复用，过了再问。 */
    static final int SCHEDULES_FRESH_TICKS = 200;

    private final CreateCompat compat;
    private final PlayerServices services;
    private UUID asking;
    private List<TrainRoutes.Schedule> schedules;
    private long schedulesAt = Long.MIN_VALUE;
    private String failure;

    TrainMode(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public TravelVia via() {
        return VIA;
    }

    // 问报价：时刻表还没问到先问服务端（下一刻再答）；有了就挑起点、终点附近的车站，找直达或一次换乘。
    @Override public void offer(TravelRequest request, OfferSink sink) {
        PlayerContext current = services.context().get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null) return;
        if (request.to() == null) {
            sink.decline(Problem.Kind.INVALID_PARAMETER, "坐火车要给 target 说去哪");
            return;
        }
        List<CreateRailways.Station> stations = compat.stations(level);
        List<CreateRailways.Station> starts = near(stations, request.from());
        List<CreateRailways.Station> ends = near(stations, request.to());
        if (starts.isEmpty() || ends.isEmpty()) {
            sink.decline(Problem.Kind.UNSUPPORTED, starts.isEmpty() ? "起点 " + STATION_RADIUS + " 格内没有车站"
                    : "终点 " + STATION_RADIUS + " 格内没有车站");
            return;
        }
        Optional<List<TrainRoutes.Schedule>> known = schedules(level.getGameTime());
        if (known.isEmpty()) {
            if (failure != null) sink.decline(Problem.Kind.UNSUPPORTED, failure);
            return;
        }
        Optional<List<TrainRoutes.Hop>> route = TrainRoutes.plan(starts, ends, stations, known.get());
        if (route.isEmpty()) {
            sink.decline(Problem.Kind.UNREACHABLE, "附近车站之间没有车连得上（直达和一次换乘都没有）");
            return;
        }
        List<TrainRoutes.Hop> hops = route.get();
        long ticks = Math.round(Math.sqrt(hops.getFirst().board().pos().distSqr(request.from())) * WALK_TICKS_PER_BLOCK);
        for (TrainRoutes.Hop hop : hops) {
            ticks += WAIT_GUESS_TICKS + Math.round(Math.sqrt(hop.board().pos().distSqr(hop.alight().pos()))
                    / TRAIN_BLOCKS_PER_TICK);
        }
        TrainRoutes.Hop first = hops.getFirst();
        // 换乘的这一段只送到换乘站；剩下那趟车算进估时，下车后再报一次价。
        sink.offer(new TravelOffer(VIA, request.from(), hops.getLast().alight().pos(), ticks, List.of(), List.of(),
                first.trainId() + "|" + first.board().name() + "|" + first.alight().name()));
    }

    @Override public TravelLeg start(TravelRequest request, TravelOffer offer) {
        String[] hint = offer.hint().split("\\|", 3);
        PlayerContext current = services.context().get();
        ClientLevel level = current == null ? null : current.level();
        List<CreateRailways.Station> stations = level == null ? List.of() : compat.stations(level);
        CreateRailways.Station board = byName(stations, hint[1]);
        CreateRailways.Station alight = byName(stations, hint[2]);
        return new TrainRide(compat, services, request.permissions(), hint[0], board, alight);
    }

    // 时刻表：新鲜的就用；不新鲜就问一次服务端，等回答；服务端答不了写明原因。
    private Optional<List<TrainRoutes.Schedule>> schedules(long now) {
        if (schedules != null && now - schedulesAt < SCHEDULES_FRESH_TICKS) return Optional.of(schedules);
        if (asking == null) {
            if (!services.serverReads().available(CreateCompat.TRAINS_OPERATION)) {
                failure = "服务端答不了列车时刻表（没装 MaiCraft，或所有者关掉了这个只读操作），也没装铁路导航";
                return Optional.ofNullable(schedules);
            }
            asking = services.serverReads().ask(CreateCompat.TRAINS_OPERATION, new JsonObject()).orElse(null);
            return Optional.ofNullable(schedules);
        }
        Optional<ClientRequest.Snapshot> snapshot = services.serverReads().answer(asking);
        if (snapshot.isPresent() && !snapshot.get().settled()) return Optional.ofNullable(schedules);
        asking = null;
        if (snapshot.isPresent() && snapshot.get().status() == ClientRequest.Status.SUCCEEDED) {
            schedules = TrainRoutes.parse(snapshot.get().result());
            schedulesAt = now;
            failure = null;
        } else {
            failure = "问列车时刻表没问到：" + snapshot.map(ClientRequest.Snapshot::message).orElse("请求已经退场");
        }
        return Optional.ofNullable(schedules);
    }

    private static List<CreateRailways.Station> near(List<CreateRailways.Station> stations, BlockPos at) {
        return stations.stream().filter(station -> station.pos().distSqr(at) <= (double) STATION_RADIUS * STATION_RADIUS)
                .sorted(Comparator.comparingDouble(station -> station.pos().distSqr(at))).toList();
    }

    private static CreateRailways.Station byName(List<CreateRailways.Station> stations, String name) {
        return stations.stream().filter(station -> station.name().equals(name)).findFirst()
                .orElse(new CreateRailways.Station(name, BlockPos.ZERO, ""));
    }
}
