// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 现货不够时在网络里下单补缺口：取完现货还缺、有样板才下单，件数按还缺的、封顶在背包装得下的，一次只下一单；
 * 自己之前下的还在做就不再下、接着等；下单的结论记一条 attempts，没开工、等不到都按失败交回并说清作业在不在跑。
 */
class NetworkCraftTest {

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final String WHERE = "ae2:terminal（3, 64, 0 的北面）";
    private static final Predicate<ItemStack> IRON = stack -> stack.is(Items.IRON_INGOT);
    private static final ToIntFunction<ItemStack> ROOMY = sample -> 36 * 64;

    /** 记账口替身：只收 attempts。 */
    private static final class Attempts implements TaskRecords {
        final List<String> lines = new ArrayList<>();

        @Override public void change(Change change) {}

        @Override public void unconfirmed(Change change) {}

        @Override public void attempt(String tried, String whatHappened) {
            lines.add(tried + "：" + whatHappened);
        }
    }

    /** 终端里铁锭那一条：网络里还有 stored 件、标着有样板。 */
    private static Ae2TerminalMenu.StockEntry ironInNetwork(long stored) {
        return new Ae2TerminalMenu.StockEntry(42, new ItemStack(Items.IRON_INGOT), stored, true);
    }

    private static NetworkCraft craft(FakeCraftPages pages, Attempts attempts) {
        return new NetworkCraft(new Ae2Compat(CraftOrderingTest.NO_TERMINALS, pages, new NoGrid()),
                CraftOrderingTest.AT_ONCE, WHERE, attempts);
    }

    // 下单那几页一直走到不再是"还在下单"为止。
    private static NetworkCraft.Next order(NetworkCraft craft, FakeCraftPages pages, NetworkCraft.Next first) {
        NetworkCraft.Next next = first;
        for (int i = 0; i < 50 && next instanceof NetworkCraft.Ordering; i++) next = craft.ordering(pages.current);
        return next;
    }

    @Test
    void 整条_取完现货下单补缺口_重开终端等成品分两批取够() {
        FakeCraftPages pages = new FakeCraftPages();
        Attempts attempts = new Attempts();
        NetworkCraft craft = craft(pages, attempts);
        // 要 64 个，网络里只有 20 个：取货动作先把 20 个取走，终端里那一条剩 0、还标着有样板。
        int taken = 20;
        pages.stock.add(ironInNetwork(0));
        NetworkCraft.Next next = craft.noStock(pages.current, pages.stock, IRON, taken, 64 - taken, ROOMY);
        assertInstanceOf(NetworkCraft.Ordering.class, next, "还缺 44 个、有样板：在终端里下单");
        assertInstanceOf(NetworkCraft.Reopen.class, order(craft, pages, next), "开工了，退回的终端要重开");
        assertEquals(List.of(42L), pages.craftRequests);
        assertEquals(List.of(44), pages.confirmedAmounts, "下的是还缺的件数");
        assertEquals(List.of("在 " + WHERE + " 的网络下单合成 44 个 minecraft:iron_ingot：开工了"), attempts.lines);
        assertTrue(craft.involved(), "下过单，取货动作的总期限放宽");

        // 重开终端：成品还没出来，在跟前等，不再下第二单；AE2 记着这一单在做，取货动作照常报进展。
        NetworkCraft.Waiting waiting = assertInstanceOf(NetworkCraft.Waiting.class,
                craft.noStock(pages.terminal, pages.stock, IRON, taken, 44, ROOMY));
        assertTrue(waiting.jobRunning());
        assertTrue(craft.waiting());
        assertEquals("等网络做好 44 个 minecraft:iron_ingot（到了 0 个）", craft.waitingText());
        // 第一批 30 个进了网络、被取走；接着等剩下的 14 个。
        taken += 30;
        assertInstanceOf(NetworkCraft.Waiting.class, craft.noStock(pages.terminal, pages.stock, IRON, taken, 14, ROOMY));
        // 第二批 14 个也取走了，网络里也没了：拿够就不再等这一单。
        taken += 14;
        assertInstanceOf(NetworkCraft.Nothing.class, craft.noStock(pages.terminal, pages.stock, IRON, taken, 0, ROOMY));
        assertEquals(1, pages.craftRequests.size(), "整个过程只下了一单");
    }

    @Test
    void 背包只装得下一部分_按装得下的下单() {
        FakeCraftPages pages = new FakeCraftPages();
        pages.stock.add(ironInNetwork(0));
        NetworkCraft craft = craft(pages, new Attempts());
        order(craft, pages, craft.noStock(pages.current, pages.stock, IRON, 0, 100, sample -> 10));
        assertEquals(List.of(10), pages.confirmedAmounts, "做出来拿不走的不下");
    }

    @Test
    void 背包一件都装不下_不下单_引擎腾出地方还能接着来() {
        FakeCraftPages pages = new FakeCraftPages();
        pages.stock.add(ironInNetwork(0));
        NetworkCraft.Stop stop = assertInstanceOf(NetworkCraft.Stop.class,
                craft(pages, new Attempts()).noStock(pages.current, pages.stock, IRON, 0, 8, sample -> 0));
        assertEquals(Problem.Kind.INVENTORY_FULL, stop.problem().kind());
        assertFalse(stop.evenIfTaken());
        assertTrue(pages.craftRequests.isEmpty());
    }

