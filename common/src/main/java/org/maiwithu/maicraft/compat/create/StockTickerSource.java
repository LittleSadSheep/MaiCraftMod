// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 机械动力物流网络的库存请求器当拿东西的一条途径（via=stock_ticker，所有者 2026-10-11 批）：
 * 找附近旁边坐着库存管理员、自家的请求器，定取货口（请求器 32 格内写了地址、开着收包裹、自家的包裹端口，
 * 上次用过的地址优先，否则最近的），按同步过来的现货报价（五秒内算数，过时报不知道有多少）；
 * 动手：右键管理员开请求界面、下单寄到取货口、到取货口等包裹、取出来拆开（见 {@link StockOrdering}）。只要现货。
 */
final class StockTickerSource implements ItemSource {

    static final AcquireVia VIA = new AcquireVia("stock_ticker",
            "机械动力物流网络：右键库存请求器旁的管理员下单，包裹寄到附近写了地址的取货口，拿出来拆开");
    /** 找请求器的默认半径（格）。 */
    private static final int SEARCH_RADIUS = 64;
    /** 取货口离请求器最远这么多格。 */
    static final int PICKUP_RADIUS = 32;
    /** 现货摘要这么多刻以内算数（五秒）。 */
    static final int FRESH_TICKS = 100;
    /** 一趟的动作数：走过去、开界面、下单、走到取货口、开、取、拆。 */
    private static final int ACTIONS = 7;

    private final CreateCompat compat;
    private final PlayerServices services;

