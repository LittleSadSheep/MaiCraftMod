// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 带限定的一次拿东西：via 只走指定途径、距离上限把太远的报价挡下、半径原样传给来源、
 * 实际拿到东西时报告途径。来源都用按剧本回答的替身。
 */
class AcquireScopeTest {

    private static final SourceContext CONTEXT = new SourceContext(
            WorldPosition.here(0, 64, 0), Permissions.DEFAULT);

    /** 替身：问价就给一条固定报价，动手就往背包里放货；记住问到的半径。 */
    class OfferingSource implements ItemSource {
        final String name;
        final String route;
        final FakeBackpack backpack;
        final AcquisitionCost cost;
        Integer askedRadius;
        int begunCount;

        OfferingSource(String name, String route, FakeBackpack backpack, double distance) {
            this.name = name;
            this.route = route;
            this.backpack = backpack;
            this.cost = new AcquisitionCost(distance, 1);
        }

        @Override public String describe() {
            return name;
        }

        @Override public String route() {
            return route;
        }

        @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
            askedRadius = context.radiusBlocks();
            return new SourceQuote.Offer(name, request.count(), cost, null);
        }

        @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer,
                SourceContext context) {
            begunCount++;
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext tick) {
                    backpack.add("minecraft:coal", request.count());
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "放货";
                }
            });
        }
    }

    private FakeBackpack backpack() {
        return new FakeBackpack(36);
    }

    private ItemAcquisition engine(FakeBackpack backpack, ItemSource... sources) {
        return new ItemAcquisition(List.of(sources), backpack, null,
                itemId -> Set.of(), new FixedSpot(0, 64, 0), Optional.empty(), 4);
    }

    @Test
    void via只让指定途径的来源动手() {
        FakeBackpack backpack = backpack();
        OfferingSource mine = new OfferingSource("采掘", AcquireRoutes.MINE, backpack, 5);
        OfferingSource container = new OfferingSource("箱子", AcquireRoutes.CONTAINER, backpack, 5);
        ItemAcquisition acquisition = engine(backpack, mine, container);

        ActionStatus status = runToEnd(acquisition.need(
                new ItemRequest(WantedItem.ofItem("minecraft:coal"), 2, "测试"),
                Permissions.DEFAULT,
                new ItemAcquisition.Scope(Set.of(AcquireRoutes.MINE), null, null), null));

        assertEquals(ActionStatus.Done.class, status.getClass());
        assertEquals(1, mine.begunCount);
        assertEquals(0, container.begunCount);
    }

    @Test
    void 距离上限把太远的报价挡下() {
        FakeBackpack backpack = backpack();
        OfferingSource far = new OfferingSource("远处的矿", AcquireRoutes.MINE, backpack, 120);
        ItemAcquisition acquisition = engine(backpack, far);

        ActionStatus status = runToEnd(acquisition.need(
                new ItemRequest(WantedItem.ofItem("minecraft:coal"), 2, "测试"),
                Permissions.DEFAULT,
                new ItemAcquisition.Scope(Set.of(), 50.0, null), null));

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(Problem.Kind.NEED_ITEM, failed.problem().kind());
        assertTrue(failed.problem().message().contains("50 格之外"), failed.problem().message());
        assertEquals(0, far.begunCount);
    }

    @Test
    void 半径原样传给来源_没给就是没给() {
        FakeBackpack backpack = backpack();
        OfferingSource mine = new OfferingSource("采掘", AcquireRoutes.MINE, backpack, 5);
        ItemAcquisition acquisition = engine(backpack, mine);
        runToEnd(acquisition.need(
                new ItemRequest(WantedItem.ofItem("minecraft:coal"), 1, "测试"),
                Permissions.DEFAULT,
                new ItemAcquisition.Scope(Set.of(), null, 16), null));
        assertEquals(16, mine.askedRadius);

        FakeBackpack other = backpack();
        OfferingSource plain = new OfferingSource("采掘", AcquireRoutes.MINE, other, 5);
        runToEnd(engine(other, plain).need(
                new ItemRequest(WantedItem.ofItem("minecraft:coal"), 1, "测试"), Permissions.DEFAULT));
        assertFalse(plain.askedRadius != null, "没给半径就不该带上半径");
    }

    @Test
    void 真拿到东西时报告途径_白跑一趟不报() {
        FakeBackpack backpack = backpack();
        OfferingSource mine = new OfferingSource("采掘", AcquireRoutes.MINE, backpack, 5);
        List<String> delivered = new ArrayList<>();
        runToEnd(engine(backpack, mine).need(
                new ItemRequest(WantedItem.ofItem("minecraft:coal"), 2, "测试"),
                Permissions.DEFAULT, ItemAcquisition.Scope.ALL, delivered::add));
        assertEquals(List.of(AcquireRoutes.MINE), delivered);

        // 白跑一趟：做完了但一件没进背包，途径不该被报告。
        FakeBackpack silent = backpack();
        OfferingSource liar = new OfferingSource("空箱子", AcquireRoutes.CONTAINER, silent, 5) {
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer,
                    SourceContext context) {
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext tick) {
                        return ActionStatus.done();
                    }
                    @Override public String describe() {
                        return "装模作样开箱";
                    }
                });
            }
        };
        List<String> none = new ArrayList<>();
        runToEnd(engine(silent, liar).need(
                new ItemRequest(WantedItem.ofItem("minecraft:coal"), 2, "测试"),
                Permissions.DEFAULT, ItemAcquisition.Scope.ALL, none::add));
        assertTrue(none.isEmpty(), "白跑一趟不该报途径");
    }

    @Test
    void 交易来源如实回答不支持() {
        TradeSource trade = new TradeSource();
        SourceQuote quote = trade.quote(
                new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 1, "测试"), CONTEXT);
        assertInstanceOf(SourceQuote.Unsupported.class, quote);
        assertEquals(AcquireRoutes.TRADE, trade.route());
    }

    @Test
    void 内部备料不受外层途径限制() {
        // via=smelt 时，缺的原料仍可以问遍所有来源：指定"烧炼"不等于连挖煤都不许。
        FakeBackpack backpack = backpack();
        OfferingSource furnace = new OfferingSource("烧炼", AcquireRoutes.SMELT, backpack, 5);
        ItemAcquisition acquisition = engine(backpack, furnace);
        // 内部需求走 actionFor：途径与距离都不限。
        Action backup = acquisition.actionFor(
                new ItemRequest(WantedItem.ofItem("minecraft:coal"), 1, "烧炼的燃料"),
                Permissions.DEFAULT);
        assertEquals(ActionStatus.Done.class, runToEnd(backup).getClass());
        assertEquals(1, furnace.begunCount);
    }

    private ActionStatus runToEnd(Action action) {
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 100; i++) {
            status = action.tick(new StubTick(i));
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        return status;
    }
}
