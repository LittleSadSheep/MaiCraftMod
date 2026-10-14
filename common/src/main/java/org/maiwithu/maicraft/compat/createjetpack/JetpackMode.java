// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.createjetpack;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.navigation.FlightLandings;
import org.maiwithu.maicraft.behavior.navigation.FlightRoutes;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.behavior.travel.spi.OfferSink;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.behavior.travel.spi.TravelMode;
import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelRequest;
import org.maiwithu.maicraft.behavior.travel.spi.TravelVia;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 飞过去当一种出行方式（via=jetpack）：身上有喷气背包（穿着，或在背包里——换上算这一段的一部分）、背罐有气，
 * 目标附近有看过、能站、上面留得出两格净空的落脚处，看过的空中有一条通到那里的路，气够飞完再多 7 秒，才报价。
 * 一次只规划 64 格：目标更远就朝它的方向飞 64 格左右，先落到中途能站的地方，再从那里接着规划。
 * 起飞前身体不必在地上，空中、水里打开背包本来就能飞。
 */
final class JetpackMode implements TravelMode {

    static final TravelVia VIA = new TravelVia("jetpack", "穿上喷气背包飞过去：要背罐里有气，落到看过、能站的地方");
    /** 一次规划多远（格）。 */
    static final int REACH = 64;
    /** 落点上方留几格净空。 */
    static final int CLEARANCE = 2;
    /** 估时：巡航每刻飞多少格；起飞、下降、收拾开关一共多少刻；换上背包多少刻。 */
    static final double CRUISE_BLOCKS_PER_TICK = 0.35;
    static final int OVERHEAD_TICKS = 80;
    static final int EQUIP_TICKS = 40;
    /**
     * 贴着头顶飞（坑道、矿洞、低矮的屋里）每格按这么多刻算：喷气背包在里面得一点点挪，比走路慢得多，
     * 这样能走就走出去，走不通才在里面飞。实机 10-13 从坑道台阶底下起飞回家，在一格宽的坑道里对不准、来回晃。
     */
    static final int CRAMPED_TICKS_PER_BLOCK = 20;
    /** 气要比飞完这一段多出这么多秒。 */
    static final int AIR_MARGIN_SECONDS = 7;
    /** 找空中路最多展开多少格。 */
    static final int ROUTE_BUDGET = 60_000;

    private final CreateJetpackCompat compat;
    private final PlayerServices services;

    JetpackMode(CreateJetpackCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public TravelVia via() {
        return VIA;
    }

    // 问报价：有没有背包和气、落在哪、飞得过去没有、气够不够，一样一样看，答不上来写清卡在哪。
    @Override public void offer(TravelRequest request, OfferSink sink) {
        PlayerContext current = services.context().get();
        ClientLevel level = current == null ? null : current.level();
        LocalPlayer player = current == null ? null : current.localPlayer();
        if (level == null || player == null) return;
        Optional<JetpackControls.Pack> pack = compat.pack(player);
        if (pack.isEmpty()) {
            sink.decline(Problem.Kind.UNSUPPORTED, "身上没有喷气背包（穿着的或背包里的都没有）");
            return;
        }
        if (request.to() == null) {
            sink.decline(Problem.Kind.INVALID_PARAMETER, "飞过去要给 target 说去哪");
            return;
        }
        Predicate<BlockPos> loaded = level::isLoaded;
        Optional<Plan> plan = plan(level, loaded, request.from(), request.to(), (int) Math.ceil(Math.max(2, request.radius())));
        if (plan.isEmpty()) {
            sink.decline(Problem.Kind.UNREACHABLE, far(request.from(), request.to())
                    ? "往目标方向 " + REACH + " 格左右没看到能落脚、上面留得出净空的地方，或看过的空中飞不过去"
                    : "目标附近没看到能落脚、上面留得出净空的地方（或那一片还没加载），或看过的空中飞不过去");
            return;
        }
        int cramped = FlightRoutes.cramped(level, plan.get().route());
        long ticks = Math.round(Math.max(0, plan.get().length() - cramped) / CRUISE_BLOCKS_PER_TICK)
                + (long) cramped * CRAMPED_TICKS_PER_BLOCK + OVERHEAD_TICKS + (pack.get().worn() ? 0 : EQUIP_TICKS);
        double needSeconds = ticks / 20.0 + AIR_MARGIN_SECONDS;
        if (pack.get().secondsLeft() < needSeconds) {
            sink.decline(Problem.Kind.UNREACHABLE, "背罐里的气只够飞 " + Math.round(pack.get().secondsLeft())
                    + " 秒，这一段要 " + Math.round(needSeconds) + " 秒（含 " + AIR_MARGIN_SECONDS + " 秒余量）");
            return;
        }
        sink.offer(new TravelOffer(VIA, request.from(), plan.get().landing(), ticks,
                List.of("背罐里的气（约 " + Math.round(needSeconds) + " 秒）"),
                pack.get().worn() ? List.of() : List.of("换上胸甲槽的 " + pack.get().itemId() + "（落地后换回原来的胸甲）"),
                ""));
    }

    @Override public TravelLeg start(TravelRequest request, TravelOffer offer) {
        return new JetpackFlight(compat, services, offer.to());
    }

    /** 一段飞行的打算：落在哪、按顺序的拐弯点、一共飞多远。 */
    record Plan(BlockPos landing, List<BlockPos> route, double length) {}

    /** 落在哪、怎么飞过去：近的落在目标附近，远的先朝目标方向飞一段。 */
    static Optional<Plan> plan(ClientLevel level, Predicate<BlockPos> loaded, BlockPos from, BlockPos to, int radius) {
        Optional<BlockPos> landing = far(from, to)
                ? FlightLandings.ahead(level, loaded, from, to, REACH, CLEARANCE)
                : FlightLandings.near(level, loaded, to, radius, CLEARANCE);
        if (landing.isEmpty()) return Optional.empty();
        return route(level, loaded, from, landing.get()).map(route -> new Plan(landing.get(), route, length(route)));
    }

    /** 从这里飞到落点的空中路：在两头外扩一圈的范围里找，往上多留些高度好越过障碍。 */
    static Optional<List<BlockPos>> route(ClientLevel level, Predicate<BlockPos> loaded, BlockPos from, BlockPos landing) {
        AABB bounds = new AABB(from).minmax(new AABB(landing)).inflate(16, 0, 16)
                .expandTowards(0, 32, 0).expandTowards(0, -8, 0);
        return FlightRoutes.find(level, loaded, from, landing, bounds, ROUTE_BUDGET);
    }

    static boolean far(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dz * dz) > REACH;
    }

    static double length(List<BlockPos> route) {
        double total = 0;
        for (int i = 1; i < route.size(); i++) total += Math.sqrt(route.get(i).distSqr(route.get(i - 1)));
        return total;
    }
}
