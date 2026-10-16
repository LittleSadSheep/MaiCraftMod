// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 配方簿点不了时逐格摆料：按形状对格、一格只从一堆拿、身上料够几份就摆几份、一份都不够如实给空。 */
class CraftingGridPlanTest {

    @Test
    void shapedRecipeStartsFromTheTopLeftCorner() {
        // 木镐 3×3：上一排三块木板，中、下两排正中一根木棍；落在工作台合成格的格子 0 1 2、4、7。
        List<Boolean> empty = List.of(false, false, false, true, false, true, true, false, true);
        int[] cells = CraftingGridPlan.layout(true, 3, 3, empty, 3, 3).orElseThrow();
        assertArrayEquals(new int[]{0, 1, 2, -1, 4, -1, -1, 7, -1}, cells);
    }

    @Test
    void narrowShapedRecipeKeepsItsRowsInAWiderGrid() {
        // 木棍 1×2：两块木板竖着摆。工作台三格宽，第二块落在第二排第一格（格子 3），不是紧挨着的格子 1。
        int[] cells = CraftingGridPlan.layout(true, 1, 2, List.of(false, false), 3, 3).orElseThrow();
        assertArrayEquals(new int[]{0, 3}, cells);
        int[] inInventory = CraftingGridPlan.layout(true, 1, 2, List.of(false, false), 2, 2).orElseThrow();
        assertArrayEquals(new int[]{0, 2}, inInventory, "背包合成格两格宽，第二块在格子 2");
    }

    @Test
    void shapedRecipeTooBigForTheGridHasNoLayout() {
        List<Boolean> nine = List.of(false, false, false, false, false, false, false, false, false);
        assertTrue(CraftingGridPlan.layout(true, 3, 3, nine, 2, 2).isEmpty(), "3×3 的配方摆不进背包的 2×2");
    }

    @Test
    void shapelessRecipeFillsCellsInOrder() {
        int[] cells = CraftingGridPlan.layout(false, 0, 0, List.of(false, true, false), 3, 3).orElseThrow();
        assertArrayEquals(new int[]{0, -1, 1}, cells);
        List<Boolean> five = List.of(false, false, false, false, false);
        assertTrue(CraftingGridPlan.layout(false, 0, 0, five, 2, 2).isEmpty(), "五样原料塞不进四格");
    }

    @Test
    void oneStackFeedsEveryCellThatWantsIt() {
        // 箱子：八格都要木板，身上一堆 20 块。想做 4 份：一次拿起，八格各放两件就是 16 块，做 2 份，剩 4 块放回。
        Map<Integer, List<Integer>> options = new LinkedHashMap<>();
        for (int cell : List.of(1, 2, 3, 4, 6, 7, 8, 9)) options.put(cell, List.of(30));
        List<CraftingGridPlan.Pickup> plan = CraftingGridPlan.plan(options,
                List.of(new CraftingGridPlan.Stack(30, 20, 64)), 4).orElseThrow();
        assertEquals(1, plan.size());
        assertEquals(new CraftingGridPlan.Pickup(30, List.of(1, 2, 3, 4, 6, 7, 8, 9), 2, 4), plan.getFirst());
    }

    @Test
    void theMostConstrainedCellPicksFirst() {
        // 格子 1 只收橡木木板（第 10 格那堆，只有 1 块），格子 2 收任意木板（两堆都行）：
        // 先让格子 1 拿走那一块橡木，格子 2 去拿云杉，不然格子 2 先拿光橡木、格子 1 就摆不上了。
        Map<Integer, List<Integer>> options = new LinkedHashMap<>();
        options.put(2, List.of(10, 11));
        options.put(1, List.of(10));
        List<CraftingGridPlan.Pickup> plan = CraftingGridPlan.plan(options, List.of(
                new CraftingGridPlan.Stack(10, 1, 64), new CraftingGridPlan.Stack(11, 1, 64)), 1).orElseThrow();
        assertEquals(List.of(new CraftingGridPlan.Pickup(10, List.of(1), 1, 0),
                new CraftingGridPlan.Pickup(11, List.of(2), 1, 0)), plan);
    }

    @Test
    void stacksThatDoNotStackHighLimitHowManyAtOnce() {
        // 蛋糕要三桶奶：桶一格只能放一个，想做两份也只能一份一份摆。
        Map<Integer, List<Integer>> options = new LinkedHashMap<>();
        options.put(1, List.of(20));
        options.put(2, List.of(21));
        List<CraftingGridPlan.Pickup> plan = CraftingGridPlan.plan(options, List.of(
                new CraftingGridPlan.Stack(20, 1, 1), new CraftingGridPlan.Stack(21, 1, 1)), 2).orElseThrow();
        assertTrue(plan.stream().allMatch(pickup -> pickup.perCell() == 1));
    }

    @Test
    void notEnoughForOneCraftHasNoPlan() {
        // 箱子要八块木板，身上只有七块：一份都摆不满，如实给空，由调用方说缺料。
        Map<Integer, List<Integer>> options = new LinkedHashMap<>();
        for (int cell : List.of(1, 2, 3, 4, 6, 7, 8, 9)) options.put(cell, List.of(30));
        assertTrue(CraftingGridPlan.plan(options, List.of(new CraftingGridPlan.Stack(30, 7, 64)), 1).isEmpty());
    }

    @Test
    void aCellNothingFitsHasNoPlan() {
        Map<Integer, List<Integer>> options = new LinkedHashMap<>();
        options.put(1, List.of(30));
        options.put(2, List.of());
        assertTrue(CraftingGridPlan.plan(options, List.of(new CraftingGridPlan.Stack(30, 64, 64)), 1).isEmpty());
    }
}
