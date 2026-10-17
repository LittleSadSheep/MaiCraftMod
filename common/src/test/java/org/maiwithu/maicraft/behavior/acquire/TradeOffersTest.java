// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.TradeOffers.Payment;
import org.maiwithu.maicraft.behavior.acquire.TradeOffers.PaymentGap;
import org.maiwithu.maicraft.behavior.acquire.TradeOffers.View;

/**
 * 报价的挑选：卖想要东西、还有存货、付款不超许可的才参与；付款件数最少的先买；
 * 缺几件买几件；付款缺口按身上的存货算。
 */
class TradeOffersTest {

    private static final WantedItem BREAD = WantedItem.ofItem("minecraft:bread");

    private static final FakeTags TAGS = new FakeTags();

    private static View offer(int index, Payment costA, Payment costB, String result, int resultCount,
            boolean outOfStock) {
        return new View(index, costA, Optional.ofNullable(costB), result, resultCount, outOfStock);
    }

    private static Payment emeralds(int count) {
        return new Payment("minecraft:emerald", count, false);
    }

    @Test
    void 只挑卖这样东西又有存货的报价() {
        List<View> offers = List.of(
                offer(0, emeralds(1), null, "minecraft:iron_pickaxe", 1, false),
                offer(1, emeralds(1), null, "minecraft:bread", 4, false),
                offer(2, emeralds(2), null, "minecraft:bread", 4, true));
        List<View> eligible = TradeOffers.eligible(offers, BREAD, TAGS, true);
        assertEquals(List.of(offers.get(1)), eligible);
    }

    @Test
    void 多条报价按付款件数最少的先买() {
        List<View> offers = List.of(
                offer(0, emeralds(3), null, "minecraft:bread", 4, false),
                offer(1, new Payment("minecraft:paper", 8, false), emeralds(2), "minecraft:bread", 4, false),
                offer(2, emeralds(2), null, "minecraft:bread", 4, false));
        assertEquals(Optional.of(offers.get(2)), TradeOffers.nextBuy(offers));
    }

    @Test
    void 付款里有稀有消耗品而许可没开时报价不参与() {
        Payment diamond = new Payment("minecraft:diamond", 1, true);
        List<View> offers = List.of(offer(0, diamond, null, "minecraft:bread", 4, false));
        assertTrue(TradeOffers.eligible(offers, BREAD, TAGS, false).isEmpty());
        // 许可开了就参与：真人也买得起这种报价，只是要许可点头。
        assertEquals(List.of(offers.get(0)), TradeOffers.eligible(offers, BREAD, TAGS, true));
    }

    @Test
    void 买不了时分开说没货和付款要稀有消耗品() {
        Payment diamond = new Payment("minecraft:diamond", 1, true);
        // 有货、第二格付款要钻石：只差许可，说清要的是什么、这次许可没开；普通的那格绿宝石不算进去。
        List<View> rareOnly = List.of(offer(0, emeralds(1), diamond, "minecraft:bread", 4, false));
        TradeOffers.Unbuyable rare = TradeOffers.whyUnbuyable(rareOnly, BREAD, TAGS, false);
        assertTrue(rare.blockedByRareItems());
        assertEquals("有货但付款要 minecraft:diamond（稀有消耗品，这次 use_rare_items=false）", rare.describe());
        // 只是没货：不提许可。既没货又要钻石的那条只算没货，放开许可它也买不了。
        List<View> soldOut = List.of(
                offer(0, diamond, null, "minecraft:bread", 4, true),
                offer(1, emeralds(1), null, "minecraft:bread", 4, true));
        TradeOffers.Unbuyable empty = TradeOffers.whyUnbuyable(soldOut, BREAD, TAGS, false);
        assertFalse(empty.blockedByRareItems());
        assertEquals("2 条报价没货（存货卖完了）", empty.describe());
        // 两样都有就都写；不卖面包的报价不算进来。
        List<View> both = List.of(
                offer(0, diamond, null, "minecraft:bread", 4, false),
                offer(1, emeralds(1), null, "minecraft:bread", 4, true),
                offer(2, new Payment("minecraft:emerald_block", 1, true), null, "minecraft:iron_pickaxe", 1, false));
        assertEquals("有货但付款要 minecraft:diamond（稀有消耗品，这次 use_rare_items=false），另有 1 条报价没货（存货卖完了）",
                TradeOffers.whyUnbuyable(both, BREAD, TAGS, false).describe());
        // 许可开着时有货的不会被挡，只剩没货那条。
        assertEquals("1 条报价没货（存货卖完了）", TradeOffers.whyUnbuyable(both, BREAD, TAGS, true).describe());
    }

    @Test
    void 缺几件买几件_付款缺口按身上的存货算() {
        // 想按一条报价（1 绿宝石 4 个面包）买 2 笔：身上只有 1 个绿宝石，还缺 1 个。
        View offer = offer(0, emeralds(1), null, "minecraft:bread", 4, false);
        List<PaymentGap> gaps = TradeOffers.paymentGaps(offer, 2, itemId -> itemId.equals("minecraft:emerald") ? 1 : 0);
        assertEquals(List.of(new PaymentGap("minecraft:emerald", 1)), gaps);
        // 身上够付两笔就没有缺口。
        assertTrue(TradeOffers.paymentGaps(offer, 2, itemId -> itemId.equals("minecraft:emerald") ? 5 : 0).isEmpty());
    }

    @Test
    void 两格付款的报价_缺口按两格合计() {
        // 附魔书是书加绿宝石：两格都要付，缺口分别算。
        View offer = offer(0, new Payment("minecraft:book", 1, false), emeralds(3), "minecraft:enchanted_book", 1, false);
        List<PaymentGap> gaps = TradeOffers.paymentGaps(offer, 1,
                itemId -> itemId.equals("minecraft:book") ? 1 : 2);
        assertEquals(List.of(new PaymentGap("minecraft:emerald", 1)), gaps);
    }

    @Test
    void 标签要的东西_卖该标签下任一物品的报价都算() {
        // #minecraft:wool 里的任一羊毛都行。
        WantedItem wool = WantedItem.ofTag("minecraft:wool");
        FakeTags tags = new FakeTags();
        tags.put("minecraft:white_wool", "minecraft:wool");
        List<View> offers = List.of(offer(0, emeralds(1), null, "minecraft:white_wool", 1, false));
        assertFalse(TradeOffers.eligible(offers, wool, tags, true).isEmpty());
    }
}
