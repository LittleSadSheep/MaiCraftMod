// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.List;
import java.util.Objects;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.travel.spi.OfferSink;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.behavior.travel.spi.TravelMode;
import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelRequest;
import org.maiwithu.maicraft.behavior.travel.spi.TravelVia;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 划原版船过水（travel 的 via=boat）：原版自带的出行方式，只在主世界用得上。
 * 隔着湖、海的目的地，走路算不出干路或绕得很远时，真人会掏出船放到水边、坐上去对着对岸划、靠岸跳下来接着走；
 * 岸边停着没人坐的船就直接用它；身上没船、岸边也没船，不为过一次水现合成船。
 *
 * <p>人正坐在原版船上时这一段非它不可：先开到最近的岸下船，再交给行程接着走。
 * 报价与执行按报价里的打算（放船站位、靠岸点）走；一段没划成（船碎了、被人拉下船、连着倒退转不过去）
 * 如实说停在哪、为什么，行程从落点重新规划。路上饿了、被打照常让生存需求打断，船停原地，回来接着划。
 */
public final class BoatCrossing implements TravelMode {

    /** 取值与一句说明，自报进 travel 的 via 参数表。 */
    public static final TravelVia VIA = new TravelVia("boat",
            "自己放船划过水面：隔着水时放船坐上划到对岸，下船接着走（身上要有船物品，或岸边停着没人坐的船）");

    /** 找岸边停着的没人坐的船，看多远（格）。 */
    static final int BOAT_SEARCH_DISTANCE = 32;
    /** 找放船站位、靠岸点各备几个、几个。 */
    static final int MAX_LAUNCH_SPOTS = 3;
    /** 放船、上船、下船一共估多少刻。 */
    static final long OVERHEAD_TICKS = 20L * 6;

    private final DimensionTravelServices services;

    /** 报价时定下的这一段打算：报价与开跑之间世界可能又变，开跑时照打算先核对现场。 */
    record Plan(BlockPos launchFeet, BlockPos launchWater, BlockPos existingBoat, List<BlockPos> landings,
                String boatItem, BlockPos destination) {}

