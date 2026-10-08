// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;

/**
 * 挑来源：把各来源的报价按总代价从低到高排，凑够要的数量为止。
 * 数量明确的报价按代价直接排；只记得有箱子、不知道里面有什么的报价排在明确够数的后面——
 * 有确定的货就不赌运气。这是纯计算，不读世界也不动手。
 */
public final class AcquisitionSelector {

    private AcquisitionSelector() {}

    /**
     * 从报价里挑出一串值得去试的来源，顺序就是执行的顺序。
     * 已知数量的报价凑够即停，末尾至多再带上一个数量未知的（世界里可能还有存货）。
     */
    public static List<SourceQuote.Offer> choose(List<SourceQuote.Offer> offers, int stillNeeded) {
        List<SourceQuote.Offer> known = new ArrayList<>();
        List<SourceQuote.Offer> unknown = new ArrayList<>();
        for (SourceQuote.Offer offer : offers) {
            (offer.countUnknown() ? unknown : known).add(offer);
        }
        known.sort(Comparator.comparing(offer -> offer.cost()));
        unknown.sort(Comparator.comparing(offer -> offer.cost()));

        List<SourceQuote.Offer> plan = new ArrayList<>();
        int covered = 0;
        for (SourceQuote.Offer offer : known) {
            if (covered >= stillNeeded) break;
            plan.add(offer);
            covered += offer.obtainableCount();
        }
        // 明确的货不够，又记得有个可能装着的箱子：带上它碰碰运气，开了才知道。
        if (covered < stillNeeded && !unknown.isEmpty()) {
            plan.add(unknown.getFirst());
        }
        return plan;
    }
}
