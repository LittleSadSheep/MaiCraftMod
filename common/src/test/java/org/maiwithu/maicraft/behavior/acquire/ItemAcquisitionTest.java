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
import org.maiwithu.maicraft.kernel.task.CollectedRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

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
    void 来源动手那一刻_正在拿的这一串看得见_做完就空了() {
        // 路上垫脚要知道正在拿什么：来源动作里走路时，拿东西的这一串都在链上。
        List<List<ItemRequest>> seen = new ArrayList<>();
        ItemAcquisition[] engine = new ItemAcquisition[1];
        ItemSource peeking = new ItemSource() {
            @Override public String describe() {
                return "挖";
            }
            @Override public AcquireVia via() {
                return AcquireVia.MINE;
            }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                return new SourceQuote.Offer("挖", request.count(), new AcquisitionCost(1, 1), null);
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext tick) {
                        seen.add(engine[0].inProgress());
                        backpack.add("minecraft:cobblestone", request.count());
                        return ActionStatus.done();
                    }
                    @Override public String describe() {
                        return "挖圆石";
                    }
                });
            }
        };
        engine[0] = engine(peeking);
        ActionStatus status = runToSettlement(engine[0].need(
                new ItemRequest(WantedItem.ofItem("minecraft:cobblestone"), 3, "石镐"), PERMISSIONS));

        assertEquals(ActionStatus.done(), status);
        assertEquals("minecraft:cobblestone", seen.getFirst().getFirst().wanted().itemId());
        assertTrue(engine[0].inProgress().isEmpty(), "做完就不在链上了");
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
    void 来源点出去没能确认的交互_一句一条交回给发起的一方() {
        // 箱子那边点出去了、游戏一直没回音：货一件没进包，引擎照旧认输换路、以缺东西结束，
        // 点出去没确认的事实不再拼进失败原因，单独交回给发起拿东西的一方。
        ScriptedSource chest = new ScriptedSource("记得的箱子", backpack)
                .answer(new SourceQuote.Offer("记得的箱子", 8, new AcquisitionCost(5, 4), null))
                .onBegin(ScriptedSource.Ending.UNCONFIRMED);
        CollectedRecords reported = new CollectedRecords();
        ActionStatus status = runToSettlement(engine(chest).need(
                new ItemRequest(WantedItem.ofItem("minecraft:coal"), 3, "火把"), PERMISSIONS,
                ItemAcquisition.Scope.ALL, null, reported));
        assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(List.of("记得的箱子里拿minecraft:coal：点出去了但没能确认结果"), reported.unconfirmedNotes());
        assertEquals("取货", reported.unconfirmed.getFirst().what());
    }

    @Test
    void 备料的嵌套请求_接住外层的记账口() {
        // 外层要木板，做木板的来源在推进时回引擎要原木（嵌套一次）：箱子里点原木没能确认的事实要记到最外层的发起方。
        ScriptedSource chest = new ScriptedSource("记得的箱子", backpack)
                .answer(new SourceQuote.Offer("记得的箱子", 8, new AcquisitionCost(5, 4), null))
                .onBegin(ScriptedSource.Ending.UNCONFIRMED);
        NestingSource planks = new NestingSource("minecraft:oak_planks",
                new ItemRequest(WantedItem.ofItem("minecraft:oak_log"), 1, "做木板的原木"));
        ItemAcquisition acquisition = engine(planks, new OnlyFor("minecraft:oak_log", chest));
        planks.engine = acquisition;
        CollectedRecords reported = new CollectedRecords();
        runToSettlement(acquisition.need(
                new ItemRequest(WantedItem.ofItem("minecraft:oak_planks"), 4, "施工备料"), PERMISSIONS,
                ItemAcquisition.Scope.ALL, null, reported));
        assertTrue(reported.unconfirmedNotes().stream().anyMatch(fact -> fact.contains("minecraft:oak_log")
                && fact.contains("没能确认")), "嵌套里没能确认的事实记到了外层发起的一方");
    }

    @Test
    void 建出来没开始做的备料动作不占着链() {
        // 来源一口气建好一串备料动作，前面一步失败了，后面的从没推进过：它们不能留在链上，
        // 之后再要同一样东西照常去拿，不报"转了圈"。
        ScriptedSource chest = new ScriptedSource("记得的箱子", backpack)
                .answer(new SourceQuote.Offer("记得的箱子", 8, new AcquisitionCost(5, 4), null));
        ItemAcquisition acquisition = engine(chest);
        acquisition.actionFor(new ItemRequest(WantedItem.ofItem("minecraft:crafting_table"), 1, "就地摆放的工作站"),
                PERMISSIONS);
        ActionStatus status = runToSettlement(acquisition.need(
                new ItemRequest(WantedItem.ofItem("minecraft:crafting_table"), 1, "开局"), PERMISSIONS));
        assertInstanceOf(ActionStatus.Done.class, status);
        assertTrue(countOf("minecraft:crafting_table") >= 1, "照常拿到了工作台");
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
        // 来源推进时又回到引擎要同样的东西：引擎认出转圈，嵌套的那一下当场失败。
        NestingSource iron = new NestingSource("minecraft:iron_ingot",
                new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 1, "工具准备"));
        ItemAcquisition acquisition = engine(iron);
        iron.engine = acquisition;
        runToSettlement(acquisition.actionFor(
                new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 1, "工具准备"), PERMISSIONS));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, iron.nestedEnding);
        assertTrue(failed.problem().message().contains("转了圈"));
    }

    @Test
    void 备料超过深度上限当场拒绝() {
        NestingSource ore = new NestingSource("minecraft:raw_iron",
                new ItemRequest(WantedItem.ofItem("minecraft:iron_ore"), 1, "备原料"));
        ItemAcquisition shallow = new ItemAcquisition(List.of(ore), backpack, offhand, tags,
                new FixedSpot(0, 64, 0), Optional.empty(), 1);
        ore.engine = shallow;
        runToSettlement(shallow.actionFor(
                new ItemRequest(WantedItem.ofItem("minecraft:raw_iron"), 1, "备原料"), PERMISSIONS));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, ore.nestedEnding);
        assertTrue(failed.problem().message().contains("层数太深"));
    }

    /** 只答一样东西的来源：别的东西问它一律说没有，好让嵌套的请求落到指定的来源上。 */
    private record OnlyFor(String itemId, ItemSource inner) implements ItemSource {
        @Override public String describe() {
            return inner.describe();
        }

        @Override public AcquireVia via() {
            return inner.via();
        }

        @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
            return request.wanted().specifier().equals(itemId) ? inner.quote(request, context)
                    : new SourceQuote.Unavailable(inner.describe(), "只管 " + itemId);
        }

        @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
            return inner.begin(request, offer, context);
        }
    }

    /**
     * 做东西的来源替身：只答一样东西，动手后在推进里回引擎要一样原料（像合成来源备料那样），
     * 把那一下的结局记下来再收场。
     */
    private static final class NestingSource implements ItemSource {
        private final String produces;
        private final ItemRequest nested;
        ItemAcquisition engine;
        ActionStatus nestedEnding;

        NestingSource(String produces, ItemRequest nested) {
            this.produces = produces;
            this.nested = nested;
        }

        @Override public String describe() {
            return "自己做";
        }

        @Override public AcquireVia via() {
            return AcquireVia.CRAFT;
        }

        @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
            return request.wanted().specifier().equals(produces)
                    ? new SourceQuote.Offer("自己做", request.count(), new AcquisitionCost(1, 1), null)
                    : new SourceQuote.Unavailable("自己做", "只做 " + produces);
        }

        @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
            Action fetching = engine.actionFor(nested, context.permissions());
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext tick) {
                    ActionStatus status = fetching.tick(tick);
                    if (!(status instanceof ActionStatus.Running)) nestedEnding = status;
                    return status instanceof ActionStatus.Running ? status : ActionStatus.done();
                }

                @Override public String describe() {
                    return "备料";
                }
            });
        }
    }

    private long countOf(String itemId) {
        return backpack.stacks().stream()
                .filter(stack -> stack.itemId().equals(itemId))
                .mapToLong(stack -> stack.count())
                .sum();
    }
}
