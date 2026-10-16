// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 逐格摆料的计划：配方簿点不了（配方还没解锁、原料带附魔或改过名配方簿不认）时，像玩家一样自己往合成格里摆。
 * 先按配方的形状定每格放什么，再从身上挑料：一格只从一堆料里放，左键拿起那一堆、右键往要它的各格各放几件，
 * 剩下的放回原格。
 *
 * <p>只排"哪格从背包哪格拿、每格放几件"，不碰界面；身上哪堆料放得进哪一格，由调用方按原生配料判断后交进来。
 */
final class CraftingGridPlan {

    private CraftingGridPlan() {}

    /** 背包里的一堆料：界面上的格号、几件、一格最多叠几件。 */
    record Stack(int slot, int count, int maxStack) {}

    /** 一次拿起：从背包 sourceSlot 拿起整堆，往 cells（合成格的界面格号）各放 perCell 件，剩下 leftover 件放回原格。 */
    record Pickup(int sourceSlot, List<Integer> cells, int perCell, int leftover) {}

    /**
     * 配方的形状落到合成格上：返回配方第 i 位原料落在合成格的第几格（从 0 起、按行排），空位为 -1；摆不下给空。
     * 有形状的配方从左上角摆起，按配方的宽对格（照镜像也算的配方摆原样就对）；无形状的依次填格。
     *
     * @param shaped   有没有形状
     * @param width    有形状时配方的宽
     * @param height   有形状时配方的高
     * @param empty    配方第 i 位是不是空位（有形状配方里的空格）；无形状配方的空位也跳过
     */
    static Optional<int[]> layout(boolean shaped, int width, int height, List<Boolean> empty,
            int gridWidth, int gridHeight) {
        int[] cells = new int[empty.size()];
        if (shaped) {
            if (width < 1 || height < 1 || width > gridWidth || height > gridHeight || empty.size() != width * height) {
                return Optional.empty();
            }
            for (int i = 0; i < cells.length; i++) {
                cells[i] = empty.get(i) ? -1 : (i / width) * gridWidth + i % width;
            }
            return Optional.of(cells);
        }
        int next = 0;
        for (int i = 0; i < cells.length; i++) {
            if (empty.get(i)) {
                cells[i] = -1;
                continue;
            }
            if (next >= gridWidth * gridHeight) return Optional.empty();
            cells[i] = next++;
        }
        return next == 0 ? Optional.empty() : Optional.of(cells);
    }

    /**
     * 挑料：每格放几件尽量多做几份（不超过 wanted 份），身上的料不够就往少里退，一份都摆不满给空。
     *
     * @param cellOptions 每个要放东西的合成格（界面格号）能从哪几堆料里拿，按格号排
     * @param stacks      身上的料
     * @param wanted      这一轮想做几份
     */
    static Optional<List<Pickup>> plan(Map<Integer, List<Integer>> cellOptions, List<Stack> stacks, int wanted) {
        if (cellOptions.isEmpty() || wanted < 1) return Optional.empty();
        Map<Integer, Stack> bySlot = new HashMap<>();
        for (Stack stack : stacks) bySlot.put(stack.slot(), stack);
        for (int perCell = wanted; perCell >= 1; perCell--) {
            Optional<Map<Integer, Integer>> assigned = assign(cellOptions, bySlot, perCell);
            if (assigned.isPresent()) return Optional.of(pickups(assigned.get(), bySlot, perCell));
        }
        return Optional.empty();
    }

    // 每格分一堆料：可选的料最少的格子先挑（免得它要的那堆被别的格子先拿光），挑剩得最多、叠得下 perCell 件的那堆。
    private static Optional<Map<Integer, Integer>> assign(Map<Integer, List<Integer>> cellOptions,
            Map<Integer, Stack> bySlot, int perCell) {
        Map<Integer, Integer> remaining = new HashMap<>();
        for (Stack stack : bySlot.values()) remaining.put(stack.slot(), stack.count());
        List<Integer> cells = new ArrayList<>(cellOptions.keySet());
        cells.sort(Comparator.<Integer>comparingInt(cell -> cellOptions.get(cell).size()).thenComparingInt(cell -> cell));
        Map<Integer, Integer> sourceOf = new HashMap<>();
        for (int cell : cells) {
            Integer best = null;
            for (int slot : cellOptions.get(cell)) {
                Stack stack = bySlot.get(slot);
                if (stack == null || stack.maxStack() < perCell || remaining.getOrDefault(slot, 0) < perCell) continue;
                if (best == null || remaining.get(slot) > remaining.get(best)
                        || remaining.get(slot).equals(remaining.get(best)) && slot < best) {
                    best = slot;
                }
            }
            if (best == null) return Optional.empty();
            remaining.merge(best, -perCell, Integer::sum);
            sourceOf.put(cell, best);
        }
        return Optional.of(sourceOf);
    }

    // 同一堆料要放的几格并成一次拿起，按格号先后放；拿起的先后按第一格的格号排。
    private static List<Pickup> pickups(Map<Integer, Integer> sourceOf, Map<Integer, Stack> bySlot, int perCell) {
        Map<Integer, List<Integer>> cellsBySource = new LinkedHashMap<>();
        sourceOf.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> cellsBySource.computeIfAbsent(entry.getValue(), slot -> new ArrayList<>())
                        .add(entry.getKey()));
        List<Pickup> pickups = new ArrayList<>();
        for (var entry : cellsBySource.entrySet()) {
            int used = entry.getValue().size() * perCell;
            pickups.add(new Pickup(entry.getKey(), List.copyOf(entry.getValue()), perCell,
                    bySlot.get(entry.getKey()).count() - used));
        }
        return List.copyOf(pickups);
    }
}
