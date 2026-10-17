// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.travel.spi.OfferSink;
import org.maiwithu.maicraft.behavior.travel.spi.TravelLeg;
import org.maiwithu.maicraft.behavior.travel.spi.TravelMode;
import org.maiwithu.maicraft.behavior.travel.spi.TravelOffer;
import org.maiwithu.maicraft.behavior.travel.spi.TravelRequest;
import org.maiwithu.maicraft.behavior.travel.spi.TravelVia;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 坐原版矿车沿铺好的铁轨走一段（travel 的 via=minecart）：原版自带的出行方式，和折跃门一样由行程规划比报价。
 * 起点和目的地旁各有一条铁轨、两格之间按轨形接口连得成一条线路、沿途的通电动力铁轨或下坡够把车送到头，
 * 且身上有矿车或起点轨道旁停着一辆没人坐的矿车，才出报价。铺轨、放动力铁轨、给红石通电不归这里，
 * 线路没这些就不出报价并说一句原因；人正坐在矿车里时这一段非它不可，先坐到头或下车——
 * 连得出去就坐到头，连不出去也给一份就近下车的报价。
 */
public class MinecartMode implements TravelMode {

    /** 取值与一句说明，自报进 travel 的 via 参数表。 */
    public static final TravelVia VIA = new TravelVia("minecart",
            "坐原版矿车：沿铺好的铁轨坐到离目的地最近的轨道格下车（要有通电的动力铁轨或一路下坡，矿车自己带或轨道上停着一辆）");

    /** 扫已加载区铁轨的范围（格）与一轮最多认几格。 */
    static final int SEARCH_DISTANCE = 64;
    static final int MAX_RAILS = 512;
    /** 一条线路最多看多少格：已加载区里连成的线再长也不替它跑断，超出算连不成一条。 */
    static final int MAX_ROUTE = 1024;
    /** 走到起点、放车、上车、下车这些零头估多少刻。 */
    static final long OVERHEAD_TICKS = 20L * 40;
    /** 矿车物品与实体的身份。 */
    static final String CART_ITEM = "minecraft:minecart";
    /** 起点轨道旁这么近（格）停着的空矿车直接用。 */
    static final double PARKED_CART_RANGE = 8.0;
    /** 世界记忆里记停过矿车的地点叫这个名字，乘车段下车时也用这个名字记。 */
    static final String REMEMBERED_CART = "停着的矿车";
    /** 记过的那辆矿车离起点这么远（格，平方）以内才算这条线用得上的车（上车段找车的范围）。 */
    static final double BOARDABLE_RANGE_SQR = 16.0 * 16.0;
    /** 记过的矿车位置允许它自己溜出去这么远（格）再核实。 */
    static final double REMEMBERED_CHECK_RANGE = 3.0;
    /** 骑乘中连不出线路时的兜底报价：就近下车，估这么多刻。 */
    static final long DISMOUNT_TICKS = 20L * 10;

    private final RailScout scout;
    private final DimensionTravelServices services;

