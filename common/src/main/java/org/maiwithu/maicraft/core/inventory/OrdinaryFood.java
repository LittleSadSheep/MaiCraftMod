// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.inventory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 自动补食按实际食物组件筛选，并先扣掉工作材料；既不依赖固定菜名，也不把特殊效果食物当普通口粮。 */
public final class OrdinaryFood {
    private OrdinaryFood() {}

    public static boolean ordinary(ItemStack stack) {
        if (stack.isEmpty() || stack.is(Items.CHORUS_FRUIT) || stack.is(Items.SUSPICIOUS_STEW) || stack.is(Items.HONEY_BOTTLE)) return false;
        var food = stack.get(DataComponents.FOOD);
        var baseline = new ItemStack(stack.getItem()).get(DataComponents.FOOD);
        return food != null && food.equals(baseline) && food.nutrition() > 0 && food.effects().isEmpty()
                && !stack.has(DataComponents.POTION_CONTENTS) && !stack.has(DataComponents.SUSPICIOUS_STEW_EFFECTS);
    }

    /** 同 ID 中有特殊组件叠时整类排除，避免只按物品类型操作的原生进食任务拿错叠。 */
    public static Set<Item> unsafe(List<ItemStack> inventory) {
        var result = new HashSet<Item>();
        for (int i = 0; i < Math.min(36, inventory.size()); i++) {
            var stack = inventory.get(i);
            if (!stack.isEmpty() && !ordinary(stack)) result.add(stack.getItem());
        }
        return result;
    }

    public static Map<Item, Long> counts(List<ItemStack> inventory) {
        var result = new HashMap<Item, Long>();
        for (int i = 0; i < Math.min(36, inventory.size()); i++) {
            var stack = inventory.get(i);
            if (!stack.isEmpty()) result.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
        }
        return result;
    }

    public static Item choose(List<ItemStack> inventory, int food, FoodMaterialBudget budget) {
        Item best = null; double score = Double.NEGATIVE_INFINITY;
        var unsafe = unsafe(inventory);
        for (var entry : counts(inventory).entrySet()) {
            Item item = entry.getKey(); var stack = new ItemStack(item);
            if (unsafe.contains(item) || !ordinary(stack) || budget.spare(item, entry.getValue()) < 1) continue;
            double value = score(stack, food);
            if (value > score || value == score && before(item, best)) { best = item; score = value; }
        }
        return best;
    }

    public record StockChoice(Item item, int count) {}
    /** 网络数量只用于选候选；取出后仍要按真实组件复核。预留不足时连现货也不能挪作食物。 */
    public static StockChoice stock(Map<ResourceLocation, Long> stored, List<ItemStack> inventory, int food, FoodMaterialBudget budget) {
        StockChoice best = null; double score = Double.NEGATIVE_INFINITY;
        var unsafe = unsafe(inventory); var carried = counts(inventory);
        for (var entry : stored.entrySet()) {
            if (!BuiltInRegistries.ITEM.containsKey(entry.getKey()) || entry.getValue() <= 0) continue;
            Item item = BuiltInRegistries.ITEM.get(entry.getKey()); var stack = new ItemStack(item);
            if (unsafe.contains(item) || !ordinary(stack)) continue;
            long available = Math.min(entry.getValue(), budget.spare(item, saturatedAdd(entry.getValue(), carried.getOrDefault(item, 0L))));
            if (available < 1) continue;
            int nutrition = stack.get(DataComponents.FOOD).nutrition();
            int servings = (int) Math.min(available, Math.min(32, (26 - food + nutrition - 1) / nutrition));
            // 取物请求是背包最终数量；先把明确预留带齐，之后只吃高于这条库存底线的份数。
            long total = budget.reserved().getOrDefault(item, 0L) + servings;
            if (total > 2304) continue;
            int count = (int) total;
            double value = Math.min(20 - food, (long) servings * nutrition) * 100.0 + score(stack, food);
            if (value > score || value == score && before(item, best == null ? null : best.item())) {
                best = new StockChoice(item, count); score = value;
            }
        }
        return best;
    }

    private static long saturatedAdd(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
    private static boolean before(Item item, Item other) {
        return other == null || BuiltInRegistries.ITEM.getKey(item).compareTo(BuiltInRegistries.ITEM.getKey(other)) < 0;
    }
    private static double score(ItemStack stack, int food) {
        var nutrition = stack.get(DataComponents.FOOD);
        return Math.min(20 - food, nutrition.nutrition()) * 4.0 + nutrition.saturation() * 2.0;
    }
}
