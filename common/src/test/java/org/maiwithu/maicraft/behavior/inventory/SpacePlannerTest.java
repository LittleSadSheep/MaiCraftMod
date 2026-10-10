// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.result.Problem;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 腾挪计划：合并最先，能存的存、该丢的从垃圾丢起，要动贵重品才停下来问。 */
class SpacePlannerTest {

    private static final KnownContainer 家门口的箱子 = new KnownContainer("家门口的箱子", 12.5, -60, 208.5);

    private static BackpackStack stack(String itemId, int count) {
        return new BackpackStack(itemId, count, 64, false, false, false, false);
    }

    private static BackpackStack junk(String itemId, int count) {
        return new BackpackStack(itemId, count, 64, false, false, false, true);
    }

    private static SpacePlanner.Scene scene(List<BackpackStack> stacks, int freeSlots, int needed,
                                            boolean carried, List<KnownContainer> containers) {
        // 有随身背包时给它足够的空格，测试只关心先后顺序。
        return new SpacePlanner.Scene(stacks, freeSlots, needed, Set.of(), true, carried ? 27 : 0, containers);
    }

    @Test
    void 散堆先合并_合并不丢东西() {
        SpacePlanner.Plan plan = SpacePlanner.plan(scene(
                List.of(stack("minecraft:bone", 30), stack("minecraft:bone", 30), stack("minecraft:bone", 10)),
                0, 1, false, List.of()));
        assertEquals(1, plan.moves().size());
        SpaceMove.MergeStacks merge = (SpaceMove.MergeStacks) plan.moves().getFirst();
        assertEquals("minecraft:bone", merge.itemId());
        // 70 个骨头散在三格，合成两格（64+6）：腾出要的一格。
        assertEquals(1, merge.slotsFreed());
        assertNull(plan.question());
        assertNull(plan.problem());
    }

    @Test
    void 合并做不到的格子不计进计划() {
        // 三堆各 40 个：两两都并不进一格（40+40 超过上限），纯合并腾不出格子；
        // 计划不该指望合并，直接安排动能动的堆（垃圾级的圆石丢掉）。
        SpacePlanner.Plan plan = SpacePlanner.plan(scene(
                List.of(junk("minecraft:cobblestone", 40), junk("minecraft:cobblestone", 40),
                        junk("minecraft:cobblestone", 40)),
                0, 1, false, List.of()));
        assertTrue(plan.moves().stream().noneMatch(move -> move instanceof SpaceMove.MergeStacks));
        assertTrue(plan.moves().getFirst() instanceof SpaceMove.DropStack);
    }

    @Test
    void 有随身背包先往里塞() {
        SpacePlanner.Plan plan = SpacePlanner.plan(scene(
                List.of(stack("minecraft:bone", 3)), 0, 1, true, List.of()));
        assertEquals(new SpaceMove.ToCarriedBackpack(stack("minecraft:bone", 3)), plan.moves().getFirst());
    }

    @Test
    void 附近有记得的容器优先存_不往地上扔() {
        SpacePlanner.Plan plan = SpacePlanner.plan(scene(
                List.of(junk("minecraft:cobblestone", 64)), 0, 1, false, List.of(家门口的箱子)));
        assertEquals(new SpaceMove.ToKnownContainer(家门口的箱子, junk("minecraft:cobblestone", 64)),
                plan.moves().getFirst());
    }

    @Test
    void 都接不上才丢_且从最不值钱的丢起() {
        // 圆石攒了两堆（共 74 个，超过一组）才算垃圾；一堆以内的圆石是建材，食物比建材贵，都比垃圾贵。
        List<BackpackStack> stacks = List.of(
                junk("minecraft:cobblestone", 64),
                junk("minecraft:cobblestone", 10),
                stack("minecraft:cooked_beef", 8));
        SpacePlanner.Plan plan = SpacePlanner.plan(scene(stacks, 0, 1, false, List.of()));
        assertEquals(new SpaceMove.DropStack(junk("minecraft:cobblestone", 64)), plan.moves().getFirst());
        // 丢一整堆就腾够了一格，食物不用动。
        assertEquals(1, plan.moves().size());
    }

    @Test
    void 贵重品和装备永远不进计划() {
        BackpackStack sword = new BackpackStack("minecraft:iron_sword", 1, 1, true, false, false, false);
        BackpackStack pearl = new BackpackStack("minecraft:ender_pearl", 2, 16, false, false, true, false);
        SpacePlanner.Plan plan = SpacePlanner.plan(scene(List.of(sword, pearl), 0, 2, false, List.of()));
        assertTrue(plan.moves().isEmpty());
        // 只剩贵重品能动：停下来问，而不是自作主张丢掉。
        assertEquals(Question.Reason.NEED_APPROVAL, plan.question().reason());
    }

    @Test
    void 动贵重品的提问写清要动哪一堆() {
        BackpackStack pearl = new BackpackStack("minecraft:ender_pearl", 2, 16, false, false, true, false);
        SpacePlanner.Plan plan = SpacePlanner.plan(scene(List.of(pearl), 0, 1, false, List.of()));
        assertTrue(plan.question().text().contains("腾不出 1 格"));
        assertTrue(plan.question().options().stream().anyMatch(option -> option.id().equals("no")));
    }

    @Test
    void 连贵重品都算上也腾不出就只能少拿() {
        BackpackStack pearl = new BackpackStack("minecraft:ender_pearl", 2, 16, false, false, true, false);
        SpacePlanner.Plan plan = SpacePlanner.plan(scene(List.of(pearl), 0, 2, false, List.of()));
        assertNull(plan.question());
        assertEquals(Problem.Kind.INVENTORY_FULL, plan.problem().kind());
        assertTrue(plan.problem().message().contains("腾不出 2 格"));
    }

    @Test
    void 任务留用的东西不算进能动与能问的范围() {
        BackpackStack reserved = stack("minecraft:iron_ingot", 30);
        SpacePlanner.Scene withReserved = new SpacePlanner.Scene(
                List.of(reserved), 0, 1, Set.of("minecraft:iron_ingot"), true, 0, List.of());
        SpacePlanner.Plan plan = SpacePlanner.plan(withReserved);
        // 唯一的物品是任务要用的：既不自动动它，也不把它列进要问的贵重品。
        assertNull(plan.question());
        assertEquals(Problem.Kind.INVENTORY_FULL, plan.problem().kind());
    }

    @Test
    void 随身背包满了接着存箱子_再不行才丢() {
        // 随身背包只剩一格：第一堆放进去，第二堆存进记得的箱子，第三堆才丢。
        List<BackpackStack> stacks = List.of(junk("minecraft:dirt", 64), junk("minecraft:gravel", 64),
                junk("minecraft:sand", 64));
        SpacePlanner.Scene scene = new SpacePlanner.Scene(stacks, 0, 3, Set.of(), false, 1,
                List.of(new KnownContainer("门口的箱子", 1, 64, 1)));
        List<SpaceMove> moves = SpacePlanner.plan(scene).moves();
        assertTrue(moves.get(0) instanceof SpaceMove.ToCarriedBackpack);
        assertTrue(moves.get(1) instanceof SpaceMove.ToKnownContainer);
        assertTrue(moves.get(2) instanceof SpaceMove.DropStack);
    }
}
