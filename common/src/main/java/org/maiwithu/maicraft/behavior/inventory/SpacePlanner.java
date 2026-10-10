// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.maiwithu.maicraft.behavior.retry.QuestionEscalation;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.result.Problem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 腾挪计划：看着背包现在有什么、要腾几格，排出"先动哪一堆"的清单。
 *
 * <p>纯判断，不碰游戏。顺序照真人玩家的做法：能合的散堆先合起来；然后从最不值钱的开始
 * 往外挪——随身背包能塞就塞，附近有记得的容器就存进去，都没有才丢；一旦要动到贵重一级，
 * 就停下来问，不自作主张。任务贵重品与装备永远不在计划里。
 */
final class SpacePlanner {

    private SpacePlanner() {}

    /**
     * 一次腾挪要看的现场。
     *
     * @param stacks                    背包主格里现在的物品堆
     * @param freeSlots                 还空着几格
     * @param slotsNeeded               要腾出几格
     * @param reservedItemIds           这次任务要留用的物品注册 ID
     * @param stackMergerAvailable      合并散堆的接缝接上了没有
     * @param carriedBackpackFreeSlots  随身背包还空着几格；没有随身背包时为 0
     * @param nearbyContainers          记得的附近容器；接缝没接上时为空
     */
    record Scene(
            List<BackpackStack> stacks,
            int freeSlots,
            int slotsNeeded,
            Set<String> reservedItemIds,
            boolean stackMergerAvailable,
            int carriedBackpackFreeSlots,
            List<KnownContainer> nearbyContainers) {
    }

    /**
     * 排好的计划：moves 是照顺序执行的腾挪；还差得动贵重品才够时给 question，
     * 连动贵重品都不够时给 problem。三者至少有一样。
     */
    record Plan(List<SpaceMove> moves, Question question, Problem problem) {
    }

    static Plan plan(Scene scene) {
        int slotsFreed = scene.freeSlots();
        if (slotsFreed >= scene.slotsNeeded) {
            throw new IllegalArgumentException("还有空格就不需要计划腾挪");
        }

        List<SpaceMove> moves = new ArrayList<>();
        slotsFreed += addMergeMoves(scene, moves);

        // 挪东西从最不值钱的开始：垃圾、普通掉落、建材、食物依次往后，贵重及以上不进计划。
        List<BackpackStack> movable = movableStacksWorthLeastFirst(scene);
        int containerIndex = 0;
        int carriedRoom = scene.carriedBackpackFreeSlots();
        for (BackpackStack stack : movable) {
            if (slotsFreed >= scene.slotsNeeded) {
                return new Plan(List.copyOf(moves), null, null);
            }
            // 随身背包有空就先放进去，满了再存记得的容器，都不行才丢——附近有箱子就不往地上扔。
            if (carriedRoom > 0) {
                moves.add(new SpaceMove.ToCarriedBackpack(stack));
                carriedRoom--;
            } else if (containerIndex < scene.nearbyContainers().size()) {
                moves.add(new SpaceMove.ToKnownContainer(scene.nearbyContainers().get(containerIndex), stack));
                containerIndex++;
            } else {
                moves.add(new SpaceMove.DropStack(stack));
            }
            slotsFreed++;
        }
        if (slotsFreed >= scene.slotsNeeded) {
            return new Plan(List.copyOf(moves), null, null);
        }

        return belowPreciousNotEnough(scene, slotsFreed);
    }

    // 散堆合并：只数"真能并出来"的格子——数量小的并进装得下它的最大堆，并到没有一堆装得下另一堆为止。
    // 不能按"总数除以堆上限"的理想装袋数：那种数法会把需要拆堆才能达到的格子也算进来，合并的执行做不到。
    private static int addMergeMoves(Scene scene, List<SpaceMove> moves) {
        if (!scene.stackMergerAvailable()) {
            return 0;
        }
        Map<String, List<BackpackStack>> byItem = new LinkedHashMap<>();
        for (BackpackStack stack : scene.stacks()) {
            byItem.computeIfAbsent(stack.itemId(), ignored -> new ArrayList<>()).add(stack);
        }
        int freed = 0;
        for (List<BackpackStack> stacks : byItem.values()) {
            if (stacks.size() < 2) {
                continue;
            }
            List<Integer> counts = stacks.stream().map(BackpackStack::count).toList();
            int saved = achievableMerges(counts, stacks.getFirst().maxStackSize());
            if (saved > 0) {
                freed += saved;
                moves.add(new SpaceMove.MergeStacks(stacks.getFirst().itemId(), saved));
            }
        }
        return freed;
    }