    /** @param services 跨维度出行的现场部件：读格、走到、换主手、当刻角色上下文（这里只用它读格与读现场） */
    public BoatCrossing(DimensionTravelServices services) {
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public TravelVia via() {
        return VIA;
    }

    // 船是主世界的东西：下界是岩浆海，末地没有能划的活水，别的维度里不为它绕进行程规划。
    @Override public boolean worksIn(String dimension) {
        return DimensionRoute.OVERWORLD.equals(dimension);
    }

    // 人正坐在原版船上时这一段非它不可：走路到不了船上的人，先开到最近的岸下船。
    @Override public boolean claims(TravelRequest request) {
        return claimsFor(request.dimension(), ridingVanillaBoat());
    }

    /** 非它不可的判断（纯函数部分）：在主世界、正坐在原版船里，别的办法都够不着。 */
    static boolean claimsFor(String dimension, boolean ridingVanillaBoat) {
        return ridingVanillaBoat && DimensionRoute.OVERWORLD.equals(dimension);
    }

    /**
     * 只读：不在主世界、没隔水、没有船，一样一样说清；都齐了按放船站位与靠岸点给一份报价。
     * 人正坐在船上时跳过隔水勘测，直接给"开到最近岸边下船"的一份。
     */
    @Override public void offer(TravelRequest request, OfferSink sink) {
        if (!DimensionRoute.OVERWORLD.equals(request.dimension())) {
            sink.decline(Problem.Kind.UNSUPPORTED, "船只在主世界用得上，这里是 " + request.dimension());
            return;
        }
        Function<BlockPos, Optional<BlockState>> read =
                services.world()::stateAt;
        boolean riding = ridingVanillaBoat();
        if (riding) {
            offerRidingToShore(request, read, sink);
            return;
        }
        if (request.to() == null) {
            sink.decline(Problem.Kind.INVALID_PARAMETER, "划船要给 target 说去哪；只想下船给不出方向");
            return;
        }
        WaterCrossing.Survey survey = WaterCrossing.between(read, request.from(), request.to());
        switch (survey.answer()) {
            case NO_WATER -> {
                sink.decline(Problem.Kind.UNSUPPORTED, "从 "
                        + request.from().toShortString() + " 到 " + request.to().toShortString()
                        + " 之间没隔着连片的活水，划船帮不上");
                return;
            }
            case WATER_AT_START -> {
                sink.decline(Problem.Kind.UNSUPPORTED, "脚下就是水，走路会泅渡，不用放船");
                return;
            }
            case WATER_AT_END -> {
                sink.decline(Problem.Kind.UNREACHABLE, "目的地那一头到 "
                        + request.to().toShortString() + " 还是水，划过去也上不了岸");
                return;
            }
            case FROZEN -> {
                sink.decline(Problem.Kind.UNREACHABLE, "中间的水面冻住了，船放不下去");
                return;
            }
            default -> { }
        }
        WaterCrossing.Span span = survey.span();
        Optional<BlockPos> freeBoat = nearestFreeBoat(services.context().get(), request.from());
        Optional<String> carried = carriedBoatItem();
        if (freeBoat.isEmpty() && carried.isEmpty()) {
            sink.decline(Problem.Kind.UNREACHABLE, "身上没有船物品，" + BOAT_SEARCH_DISTANCE
                    + " 格内也没有停着的空船；不为过一次水现合成船");
            return;
        }
        List<BoatLaunchSpots.Spot> spots = BoatLaunchSpots.around(read, span.nearShore(), request.from(),
                span.farShore(), MAX_LAUNCH_SPOTS);
        if (spots.isEmpty()) {
            sink.decline(Problem.Kind.UNREACHABLE, "出发侧岸边（" + span.nearShore().toShortString()
                    + " 附近）没有能放船的站位：脚下站不住或前面够不着水面");
            return;
        }
        List<BlockPos> landings = BoatLandings.around(read, span.farShore(), request.to());
        if (landings.isEmpty()) {
            sink.decline(Problem.Kind.UNREACHABLE, "对岸（" + span.farShore().toShortString()
                    + " 附近）没有紧挨水面的靠岸点，划过去上不了岸");
            return;
        }
        BlockPos launchWater = spots.getFirst().water();
        long ticks = WaterCrossing.estimateTicks(request.from(), span, request.to());
        String needs = carried.isPresent() ? "身上的一条船（" + carried.get() + "）"
                : "岸边 " + freeBoat.get().toShortString() + " 停着的没人坐的船";
        String changes = carried.isPresent()
                ? "放下一条船（身上少一条船）；船划完留在岸边不收"
                : "用岸边那条船，划完留在对岸";
        String hint = "放船站位 " + spots.getFirst().feet().toShortString() + "，靠岸点 "
                + landings.getFirst().toShortString() + (carried.isEmpty()
                        ? "，用岸边那条船" : "，用身上的船");
        Plan plan = new Plan(spots.getFirst().feet(), launchWater, freeBoat.orElse(null), landings,
                carried.orElse(null), request.to());
        TravelOffer offer = new TravelOffer(VIA, spots.getFirst().feet(), landings.getFirst(), ticks,
                List.of(needs), List.of(changes), hint);
        plans.put(offer, plan);
        sink.offer(offer);
    }

    // 人在船里：跳过隔水勘测，直接给"开到最近的岸、下船"的一份报价；交还行程后从岸上接着走。
    private void offerRidingToShore(TravelRequest request,
            Function<BlockPos, Optional<BlockState>> read, OfferSink sink) {
        BlockPos here = request.from();
        Optional<BlockPos> shore = nearestShore(read, here, request.to() == null ? here : request.to());
        if (shore.isEmpty()) {
            sink.decline(Problem.Kind.UNREACHABLE, "坐在船里，可 " + SHORE_SEARCH_DISTANCE
                    + " 格内没看到能上岸的实地（或那一片没加载）");
            return;
        }
        BlockPos shoreAt = shore.get();
        List<BlockPos> landings = BoatLandings.around(read, shoreAt, request.to() == null ? shoreAt : request.to());
        if (landings.isEmpty()) {
            sink.decline(Problem.Kind.UNREACHABLE, "岸边 " + shoreAt.toShortString()
                    + " 附近没有紧挨水面的靠岸点，开过去也下不了船");
            return;
        }
        BlockPos landing = landings.getFirst();
        long ticks = Math.round(Math.sqrt(here.distSqr(landing)) / 8.0 * 20) + OVERHEAD_TICKS / 2;
        TravelOffer offer = new TravelOffer(VIA, here, landing, ticks, List.of(),
                List.of("把船开到岸边停下，人下船；船留在岸边"),
                "先开到 " + landing.toShortString() + " 下船");
        plans.put(offer, new Plan(null, null, null, landings, null, request.to()));
        sink.offer(offer);
    }

    @Override public TravelLeg start(TravelRequest request, TravelOffer offer) {
        Plan plan = plans.remove(offer);
        if (plan == null) {
            // 规划隔了几刻才挑中这份报价，内存里的打算没跟上的按报价里的线索重算一份。
            plan = replan(request, offer);
        }
        return new BoatCrossingLeg(plan, request.permissions(), services);
    }

    // 丢了打算时按报价的起终点重算：站位与靠岸点重新挑，挑不出这段就走不通，让行程如实说。
    private Plan replan(TravelRequest request, TravelOffer offer) {
        Function<BlockPos, Optional<BlockState>> read = services.world()::stateAt;
        List<BlockPos> landings = BoatLandings.around(read, offer.to(), request.to() == null ? offer.to() : request.to());
        return new Plan(offer.from(), offer.from(), null,
                landings.isEmpty() ? List.of(offer.to()) : landings, null, request.to());
    }

    /** 出发点最近的一柱实地：从船的位置一圈圈往外找，找带着往目的地那边偏。 */
    static Optional<BlockPos> nearestShore(Function<BlockPos, Optional<BlockState>> read,
            BlockPos at, BlockPos toward) {
        for (int r = 0; r <= SHORE_SEARCH_DISTANCE; r++) {
            BlockPos best = null;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos cell = new BlockPos(at.getX() + dx, at.getY(), at.getZ() + dz);
                    if (WaterCrossing.surfaceKind(read, cell) != WaterCrossing.Kind.LAND) continue;
                    if (best == null || cell.distSqr(toward) < best.distSqr(toward)) best = cell;
                }
            }
            if (best != null) return Optional.of(best);
        }
        return Optional.empty();
    }

    /** 人此刻正坐在原版船里吗（普通船或运输船）；读不到角色给假。 */
    private boolean ridingVanillaBoat() {
        PlayerContext context = services.context().get();
        return context != null && context.localPlayer() != null
                && context.localPlayer().getVehicle() instanceof Boat;
    }

    /** 附近没人坐的船：位置最近的先给；读不到现场给空。 */
    static Optional<BlockPos> nearestFreeBoat(PlayerContext context, BlockPos from) {
        if (context == null || context.localPlayer() == null) return Optional.empty();
        Boat best = null;
        for (Boat boat : context.level().getEntitiesOfClass(Boat.class,
                new AABB(from.getX() - BOAT_SEARCH_DISTANCE, from.getY() - 16, from.getZ() - BOAT_SEARCH_DISTANCE,
                        from.getX() + BOAT_SEARCH_DISTANCE, from.getY() + 16, from.getZ() + BOAT_SEARCH_DISTANCE))) {
            if (!boat.isAlive() || !boat.getPassengers().isEmpty()) continue;
            if (best == null || boat.blockPosition().distSqr(from) < best.blockPosition().distSqr(from)) best = boat;
        }
        return best == null ? Optional.empty() : Optional.of(best.blockPosition());
    }

    /** 身上带的船物品（各种木头的船、竹筏、运输船都算）：有就给物品 id，划完这条就少一条。 */
    private Optional<String> carriedBoatItem() {
        for (BackpackStack stack : services.world().carriedStacks()) {
            if (isBoatItem(stack.itemId())) return Optional.of(stack.itemId());
        }
        return Optional.empty();
    }

    /** 是不是船物品：各种木头的船与竹筏（含运输船）都叫 *_boat / *_raft。 */
    static boolean isBoatItem(String itemId) {
        int slash = itemId.indexOf(':');
        String path = slash < 0 ? itemId : itemId.substring(slash + 1);
        return path.endsWith("_boat") || path.endsWith("_raft");
    }

    /** 找最近的岸最多看多远（格）：坐在船里四周该看得见岸，再远就是在大海中央。 */
    static final int SHORE_SEARCH_DISTANCE = 48;

    /** 报价与开跑之间传递打算：规划挑中哪份，开跑就照哪份走。 */
    private final Map<TravelOffer, Plan> plans = new IdentityHashMap<>();
}
