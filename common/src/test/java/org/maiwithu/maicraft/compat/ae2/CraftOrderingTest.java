// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Problem;

/** 在终端里下单合成那几页：每页只点一下，本刻点不出去下一刻再点；开工、缺料、被拒、算不完、界面被关各自如实收场。 */
class CraftOrderingTest {

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 终端里铁锭那一条：现货是零、标着有样板。用到时再建，物品注册表要先引导好。 */
    private static Ae2TerminalMenu.StockEntry ironIngots() {
        return new Ae2TerminalMenu.StockEntry(42, new ItemStack(Items.IRON_INGOT), 0, true);
    }

    /** 附近没有终端的读写端：下单只用到终端界面这一头。 */
    static final Ae2Terminals NO_TERMINALS = new Ae2Terminals() {
        @Override public List<Ae2Terminals.Terminal> near(ClientLevel level, BlockPos center, int radius) {
            return List.of();
        }

        @Override public boolean stillThere(ClientLevel level, BlockPos block, Direction side) {
            return false;
        }
    };

    /** 每次都发得出去的发包：当刻就发。 */
    static final CraftOrdering.PageSender AT_ONCE = (what, send) -> {
        send.run();
        return true;
    };

    private static CraftOrdering ordering(FakeCraftPages pages, CraftOrdering.PageSender sender, int amount) {
        return new CraftOrdering(new Ae2Compat(NO_TERMINALS, pages, new NoGrid()), sender, ironIngots(), amount);
    }

    // 一直推进到不再是"还在走"为止，最多 ticks 刻；返回最后一刻的结论。
    private static CraftOrdering.Step run(CraftOrdering ordering, FakeCraftPages pages, int ticks) {
        CraftOrdering.Step step = new CraftOrdering.Running();
        for (int i = 0; i < ticks && step instanceof CraftOrdering.Running; i++) step = ordering.tick(pages.current);
        return step;
    }

    @Test
    void 料齐_点合成填数量就开工_每页只点一下() {
        FakeCraftPages pages = new FakeCraftPages();
        CraftOrdering ordering = ordering(pages, AT_ONCE, 44);
        assertInstanceOf(CraftOrdering.Started.class, run(ordering, pages, 20));
        assertEquals(List.of(42L), pages.craftRequests);
        assertEquals(List.of(44), pages.confirmedAmounts);
    }

    @Test
    void 本刻点不出去_下一刻再点_不多点() {
        FakeCraftPages pages = new FakeCraftPages();
        int[] refusals = {3};
        CraftOrdering.PageSender busy = (what, send) -> {
            if (refusals[0]-- > 0) return false;
            send.run();
            return true;
        };
        CraftOrdering ordering = ordering(pages, busy, 5);
        assertInstanceOf(CraftOrdering.Started.class, run(ordering, pages, 20));
        assertEquals(1, pages.craftRequests.size());
        assertEquals(List.of(5), pages.confirmedAmounts);
    }

    @Test
    void 缺料_照抄缺的每一样() {
        FakeCraftPages pages = new FakeCraftPages();
        pages.startsAtOnce = false;
        pages.plan = new Ae2TerminalMenu.CraftPlanPage(true, List.of("minecraft:iron_ingot ×3", "ae2:printed_silicon ×1"),
                Optional.empty());
        CraftOrdering.Stopped stopped = assertInstanceOf(CraftOrdering.Stopped.class, run(ordering(pages, AT_ONCE, 4),
                pages, 20));
        assertEquals(Problem.Kind.NEED_ITEM, stopped.kind());
        assertEquals("网络算了料，缺：minecraft:iron_ingot ×3、ae2:printed_silicon ×1", stopped.reason());
    }