    // 模拟合并的执行，数能省出几格：每次挑最小的一堆，并进装得下它的最大一堆；并不动了就停。
    // 模拟按同一种物品算（执行读端还比对物品组件，组件不同的散堆并不了，实际会比这里少并）。
    private static int achievableMerges(List<Integer> counts, int maxStackSize) {
        List<Integer> piles = new ArrayList<>(counts);
        int saved = 0;
        while (true) {
            piles.sort(Integer::compare);
            boolean merged = false;
            for (int small = 0; small < piles.size() && !merged; small++) {
                for (int big = piles.size() - 1; big > small; big--) {
                    if (piles.get(small) + piles.get(big) <= maxStackSize) {
                        piles.set(big, piles.get(small) + piles.get(big));
                        piles.remove(small);
                        saved++;
                        merged = true;
                        break;
                    }
                }
            }
            if (!merged) return saved;
        }
    }

    // 能自动挪的堆：贵重、装备与任务留用的都不动；从垃圾排起，同级的保持背包里的格子顺序。
    private static List<BackpackStack> movableStacksWorthLeastFirst(Scene scene) {
        Map<String, Integer> totals = totalsInBackpack(scene.stacks());
        record Entry(BackpackStack stack, int worthRank) {}
        List<Entry> entries = new ArrayList<>();
        for (BackpackStack stack : scene.stacks()) {
            Worth worth = ItemWorth.of(stack, scene.reservedItemIds(), totals);
            if (!worth.tooValuableToDrop() && worth != Worth.TASK_RESERVED) {
                entries.add(new Entry(stack, worth.ordinal()));
            }
        }
        // 稳定排序：同级之间不动格子顺序，计划才可复现、测试才说得清。
        entries.sort((a, b) -> Integer.compare(b.worthRank, a.worthRank));
        return entries.stream().map(Entry::stack).toList();
    }

    // 自动能动的都动了还不够：动贵重品能补上就问，补不上就只能少拿。
    private static Plan belowPreciousNotEnough(Scene scene, int slotsFreedAfterMoves) {
        int shortage = scene.slotsNeeded() - slotsFreedAfterMoves;
        Map<String, Integer> totals = totalsInBackpack(scene.stacks());
        int valuables = 0;
        BackpackStack cheapest = null;
        Worth cheapestWorth = null;
        for (BackpackStack stack : scene.stacks()) {
            Worth worth = ItemWorth.of(stack, scene.reservedItemIds(), totals);
            if (worth == Worth.TASK_RESERVED || !worth.tooValuableToDrop()) {
                continue;
            }
            valuables++;
            // 从要问的那几格里挑最便宜的：等级更贱的优先，同级里数量少的优先。
            if (cheapest == null
                    || worth.ordinal() > cheapestWorth.ordinal()
                    || (worth.ordinal() == cheapestWorth.ordinal() && stack.count() < cheapest.count())) {
                cheapest = stack;
                cheapestWorth = worth;
            }
        }
        if (valuables >= shortage) {
            return new Plan(List.of(),
                    QuestionEscalation.approval(
                            "背包放不下，丢掉不值钱的也腾不出 " + shortage + " 格",
                            "把 " + describe(cheapest) + " 丢掉或存起来"),
                    null);
        }
        // 循环正常走完说明能动的全在计划里：最多能腾到 freeSlots 加合并加全部可挪堆。
        int atMost = slotsFreedAfterMoves;
        return new Plan(List.of(), null, Problem.of(Problem.Kind.INVENTORY_FULL,
                "背包塞满了，连贵重品都算上也腾不出 " + scene.slotsNeeded() + " 格，最多腾出 " + atMost + " 格",
                "这次少拿 " + (scene.slotsNeeded() - atMost) + " 格的东西，或者先把手头的事做完"));
    }

    // 向 LLM 描述要动的那一堆：数量加物品名，例如"32 个 minecraft:iron_ingot"。
    private static String describe(BackpackStack stack) {
        return stack.count() + " 个 " + stack.itemId();
    }

    private static Map<String, Integer> totalsInBackpack(List<BackpackStack> stacks) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (BackpackStack stack : stacks) {
            totals.merge(stack.itemId(), stack.count(), Integer::sum);
        }
        return totals;
    }
}
