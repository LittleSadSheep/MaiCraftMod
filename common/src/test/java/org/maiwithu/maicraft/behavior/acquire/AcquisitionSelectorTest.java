// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;

/** 挑来源：按总代价从低到高凑够数量；数量没凑够才带上一只没开过的箱子碰运气。 */
class AcquisitionSelectorTest {

    private static SourceQuote.Offer offer(String name, int count, double distance, int actions) {
        return new SourceQuote.Offer(name, count, new AcquisitionCost(distance, actions), null);
    }

    private static SourceQuote.Offer unknown(String name, double distance) {
        return new SourceQuote.Offer(name, SourceQuote.Offer.UNKNOWN_COUNT,
                new AcquisitionCost(distance, 4), "没开过", "1,2,3");
    }

    @Test
    void 按总代价从低到高排() {
        List<SourceQuote.Offer> plan = AcquisitionSelector.choose(
                List.of(offer("远的", 64, 200, 4), offer("近的", 2, 8, 2)), 4);
        assertEquals(List.of("近的", "远的"), plan.stream().map(SourceQuote.Offer::source).toList());
    }

    @Test
    void 凑够就停_不为了多余的货多跑路() {
        List<SourceQuote.Offer> plan = AcquisitionSelector.choose(
                List.of(offer("近的", 3, 8, 2), offer("远的", 64, 200, 4)), 3);
        assertEquals(List.of("近的"), plan.stream().map(SourceQuote.Offer::source).toList());
    }

    @Test
    void 明确的货不够_带上一只没开过的箱子_排在明确报价后面() {
        List<SourceQuote.Offer> plan = AcquisitionSelector.choose(
                List.of(unknown("没开过的箱子", 5), offer("近的", 1, 8, 2)), 3);
        assertEquals(List.of("近的", "没开过的箱子"), plan.stream().map(SourceQuote.Offer::source).toList());
    }

    @Test
    void 明确的货够用_没开过的箱子不参与() {
        List<SourceQuote.Offer> plan = AcquisitionSelector.choose(
                List.of(unknown("没开过的箱子", 5), offer("近的", 8, 8, 2)), 3);
        assertEquals(List.of("近的"), plan.stream().map(SourceQuote.Offer::source).toList());
    }

    @Test
    void 一条报价都没有_计划为空() {
        assertTrue(AcquisitionSelector.choose(List.of(), 3).isEmpty());
    }
}