    @Test
    void 合成CPU都用不上_照抄几台忙几台太小() {
        FakeCraftPages pages = new FakeCraftPages();
        pages.startsAtOnce = false;
        pages.plan = new Ae2TerminalMenu.CraftPlanPage(true, List.of(),
                Optional.of(new Ae2TerminalMenu.CraftRefusal("NO_SUITABLE_CPU_FOUND", 2, 1, 0, "")));
        CraftOrdering.Stopped stopped = assertInstanceOf(CraftOrdering.Stopped.class, run(ordering(pages, AT_ONCE, 4),
                pages, 20));
        assertEquals(Problem.Kind.REFUSED_BY_GAME, stopped.kind());
        assertEquals("AE2 没开工：网络里的合成 CPU 都用不上（2 台在忙、1 台存储太小）", stopped.reason());
    }

    @Test
    void 还在算就等_三十秒算不完如实说() {
        FakeCraftPages pages = new FakeCraftPages();
        pages.startsAtOnce = false;
        CraftOrdering ordering = ordering(pages, AT_ONCE, 4);
        assertInstanceOf(CraftOrdering.Running.class, run(ordering, pages, CraftOrdering.CALCULATE_TICKS),
                "大网络算得慢，三十秒内都等着，不说成 CPU 在忙");
        CraftOrdering.Stopped stopped = assertInstanceOf(CraftOrdering.Stopped.class, run(ordering, pages, 5));
        assertEquals(Problem.Kind.STUCK, stopped.kind());
        assertTrue(stopped.reason().contains("还没算完"), stopped.reason());
    }

    @Test
    void 下单的界面被关上_AE2记着这单就算开工_没记着如实说() {
        FakeCraftPages pages = new FakeCraftPages();
        pages.startsAtOnce = false;
        CraftOrdering ordering = ordering(pages, AT_ONCE, 4);
        run(ordering, pages, 5);
        pages.current = pages.elsewhere;
        pages.pending = true;
        assertInstanceOf(CraftOrdering.Started.class, ordering.tick(pages.current));

        FakeCraftPages closed = new FakeCraftPages();
        closed.startsAtOnce = false;
        CraftOrdering unsure = ordering(closed, AT_ONCE, 4);
        run(unsure, closed, 5);
        closed.current = closed.elsewhere;
        CraftOrdering.Stopped stopped = assertInstanceOf(CraftOrdering.Stopped.class, unsure.tick(closed.current));
        assertEquals("下单的界面被关上了，没能确认开没开工", stopped.reason());
    }

    @Test
    void 点了合成没打开填数量页_到期限收场() {
        FakeCraftPages pages = new FakeCraftPages();
        pages.ignoresCraftRequest = true;
        CraftOrdering.Stopped stopped = assertInstanceOf(CraftOrdering.Stopped.class,
                run(ordering(pages, AT_ONCE, 4), pages, CraftOrdering.PAGE_TICKS + 5));
        assertEquals("点了合成，没打开填数量的界面", stopped.reason());
        assertEquals(1, pages.craftRequests.size(), "没反应也不再点第二下");
    }

    @Test
    void AE2拒绝开工的原因逐种写成一句() {
        assertEquals("网络里没有合成 CPU",
                CraftOrdering.refusalText(new Ae2TerminalMenu.CraftRefusal("NO_CPU_FOUND", 0, 0, 0, "")));
        assertEquals("网络里的合成 CPU 都用不上（1 台断电或缺频道）",
                CraftOrdering.refusalText(new Ae2TerminalMenu.CraftRefusal("NO_SUITABLE_CPU_FOUND", 0, 0, 1, "")));
        assertEquals("开工时缺料：minecraft:redstone ×2（算完以后被取走了）",
                CraftOrdering.refusalText(new Ae2TerminalMenu.CraftRefusal("MISSING_INGREDIENT", 0, 0, 0,
                        "minecraft:redstone ×2")));
        assertEquals("AE2 拒绝开工（SOMETHING_NEW）",
                CraftOrdering.refusalText(new Ae2TerminalMenu.CraftRefusal("SOMETHING_NEW", 0, 0, 0, "")));
    }
}
