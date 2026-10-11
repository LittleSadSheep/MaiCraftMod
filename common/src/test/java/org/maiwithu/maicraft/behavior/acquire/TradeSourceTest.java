// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 交易的来源：成年有业又碰得的村民才参与；幼年、无业、傻子、有名字、圈养、记住区域里的逐类排除并写明原因；
 * 报价如实说"有多少要开了才知道"；动手把合格的候选按由近及远交给执行接缝。
 */
class TradeSourceTest {

    private static final SourceContext CONTEXT = new SourceContext(
            WorldPosition.here(0, 64, 0), Permissions.DEFAULT);

    /** 谁的都不是：没有名字、没拴绳、没圈养、没驯服。 */
    private static final ReadsCreatureSituation NOBODY = uuid -> Optional.of(
            new ReadsCreatureSituation.CreatureSituation(false, false, false, false, false, false));

    private static final Protection NOBODY_OWNS = new Protection((dimension, x, y, z) -> Optional.empty(),
            List::of, name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self");

    private static ScansMerchants.MerchantSpot farmer(int id, int x, boolean baby, boolean opensTrading) {
        return new ScansMerchants.MerchantSpot(id, new UUID(0, id), WorldPosition.here(x, 64, 0),
                "farmer 村民", baby, opensTrading);
    }

    private TradeSource source(ScansMerchants scans, ReadsCreatureSituation creatures) {
        return new TradeSource(scans, creatures, List::of, NOBODY_OWNS,
                (candidates, request, permissions) -> Optional.empty());
    }

    @Test
    void 附近有合格的村民时报_要开了才知道() {
        TradeSource source = source((center, radius) -> List.of(farmer(1, 8, false, true)), NOBODY);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                source.quote(request(), CONTEXT));
        assertEquals(SourceQuote.Offer.UNKNOWN_COUNT, offer.obtainableCount());
        assertTrue(offer.risk().contains("要开了"), offer.risk());
        // 线索记的是最近合格候选的实体编号，动手时原样带回来核对。
        assertEquals("1", offer.hint());
    }

    @Test
    void 幼年的村民被排除并写明原因() {
        TradeSource source = source((center, radius) -> List.of(farmer(1, 8, true, false)), NOBODY);
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class,
                source.quote(request(), CONTEXT));
        assertTrue(unavailable.reason().contains("幼年"), unavailable.reason());
    }

    @Test
    void 无业与傻子的村民被排除并写明原因() {
        TradeSource noJob = source((center, radius) -> List.of(
                new ScansMerchants.MerchantSpot(1, new UUID(0, 1), WorldPosition.here(8, 64, 0),
                        "无业的村民", false, false)), NOBODY);
        assertTrue(assertInstanceOf(SourceQuote.Unavailable.class, noJob.quote(request(), CONTEXT))
                .reason().contains("营生"));
        TradeSource nitwit = source((center, radius) -> List.of(
                new ScansMerchants.MerchantSpot(2, new UUID(0, 2), WorldPosition.here(8, 64, 0),
                        "傻子村民", false, false)), NOBODY);
        assertTrue(assertInstanceOf(SourceQuote.Unavailable.class, nitwit.quote(request(), CONTEXT))
                .reason().contains("营生"));
    }

    @Test
    void 有名字与圈养的村民是别人的_不搭话() {
        // 有名字的：处境里写着 bonded。
        ReadsCreatureSituation named = uuid -> Optional.of(
                new ReadsCreatureSituation.CreatureSituation(false, false, true, false, false, false));
        TradeSource source = source((center, radius) -> List.of(farmer(1, 8, false, true)), named);
        assertTrue(assertInstanceOf(SourceQuote.Unavailable.class, source.quote(request(), CONTEXT))
                .reason().contains("别人的"), "有名字的该按别人的排除");
        // 圈养的：围栏里的牲畜是有主的。
        ReadsCreatureSituation fenced = uuid -> Optional.of(
                new ReadsCreatureSituation.CreatureSituation(false, false, false, false, false, true));
        TradeSource enclosed = source((center, radius) -> List.of(farmer(1, 8, false, true)), fenced);
        assertTrue(assertInstanceOf(SourceQuote.Unavailable.class, enclosed.quote(request(), CONTEXT))
                .reason().contains("别人的"), "圈养的该按别人的排除");
    }

    @Test
    void 记住区域里的村民不去搭话() {
        // 村民正站在角色记住的玩家地盘里：地盘里的东西都是别人的。
        TradeSource source = new TradeSource((center, radius) -> List.of(farmer(1, 8, false, true)), NOBODY,
                () -> List.of(new RememberedRegion("河东的农场", WorldPosition.here(10, 64, 0), 16)),
                NOBODY_OWNS, (candidates, request, permissions) -> Optional.empty());
        assertTrue(assertInstanceOf(SourceQuote.Unavailable.class, source.quote(request(), CONTEXT))
                .reason().contains("河东的农场"));
    }

    @Test
    void 附近一个村民都没有时如实说_并提示先走到有村民的地方() {
        TradeSource source = source((center, radius) -> List.of(), NOBODY);
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class,
                source.quote(request(), CONTEXT));
        assertTrue(unavailable.reason().contains("没有能做买卖的"), unavailable.reason());
        assertTrue(unavailable.reason().contains("走到"), unavailable.reason());
    }

    @Test
    void 动手时把合格的候选按扫描顺序交给执行接缝() {
        // 扫描按由近及远排好：合格的候选原样递给接缝，排除的在源头就滤掉。
        List<ScansMerchants.MerchantSpot> scanned = List.of(farmer(7, 5, false, true), farmer(3, 9, false, true));
        List<Object> handed = new ArrayList<>();
        TradeSource source = new TradeSource((center, radius) -> scanned, NOBODY, List::of, NOBODY_OWNS,
                (candidates, request, permissions) -> {
                    candidates.forEach(handed::add);
                    return Optional.of(new Action() {
                        @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
                        @Override public String describe() { return "试替身"; }
                    });
                });
        Optional<Action> begun = source.begin(request(),
                new SourceQuote.Offer("和村民交易", SourceQuote.Offer.UNKNOWN_COUNT,
                        new AcquisitionCost(5, 6), "", "7"),
                CONTEXT);
        assertTrue(begun.isPresent());
        assertEquals(List.of(scanned.get(0), scanned.get(1)), handed);
    }

    private static ItemRequest request() {
        return new ItemRequest(WantedItem.ofItem("minecraft:bread"), 5, "按需要拿取");
    }
}