    StockTickerSource(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public String describe() {
        return "库存请求器";
    }

    @Override public AcquireVia via() {
        return VIA;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        PlayerContext player = services.context().get();
        if (player == null || player.level() == null) return new SourceQuote.Unavailable(describe(), "角色不在世界里");
        ClientLevel level = player.level();
        WorldPosition here = context.characterAt();
        BlockPos at = new BlockPos(here.x(), here.y(), here.z());
        int radius = context.radiusBlocks() == null ? SEARCH_RADIUS : context.radiusBlocks();
        Predicate<String> wanted = itemId -> request.wanted().matches(itemId, services.itemTags().tagsOf(itemId));
        Choice choice = choose(level, at, radius, wanted, context.permissions());
        return switch (choice) {
            case Choice.None none -> new SourceQuote.Unavailable(describe(), none.reason());
            case Choice.Found found -> found.ticker().ticksSinceUpdate() <= FRESH_TICKS
                    ? stockedOffer(found, wanted, request.count(), at)
                    : new SourceQuote.Offer(describe(), SourceQuote.Offer.UNKNOWN_COUNT, cost(found, at),
                            "这台请求器的现货很久没看过，到了再看；包裹寄到 " + found.pickup().address(), found.hint());
        };
    }

    private SourceQuote stockedOffer(Choice.Found found, Predicate<String> wanted, int count, BlockPos at) {
        int stock = found.ticker().stock().entrySet().stream().filter(entry -> wanted.test(entry.getKey()))
                .mapToInt(Map.Entry::getValue).sum();
        if (stock <= 0) return new SourceQuote.Unavailable(describe(), "库存请求器同步来的现货里没有");
        return new SourceQuote.Offer(describe(), Math.min(stock, count), cost(found, at),
                "下单后包裹顺着物流网络送到 " + found.pickup().address() + "，要等它到", found.hint());
    }

    // 走到请求器、下单、到取货口取的代价，再加上站在取货口等包裹的时间：包裹不是立等可取，
    // 箱子里、身上现成的排在前面。
    private static AcquisitionCost cost(Choice.Found found, BlockPos at) {
        return new AcquisitionCost(Math.sqrt(found.ticker().pos().distSqr(at)), ACTIONS)
                .withWait(ParcelWait.EXPECTED_TICKS);
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        PlayerContext player = services.context().get();
        if (player == null || player.level() == null) return Optional.empty();
        WorldPosition here = context.characterAt();
        Predicate<String> wanted = itemId -> request.wanted().matches(itemId, services.itemTags().tagsOf(itemId));
        // 问价与动手之间现场可能变了：重新挑一遍，挑不出交回空，引擎换别的路。
        Choice choice = choose(player.level(), new BlockPos(here.x(), here.y(), here.z()),
                context.radiusBlocks() == null ? SEARCH_RADIUS : context.radiusBlocks(), wanted, context.permissions());
        if (!(choice instanceof Choice.Found found)) return Optional.empty();
        return Optional.of(new StockOrdering(compat, services, found.ticker(), found.pickup(), wanted, request.count(),
                context.permissions()));
    }

    // 挑请求器：要有管理员、自家的；它附近要有取货口；按离角色远近挑第一台齐全的。
    private Choice choose(ClientLevel level, BlockPos at, int radius, Predicate<String> wanted, Permissions permissions) {
        String dimension = level.dimension().location().toString();
        List<CreateLogistics.Ticker> tickers = compat.tickers(level, at, radius).stream()
                .sorted(Comparator.comparingDouble(ticker -> ticker.pos().distSqr(at))).toList();
        if (tickers.isEmpty()) return new Choice.None("附近 " + radius + " 格内没有库存请求器");
        String lastReason = "";
        for (CreateLogistics.Ticker ticker : tickers) {
            if (ticker.keeper().isEmpty()) {
                lastReason = "请求器旁边没有坐着的库存管理员";
                continue;
            }
            if (!usable(level, dimension, ticker.pos(), permissions)) {
                lastReason = "附近的请求器不是自己或自家人的";
                continue;
            }
            List<CreateLogistics.Pickup> ports = compat.pickups(level, ticker.pos(), PICKUP_RADIUS).stream()
                    .filter(pickup -> usable(level, dimension, pickup.pos(), permissions)).toList();
            Optional<CreateLogistics.Pickup> pickup = pickupFor(ticker, ports);
            if (pickup.isEmpty()) {
                lastReason = "请求器附近没有写了地址、开着收包裹的取货口（包裹青蛙港或邮筒）";
                continue;
            }
            return new Choice.Found(ticker, pickup.get());
        }
        return new Choice.None(lastReason);
    }

    /**
     * 取货口挑哪一只，纯函数：写了地址、开着收包裹的里面，请求器上次用过的地址有对应的就用它，否则离请求器最近的。
     */
    static Optional<CreateLogistics.Pickup> pickupFor(CreateLogistics.Ticker ticker, List<CreateLogistics.Pickup> ports) {
        List<CreateLogistics.Pickup> candidates = ports.stream()
                .filter(pickup -> pickup.accepts() && !pickup.address().isBlank()).toList();
        Optional<CreateLogistics.Pickup> remembered = candidates.stream()
                .filter(pickup -> !ticker.lastAddress().isBlank() && pickup.address().equals(ticker.lastAddress()))
                .min(Comparator.comparingDouble(pickup -> pickup.pos().distSqr(ticker.pos())));
        if (remembered.isPresent()) return remembered;
        return candidates.stream().min(Comparator.comparingDouble(pickup -> pickup.pos().distSqr(ticker.pos())));
    }

    // 能不能用：问保护判断里"存储能不能取用"那一条，自己或自家人放的才行。
    private boolean usable(ClientLevel level, String dimension, BlockPos pos, Permissions permissions) {
        String blockType = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        return services.protection().mayUseStorage(new WorldPosition(pos.getX(), pos.getY(), pos.getZ(), dimension),
                blockType, permissions.protectedLandmarks());
    }

    /** 挑的结论：用哪台请求器、寄到哪个取货口；或为什么一台都用不了。 */
    sealed interface Choice {
        record Found(CreateLogistics.Ticker ticker, CreateLogistics.Pickup pickup) implements Choice {
            String hint() {
                return ticker.pos().toShortString() + "|" + pickup.pos().toShortString() + "|" + pickup.address();
            }
        }

        record None(String reason) implements Choice {
        }
    }
}
