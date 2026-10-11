// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.function.ToIntFunction;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 交易界面里的报价与挑选：报价读自游戏同步过来的列表，挑哪一条、缺多少付款是纯判断，可离线测。
 *
 * <p>像真人一样不还价：他摆多少就是多少，卖想要东西的报价里挑付款件数最少的那条；
 * 一条买完还缺接着买下一条，卖完了就换商人。超出稀有消耗品许可的报价不参与。
 */
public final class TradeOffers {

    /** 一笔付款：这条报价收什么、收几件；是不是稀有消耗品由读端按游戏自己标的价值分级给出。 */
    public record Payment(String itemId, int count, boolean rare) {

        public Payment {
            if (itemId == null || itemId.isBlank()) throw new IllegalArgumentException("付款物品不能为空");
            if (count < 1) throw new IllegalArgumentException("付款件数至少为 1：" + count);
        }
    }

    /**
     * 交易界面里的一条报价。
     *
     * @param listIndex   在报价列表里的第几条：点选报价按它点，同价商品排在一起也不会买错条
     * @param costA       第一笔付款；交易界面两格付款，第一条必有
     * @param costB       第二笔付款；这条报价只要一样东西时为空
     * @param resultItemId 卖出来的东西
     * @param resultCount 一笔卖几件
     * @param outOfStock  这条报价的存货用完了没有；用完的暂时买不了
     */
    public record View(int listIndex, Payment costA, Optional<Payment> costB,
            String resultItemId, int resultCount, boolean outOfStock) {

        public View {
            if (costA == null) throw new IllegalArgumentException("报价的第一笔付款不能为空");
            if (resultItemId == null || resultItemId.isBlank()) throw new IllegalArgumentException("报价要有卖出的东西");
            if (resultCount < 1) throw new IllegalArgumentException("一笔至少卖一件：" + resultCount);
            costB = costB == null ? Optional.empty() : costB;
        }

        /** 这条报价收几件东西：挑报价时按它比，少出力的先买。 */
        public int paymentUnits() {
            return costA.count + costB.map(Payment::count).orElse(0);
        }
    }

    /** 一次付款缺口：身上缺哪样付款物品、还缺几件。 */
    public record PaymentGap(String itemId, int missingCount) {
    }

    /** 交易界面一格现在的样子：装着什么、有几件；空格给 null。 */
    public record SlotContent(String itemId, int count) {

        public static final SlotContent EMPTY = new SlotContent(null, 0);

        public boolean isEmpty() {
            return itemId == null;
        }
    }

    private TradeOffers() {
    }

    /**
     * 参与购买的报价：卖的是想要的东西、还有存货、付款不超出这次的稀有消耗品许可。
     * 列表顺序保持报价列表原样，挑选时在参与的里面比。
     *
     * @param mayUseRareItems 这次的 use_rare_items 许可；稀有付款物品的报价只有开着才参与
     */
    public static List<View> eligible(List<View> offers, WantedItem wanted, ReadsItemTags tags,
            boolean mayUseRareItems) {
        List<View> found = new ArrayList<>();
        for (View offer : offers) {
            if (offer.outOfStock()) continue;
            if (!wanted.matches(offer.resultItemId(), tags.tagsOf(offer.resultItemId()))) continue;
            if (!paymentAllowed(offer, mayUseRareItems)) continue;
            found.add(offer);
        }
        return List.copyOf(found);
    }

    /** 报价能不能买：付款里有一件稀有消耗品而许可没开，这条就不参与。 */
    public static boolean paymentAllowed(View offer, boolean mayUseRareItems) {
        return mayUseRareItems || (!offer.costA().rare && offer.costB().map(payment -> !payment.rare).orElse(true));
    }

    /**
     * 下一笔买哪条：付款件数最少的报价先买，同价按列表顺序——报价列表里同价商品排在一起，
     * 点选按列表序号点，不会买错条。
     */
    public static Optional<View> nextBuy(List<View> offers) {
        View best = null;
        for (View offer : offers) {
            if (best == null || offer.paymentUnits() < best.paymentUnits()) {
                best = offer;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * 按这条报价买这么些笔，身上缺哪些付款：同一种付款按各笔合计；缺货的报价上面已经筛掉。
     *
     * @param trades 想按这条报价买几笔
     * @param carriedOf 身上某种物品有几件的数法，由调用方从背包视图数出来
     */
    public static List<PaymentGap> paymentGaps(View offer, int trades,
            ToIntFunction<String> carriedOf) {
        List<PaymentGap> gaps = new ArrayList<>();
        collectGap(offer.costA(), trades, carriedOf, gaps);
        offer.costB().ifPresent(payment -> collectGap(payment, trades, carriedOf, gaps));
        return List.copyOf(gaps);
    }

    private static void collectGap(Payment payment, int trades,
            ToIntFunction<String> carriedOf, List<PaymentGap> gaps) {
        int needed = payment.count * trades;
        int missing = needed - carriedOf.applyAsInt(payment.itemId);
        if (missing > 0) {
            gaps.add(new PaymentGap(payment.itemId, missing));
        }
    }
}