    /** @param services 跨维度出行那份现场部件：扫铁轨靠 {@code scout}，读格、走到、换手、用方块、世界记忆都在里面 */
    public MinecartMode(RailScout scout, DimensionTravelServices services) {
        this.scout = Objects.requireNonNull(scout, "scout");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public TravelVia via() {
        return VIA;
    }

    // 只读：线路这一刻没读完就什么都不答，规划下一刻再问；读完了各给一份报价或一句送不了的原因。
    @Override public void offer(TravelRequest request, OfferSink sink) {
        if (request.to() == null) {
            sink.decline(Problem.Kind.UNSUPPORTED, "坐矿车要有目的地（矿车不认路，只沿着连成的线路走）");
            return;
        }
        RailScout.Scan scan = scout.scanRails(SEARCH_DISTANCE, MAX_RAILS);
        if (!scan.complete()) return;
        RailLine.ReadsCells cells = services.world()::stateAt;
        Optional<BlockPos> start = nearestRail(scan.hits(), request.from());
        Optional<BlockPos> end = nearestRail(scan.hits(), request.to());
        Optional<List<BlockPos>> route = start.isPresent() && end.isPresent()
                ? RailLine.route(cells, start.get(), end.get(), MAX_ROUTE) : Optional.empty();
        // 人已经在矿车里：这一段非它不可，先坐到头或下车。连得出去就坐到头（供电够不够不再挑，
        // 车动了坐到停、没动就下车）；连不出去（目的地旁没轨、线路有断口、身边没轨）也给一份就近下车的报价。
        if (ridingMinecart()) {
            if (route.isPresent()) {
                long ticks = Math.round(route.get().size() / 8.0 * 20) + OVERHEAD_TICKS;
                sink.offer(new TravelOffer(VIA, start.get(), end.get(), ticks, List.of(), List.of(), null));
            } else {
                sink.offer(new TravelOffer(VIA, request.from(), request.from(), DISMOUNT_TICKS,
                        List.of(), List.of(), "就近下车"));
            }
            return;
        }
        if (start.isEmpty()) {
            sink.decline(Problem.Kind.NOT_FOUND, "附近 " + SEARCH_DISTANCE + " 格的已加载区没有铁轨");
            return;
        }
        if (end.isEmpty()) {
            sink.decline(Problem.Kind.NOT_FOUND, "目的地 " + SEARCH_DISTANCE + " 格内没有铁轨，坐不了车");
            return;
        }
        if (route.isEmpty()) {
            sink.decline(Problem.Kind.NOT_FOUND, "起点与目的地旁的铁轨连不成一条线路（轨形接不上，或中间有断口）");
            return;
        }
        RailLine.Delivery delivery = RailLine.delivery(route.get(), cells);
        if (delivery instanceof RailLine.Delivery.Blocked blocked) {
            sink.decline(Problem.Kind.UNREACHABLE, blocked.reason() + "；不铺轨、不放动力铁轨、不给红石通电");
            return;
        }
        boolean parkedNearby = usableParkedCart(services.context().get(), start.get(), services.memory()).isPresent();
        if (!parkedNearby && !carriedCart()) {
            sink.decline(Problem.Kind.NOT_FOUND, "身上没有矿车，起点轨道旁 " + (int) PARKED_CART_RANGE
                    + " 格内也没有停着的空矿车（不现合成）");
            return;
        }
        long ticks = Math.round(route.get().size() / 8.0 * 20) + OVERHEAD_TICKS;
        sink.offer(new TravelOffer(VIA, start.get(), end.get(), ticks,
                List.of(parkedNearby ? "起点轨道旁停着的空矿车" : "身上的一辆矿车"),
                List.of(), null));
    }

    // 人正坐在原版矿车里：这一段非它不可，先坐到头或下车，走路接不上。
    @Override public boolean claims(TravelRequest request) {
        return ridingMinecart();
    }

    // 开跑这一段：起点终点直接取报价里的两格。推车方向按线路第一段算（有界只读，把报价时认过的线路再看一眼）；
    // 算不出（线路刚断、那一段没加载）给空，乘车段按整条线路的主轴推，失败说明里写清方向没把握。
    @Override public TravelLeg start(TravelRequest request, TravelOffer offer) {
        Direction firstMove = RailLine.route(services.world()::stateAt, offer.from(), offer.to(), MAX_ROUTE)
                .filter(cells -> cells.size() >= 2)
                .map(cells -> RailLine.directionOf(cells.get(1).subtract(cells.get(0))))
                .orElse(null);
        return new MinecartRide(offer.from(), offer.to(), request.permissions(), services, firstMove);
    }

    // 离谁最近的一格铁轨：扫描命中先到现场复核这一格现在还是不是铁轨（可能刚被挖掉）。
    private Optional<BlockPos> nearestRail(List<BlockPos> hits, BlockPos around) {
        Optional<BlockPos> nearest = Optional.empty();
        for (BlockPos pos : hits) {
            if (services.world().stateAt(pos).map(RailLine::isRail).orElse(false)
                    && (nearest.isEmpty() || pos.distSqr(around) < nearest.get().distSqr(around))) {
                nearest = Optional.of(pos);
            }
        }
        return nearest;
    }

    /** 身上带着矿车吗（主背包与快捷栏）。 */
    private boolean carriedCart() {
        for (BackpackStack stack : services.world().carriedStacks()) {
            if (CART_ITEM.equals(stack.itemId()) && stack.count() > 0) return true;
        }
        return false;
    }

    /** 起点轨道旁停着的空矿车：普通矿车（不含运输漏斗 TNT 动力车）、没人坐、还在。 */
    Optional<AbstractMinecart> parkedCart(BlockPos startCell) {
        PlayerContext context = services.context().get();
        return parkedCart(context, startCell, PARKED_CART_RANGE);
    }

    /**
     * 这条线用得上的停着的空矿车：先看起点轨道旁现场停的；没有再查世界记忆里记过的"停着的矿车"，
     * 到现场核实它还在、没人坐、离起点近得上车（上车段就按这个范围找车）才认。
     */
    static Optional<AbstractMinecart> usableParkedCart(PlayerContext context, BlockPos startCell, WorldMemory memory) {
        Optional<AbstractMinecart> parked = parkedCart(context, startCell, PARKED_CART_RANGE);
        if (parked.isPresent() || memory == null) return parked;
        return memory.place(REMEMBERED_CART).flatMap(spot -> {
            BlockPos remembered = new BlockPos(spot.x(), spot.y(), spot.z());
            if (remembered.distSqr(startCell) > BOARDABLE_RANGE_SQR) return Optional.empty();
            return parkedCart(context, remembered, REMEMBERED_CHECK_RANGE);
        });
    }

    /** 附近停着的空矿车（普通矿车、没人坐、还在）；测试与乘车段也用它认车。 */
    static Optional<AbstractMinecart> parkedCart(PlayerContext context, BlockPos cell, double range) {
        if (context == null || context.level() == null) return Optional.empty();
        AABB around = new AABB(cell).inflate(range);
        for (AbstractMinecart cart : context.level().getEntitiesOfClass(AbstractMinecart.class, around)) {
            if (cart.getType() == EntityType.MINECART && cart.isAlive() && cart.getPassengers().isEmpty()) {
                return Optional.of(cart);
            }
        }
        return Optional.empty();
    }

    /** 人此刻正坐在一辆原版矿车里。 */
    boolean ridingMinecart() {
        PlayerContext context = services.context().get();
        return context != null && context.localPlayer() != null
                && context.localPlayer().getVehicle() instanceof AbstractMinecart cart
                && cart.getType() == EntityType.MINECART;
    }
}
