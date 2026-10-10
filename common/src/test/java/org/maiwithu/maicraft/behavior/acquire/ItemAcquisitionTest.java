// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 拿到物品的引擎：身上够了直接算完；缺了问遍来源挑最省的路，用一个来源就重新清点；
 * 做砸或白跑的来源不再回头；谁也给不了就按卡住的原因挑问题种类；
 * 背包装不下先腾，要动贵重品就交许可问题。
 */
class ItemAcquisitionTest {

    private static final Permissions PERMISSIONS = Permissions.DEFAULT;

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeOffhand offhand = new FakeOffhand();
    private final FakeTags tags = new FakeTags();

    private ItemAcquisition engine(ItemSource... sources) {
        return new ItemAcquisition(List.of(sources), backpack, offhand, tags,
                new FixedSpot(0, 64, 0), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH);
    }

    /** 一刻一刻推进，直到动作定局，返回终态。 */
    private ActionStatus runToSettlement(Action action) {
        for (int tick = 1; tick <= 200; tick++) {
            ActionStatus status = action.tick(new StubTick(tick));
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("两百刻还没定局，引擎在原地打转");
    }

    @Test
    void 要的是再多几件_身上原有的不算() {
        // 身上已有 3 根原木，再要 3 根：照样去问来源，拿完身上是 6 根。
        backpack.add("minecraft:oak_log", 3);
        ScriptedSource chest = new ScriptedSource("记得的箱子", backpack)
                .answer(new SourceQuote.Offer("记得的箱子", 3, new AcquisitionCost(5, 4), null));
        ActionStatus status = runToSettlement(
                engine(chest).need(new ItemRequest(WantedItem.ofItem("minecraft:oak_log"), 3, "施工备料"),
                        PERMISSIONS));
        assertEquals(ActionStatus.done(), status);
        assertEquals(1, chest.begunTimes());
        assertEquals(6, countOf("minecraft:oak_log"));
    }

    @Test
    void 东西先进了背包_来源还在收尾_等它收完再清点_途径照记() {
        // 从终端取货就是这样：最后几件进了背包，还要放好光标、关上界面才算做完。
        AcquireVia ae2 = new AcquireVia("ae2", "从附近的 ME 终端取");
        ScriptedSource terminal = new ScriptedSource("ME 终端", ae2, backpack)
                .answer(new SourceQuote.Offer("ME 终端", 8, new AcquisitionCost(6, 4), null))
                .onBegin(ScriptedSource.Ending.DELIVER_THEN_TIDY);
        List<String> obtainedVia = new ArrayList<>();
        ActionStatus status = runToSettlement(engine(terminal).need(
                new ItemRequest(WantedItem.ofItem("minecraft:torch"), 8, "照明"), PERMISSIONS,
                ItemAcquisition.Scope.ALL, obtainedVia::add));
        assertEquals(ActionStatus.done(), status);
        assertTrue(terminal.tidied(), "数够了也先让来源把收尾做完");
        assertEquals(List.of("ae2"), obtainedVia);
        assertEquals(8, countOf("minecraft:torch"));
    }

    @Test
    void 东西进了背包后来源以问题收场_途径照记_不说成身上已有的() {
        AcquireVia ae2 = new AcquireVia("ae2", "从附近的 ME 终端取");
        ScriptedSource terminal = new ScriptedSource("ME 终端", ae2, backpack)
                .answer(new SourceQuote.Offer("ME 终端", 8, new AcquisitionCost(6, 4), null))
                .onBegin(ScriptedSource.Ending.DELIVER_THEN_FAIL);
        List<String> obtainedVia = new ArrayList<>();
        ActionStatus status = runToSettlement(engine(terminal).need(
                new ItemRequest(WantedItem.ofItem("minecraft:torch"), 8, "照明"), PERMISSIONS,
                ItemAcquisition.Scope.ALL, obtainedVia::add));
        assertEquals(ActionStatus.done(), status);
        assertEquals(List.of("ae2"), obtainedVia);
        assertEquals(8, countOf("minecraft:torch"));
    }

    @Test
    void 缺的问来源_用最省的一条路_拿到后重新清点算完成() {
        ScriptedSource costly = new ScriptedSource("自己做", backpack)
                .answer(new SourceQuote.Offer("自己做", 10, new AcquisitionCost(80, 6), null));
        ScriptedSource cheap = new ScriptedSource("记得的箱子", backpack)
                .answer(new SourceQuote.Offer("记得的箱子", 4, new AcquisitionCost(10, 4), null));
        ActionStatus status = runToSettlement(
                engine(costly, cheap).need(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 4, "烧炼的燃料"),
                        PERMISSIONS));
        assertEquals(ActionStatus.done(), status);
        assertEquals(4, countOf("minecraft:coal"));
        // 只用了便宜的一条：贵的那个从来没动手。
        assertEquals(0, costly.begunTimes());
    }

