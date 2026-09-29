// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.inventory;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.world.item.Item;

/** 已知数量先预留；尚未选定配方的食物原料整类保留，不能把未知工序需求当成零。 */
public record FoodMaterialBudget(Map<Item, Long> reserved, Set<Item> held, boolean complete) {
    public static final FoodMaterialBudget EMPTY = new FoodMaterialBudget(Map.of(), Set.of(), true);
    public FoodMaterialBudget { reserved = Map.copyOf(reserved); held = Set.copyOf(held); }
    public long spare(Item item, long count) {
        return !complete || held.contains(item) ? 0 : Math.max(0, count - reserved.getOrDefault(item, 0L));
    }

    /** 从整份计划的物品需求追踪配方原料；碰到循环只访问一次，超过预算则停止自动动用既有食物。 */
    public static FoodMaterialBudget plan(Map<Item, Long> exact, Set<Item> future, Function<Item, Set<Item>> ingredients) {
        var held = new HashSet<Item>(future); var visited = new HashSet<Item>();
        var queue = new ArrayDeque<Item>(); queue.addAll(exact.keySet()); queue.addAll(future);
        while (!queue.isEmpty()) {
            Item output = queue.removeFirst(); if (!visited.add(output)) continue;
            if (visited.size() > 4096) return new FoodMaterialBudget(exact, held, false);
            for (Item input : ingredients.apply(output)) if (input != output) {
                held.add(input); if (!visited.contains(input)) queue.addLast(input);
            }
        }
        return new FoodMaterialBudget(exact, held, true);
    }
}
