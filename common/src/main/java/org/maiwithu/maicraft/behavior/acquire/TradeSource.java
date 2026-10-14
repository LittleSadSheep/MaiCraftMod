// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.worldmemory.RemembersRegions;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 交易的来源：和附近已加载的村民与流浪商人换东西。
 *
 * <p>村里的村民各做各行，要什么找做这行的。候选只有成年、有业的村民与流浪商人——
 * 无业和傻子的开不出交易界面；有名字、拴着绳、放在船或矿车里的，以及角色记住的玩家地盘里的，
 * 是别人的，不去搭话。圈着不算：村民本来就住在屋里、围栏边上，交易所也是为了交易才圈起来的。报价只有开了界面才看得见，所以问价时如实报"有多少要开了才知道"，
 * 排在数量明确的来源后面。村子在哪归勘察与出行：只跟已经加载的村民交易，不自己跑图找村子。
 *
 * <p>交易不伤人也不改方块，不需要专门的许可；报价收稀有消耗品的照 use_rare_items 挡，
 * 付款不够按同一套拿东西的办法先去凑（深度上限与防环由引擎统一把守）。
 */
public final class TradeSource implements ItemSource {

    /** 找村民的半径：与找箱子的一样，再远就不算顺手能买。 */
    public static final int SEARCH_RADIUS_BLOCKS = 64;
    /** 走到村民跟前、点开、一连买、关上，大约几个动作：比开一只箱子多几次来回。 */
    private static final int ACTIONS_PER_VISIT = 6;

    private final ScansMerchants scans;
    private final ReadsCreatureSituation creatures;
    private final RemembersRegions regions;
    private final Protection protection;
    private final TradesWithMerchants trades;

    public TradeSource(ScansMerchants scans, ReadsCreatureSituation creatures, RemembersRegions regions,
            Protection protection, TradesWithMerchants trades) {
        this.scans = scans;
        this.creatures = creatures;
        this.regions = regions;
        this.protection = protection;
        this.trades = trades;
    }

    @Override public String describe() {
        return "和村民交易";
    }

    @Override public AcquireVia via() {
        return AcquireVia.TRADE;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        List<ScansMerchants.MerchantSpot> merchants = scans.merchants(context.characterAt(),
                searchRadius(context));
        List<ScansMerchants.MerchantSpot> qualified = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        for (ScansMerchants.MerchantSpot merchant : merchants) {
            String refusal = refuses(merchant, context);
            if (refusal == null) {
                qualified.add(merchant);
            } else {
                excluded.add(refusal);
            }
        }
        if (qualified.isEmpty()) {
            // 都试完还不够时按看到的原因如实结束：没有合格村民、被排除的各占一条，不统一叫找不到。
            String summary = merchants.isEmpty()
                    ? "已加载的范围里没有能做买卖的村民或流浪商人"
                    : "附近的村民里没有开得出交易界面又碰得的（" + String.join("；", distinct(excluded)) + "）";
            return new SourceQuote.Unavailable(describe(), summary + "；要买东西得先走到有村民的地方");
        }
        double nearest = nearestDistance(qualified.get(0), context);
        return new SourceQuote.Offer(describe(), SourceQuote.Offer.UNKNOWN_COUNT,
                new AcquisitionCost(nearest, ACTIONS_PER_VISIT),
                "有几件货、收什么价，要开了他的界面才知道；买了他会临时抬价，抬了就换下一条",
                String.valueOf(qualified.get(0).entityId()));
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        // 动手前重新扫一遍：问价到动手之间村民可能走远了、变幼年了，以现场为准。
        List<ScansMerchants.MerchantSpot> merchants = scans.merchants(context.characterAt(),
                searchRadius(context));
        List<ScansMerchants.MerchantSpot> qualified = merchants.stream()
                .filter(merchant -> refuses(merchant, context) == null).toList();
        if (qualified.isEmpty()) {
            return Optional.empty();
        }
        return trades.trade(qualified, request, context.permissions());
    }

    /** 这只村民去不得的原因；去得的给 null。先看开不开得出界面，再看是不是别人的。 */
    private String refuses(ScansMerchants.MerchantSpot merchant, SourceContext context) {
        if (merchant.baby()) {
            return "幼年的" + merchant.describe() + "开不出交易界面，不搭话";
        }
        if (!merchant.opensTrading()) {
            return merchant.describe() + "没有做买卖的营生，开不出交易界面，不搭话";
        }
        // 有名字、拴着绳、被人装进船或矿车：是别人的，不搭话。圈着不算——村民住在屋里、围栏边上，
        // 实机 10-14 村里的农民站在屋旁被当成"圈着"，拿着绿宝石一个面包也买不到。地盘归记住的区域和受保护地标管。
        var situation = creatures.situationOf(merchant.uuid())
                .filter(found -> found.named() || found.tamed() || found.leashed() || found.inVehicle());
        if (situation.isPresent()) {
            return merchant.describe() + "有名字或被拴着，是别人的，不搭话";
        }
        for (var region : regions.regions()) {
            if (region.contains(merchant.at())) {
                return merchant.describe() + "在记住的区域「" + region.name() + "」里，是别人的地盘";
            }
        }
        var landmark = protection.protectedLandmarkHit(merchant.at(),
                context.permissions().protectedLandmarks());
        if (landmark.isPresent()) {
            return merchant.describe() + "在受保护的地标「" + landmark.get() + "」旁";
        }
        return null;
    }

    /** 这次搜多大范围：任务给了半径就在这个范围里找（给了就不越界），没给用来源自己的默认。 */
    private static int searchRadius(SourceContext context) {
        return context.radiusBlocks() == null ? SEARCH_RADIUS_BLOCKS : context.radiusBlocks();
    }

    private static double nearestDistance(ScansMerchants.MerchantSpot merchant, SourceContext context) {
        var here = context.characterAt();
        var at = merchant.at();
        double dx = at.x() - here.x();
        double dy = at.y() - here.y();
        double dz = at.z() - here.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<String> distinct(List<String> lines) {
        List<String> seen = new ArrayList<>();
        for (String line : lines) {
            if (!seen.contains(line)) seen.add(line);
        }
        return seen;
    }
}