    @Test
    void 没样板_照旧说网络里没有了() {
        FakeCraftPages pages = new FakeCraftPages();
        pages.stock.add(new Ae2TerminalMenu.StockEntry(42, new ItemStack(Items.IRON_INGOT), 0, false));
        assertInstanceOf(NetworkCraft.Nothing.class,
                craft(pages, new Attempts()).noStock(pages.current, pages.stock, IRON, 0, 8, ROOMY));
        assertTrue(pages.craftRequests.isEmpty());
    }

    @Test
    void 自己之前下的还在做_不再下单_等不到说清还在跑() {
        FakeCraftPages pages = new FakeCraftPages();
        pages.stock.add(ironInNetwork(0));
        pages.pending = true;
        Attempts attempts = new Attempts();
        NetworkCraft craft = craft(pages, attempts);
        assertInstanceOf(NetworkCraft.Waiting.class, craft.noStock(pages.current, pages.stock, IRON, 0, 8, ROOMY));
        NetworkCraft.Next next = new NetworkCraft.Waiting(true);
        for (int i = 0; i < CraftWait.IDLE_TICKS && next instanceof NetworkCraft.Waiting; i++) {
            next = craft.noStock(pages.current, pages.stock, IRON, 0, 8, ROOMY);
        }
        NetworkCraft.Stop stop = assertInstanceOf(NetworkCraft.Stop.class, next);
        assertTrue(pages.craftRequests.isEmpty(), "被打断回来、上次等到期限再来，都不重复下单");
        assertTrue(attempts.lines.isEmpty(), "没下单，不记下单");
        assertEquals(WHERE + " 的网络里还在做之前下单的 minecraft:iron_ingot，这次等到 0 个，两分钟没再出新的"
                + "；作业还在网络里跑，做好会进网络", stop.problem().message());
        assertTrue(stop.problem().suggestion().contains("不会重复下单"), stop.problem().suggestion());
        assertTrue(stop.evenIfTaken(), "等不到成品：引擎划掉这条路，不再回来白等");
    }

    @Test
    void 缺料没开工_照抄缺什么_取到现货也按失败交回() {
        FakeCraftPages pages = new FakeCraftPages();
        pages.startsAtOnce = false;
        pages.plan = new Ae2TerminalMenu.CraftPlanPage(true, List.of("minecraft:raw_iron ×44"), Optional.empty());
        pages.stock.add(ironInNetwork(0));
        Attempts attempts = new Attempts();
        NetworkCraft craft = craft(pages, attempts);
        NetworkCraft.Stop stop = assertInstanceOf(NetworkCraft.Stop.class,
                order(craft, pages, craft.noStock(pages.current, pages.stock, IRON, 20, 44, ROOMY)));
        assertEquals(Problem.Kind.NEED_ITEM, stop.problem().kind());
        assertEquals("已在 " + WHERE + " 的网络下单合成 44 个 minecraft:iron_ingot，网络算了料，缺：minecraft:raw_iron ×44",
                stop.problem().message());
        assertEquals("缺的料补进网络，或换别的途径", stop.problem().suggestion());
        assertTrue(stop.evenIfTaken(), "引擎划掉这条路去手搓，不回来再下一次一样缺料的单");
        assertEquals(List.of("在 " + WHERE + " 的网络下单合成 44 个 minecraft:iron_ingot：缺料，没开工"), attempts.lines,
                "缺的清单只在问题里写一遍");
        assertInstanceOf(NetworkCraft.Nothing.class, craft.noStock(pages.current, pages.stock, IRON, 20, 44, ROOMY),
                "一次取货只下一单");
    }

    @Test
    void AE2报这单结束_网络里没有更多_不等满两分钟() {
        FakeCraftPages pages = new FakeCraftPages();
        pages.stock.add(ironInNetwork(0));
        NetworkCraft craft = craft(pages, new Attempts());
        order(craft, pages, craft.noStock(pages.current, pages.stock, IRON, 0, 16, ROOMY));
        assertInstanceOf(NetworkCraft.Waiting.class, craft.noStock(pages.terminal, pages.stock, IRON, 0, 16, ROOMY));
        // 这一单被取消了（或成品被输出总线抽走）：AE2 不再记着它，网络里也一直没有。
        pages.pending = false;
        NetworkCraft.Next next = new NetworkCraft.Waiting(true);
        int ticks = 0;
        while (next instanceof NetworkCraft.Waiting) {
            ticks++;
            next = craft.noStock(pages.terminal, pages.stock, IRON, 0, 16, ROOMY);
        }
        assertEquals(CraftWait.ENDED_SETTLE_TICKS + 1, ticks, "等两秒存货同步过来就收场");
        NetworkCraft.Stop stop = assertInstanceOf(NetworkCraft.Stop.class, next);
        assertTrue(stop.problem().message().startsWith("已在 " + WHERE + " 的网络下单合成 16 个 minecraft:iron_ingot，到了 0 个；"
                + "AE2 报这单已经结束"), stop.problem().message());
    }
}