    @Test
    void 做砸的来源划掉_换下一个() {
        ScriptedSource broken = new ScriptedSource("记得的箱子", backpack)
                .answer(new SourceQuote.Offer("记得的箱子", 2, new AcquisitionCost(5, 4), null))
                .onBegin(ScriptedSource.Ending.FAIL);
        ScriptedSource steady = new ScriptedSource("采掘", backpack)
                .answer(new SourceQuote.Offer("采掘", 3, new AcquisitionCost(20, 3), null));
        ActionStatus status = runToSettlement(
                engine(broken, steady).need(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 3, "烧炼的燃料"),
                        PERMISSIONS));
        assertEquals(ActionStatus.done(), status);
        assertEquals(3, countOf("minecraft:coal"));
    }

    @Test
    void 白跑一趟的来源不再回头_最后如实说缺什么() {
        ScriptedSource emptyChest = new ScriptedSource("记得的箱子", backpack)
                .answer(new SourceQuote.Offer("记得的箱子", 5, new AcquisitionCost(5, 4), null))
                .onBegin(ScriptedSource.Ending.DELIVER_NOTHING);
        ActionStatus status = runToSettlement(
                engine(emptyChest).need(new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 2, "工具准备"),
                        PERMISSIONS));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(Problem.Kind.NEED_ITEM, failed.problem().kind());
        assertTrue(failed.problem().message().contains("记得的箱子"), "问题里要点名白跑的来源");
    }

    @Test
    void 谁也给不了_问题里带上各家给不了的原因() {
        ScriptedSource none = new ScriptedSource("记得的箱子", backpack)
                .answer(new SourceQuote.Unavailable("记得的箱子", "没装着这个"));
        ActionStatus status = runToSettlement(
                engine(none).need(new ItemRequest(WantedItem.ofItem("minecraft:diamond"), 1, "工具准备"),
                        PERMISSIONS));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(Problem.Kind.NEED_ITEM, failed.problem().kind());
        assertTrue(failed.problem().message().contains("没装着这个"));
    }

    @Test
    void 全被许可挡下_交许可问题() {
        ScriptedSource guarded = new ScriptedSource("采掘", backpack)
                .answer(new SourceQuote.NeedsApproval("采掘", Problem.of(Problem.Kind.NEED_APPROVAL,
                        "(1, 64, 1) 是玩家放的，任何许可档位都不动它")));
        ActionStatus status = runToSettlement(
                engine(guarded).need(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 1, "火把"),
                        PERMISSIONS));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(Problem.Kind.NEED_APPROVAL, failed.problem().kind());
    }

    @Test
    void 背包要动贵重品才腾得出_交许可问题() {
        FakeBackpack full = new FakeBackpack(1, new BackpackStack(
                "minecraft:diamond", 1, 64, false, false, true, false));
        InventorySpace space = new InventorySpace(full, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        ScriptedSource chest = new ScriptedSource("记得的箱子", full)
                .answer(new SourceQuote.Offer("记得的箱子", 5, new AcquisitionCost(5, 4), null));
        ItemAcquisition acquisition = new ItemAcquisition(List.of(chest), full, offhand, tags,
                new FixedSpot(0, 64, 0), Optional.of(space), ItemAcquisition.DEFAULT_MAX_DEPTH);
        ActionStatus status = runToSettlement(
                acquisition.need(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 1, "火把"), PERMISSIONS));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(Problem.Kind.NEED_APPROVAL, failed.problem().kind());
        assertTrue(failed.problem().message().contains("贵重"));
    }

    @Test
    void 备料转圈当场拒绝() {
        // 来源动手时又回到引擎要同样的东西：引擎认出转圈，给一个当场失败的动作。
        ScriptedSource iron = new ScriptedSource("记得的箱子", backpack)
                .answer(new SourceQuote.Offer("记得的箱子", 8, new AcquisitionCost(5, 4), null));
        ItemAcquisition acquisition = engine(iron);
        Action first = acquisition.actionFor(
                new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 1, "工具准备"), PERMISSIONS);
        ActionStatus loop = first.tick(new StubTick(1));
        assertInstanceOf(ActionStatus.Running.class, loop);
        Action looped = acquisition.actionFor(
                new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 1, "工具准备"), PERMISSIONS);
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, looped.tick(new StubTick(2)));
        assertTrue(failed.problem().message().contains("转了圈"));
        first.close();
    }

    @Test
    void 备料超过深度上限当场拒绝() {
        ScriptedSource ore = new ScriptedSource("采掘", backpack)
                .answer(new SourceQuote.Offer("采掘", 8, new AcquisitionCost(5, 3), null));
        ItemAcquisition shallow = new ItemAcquisition(List.of(ore), backpack, offhand, tags,
                new FixedSpot(0, 64, 0), Optional.empty(), 1);
        Action first = shallow.actionFor(
                new ItemRequest(WantedItem.ofItem("minecraft:raw_iron"), 1, "备原料"), PERMISSIONS);
        assertInstanceOf(ActionStatus.Running.class, first.tick(new StubTick(1)));
        Action second = shallow.actionFor(
                new ItemRequest(WantedItem.ofItem("minecraft:iron_ore"), 1, "备原料"), PERMISSIONS);
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, second.tick(new StubTick(2)));
        assertTrue(failed.problem().message().contains("层数太深"));
        first.close();
    }

    private long countOf(String itemId) {
        return backpack.stacks().stream()
                .filter(stack -> stack.itemId().equals(itemId))
                .mapToLong(stack -> stack.count())
                .sum();
    }
}
