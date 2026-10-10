// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.maiwithu.maicraft.game.player.BackpackStack;

import java.util.Map;
import java.util.Set;

/**
 * 物品价值分级的判断：给一格物品、这次任务要留的东西、背包里各种物品的总数，回答它值哪一级。
 *
 * <p>纯函数，只读输入；垃圾的判断要看背包里这种物品一共有多少——超过一组的圆石才是垃圾，
 * 一组以内还是建材，所以总数由调用方按背包视图数好传进来。
 */
final class ItemWorth {

    /**
     * 垃圾物品：堆多了就一文不值的普通方块，和怎么都不值得留的腐肉。
     * 圆石、泥土、沙砾超过一组才算垃圾；腐肉随时都是。
     */
    private static final Set<String> JUNK_ITEM_IDS = Set.of(
            "minecraft:cobblestone",
            "minecraft:dirt",
            "minecraft:gravel",
            "minecraft:rotten_flesh");
    private static final String ROTTEN_FLESH = "minecraft:rotten_flesh";

    private ItemWorth() {}

    /**
     * 这一格物品值哪一级。顺序就是"先看更要紧的"：任务留用的压过一切，
     * 装备、贵重依次往下，垃圾压过食物和建材——腐肉虽然能吃，也没人把它当口粮留着。
     */
    static Worth of(BackpackStack stack, Set<String> reservedItemIds, Map<String, Integer> totalsInBackpack) {
        if (reservedItemIds.contains(stack.itemId())) {
            return Worth.TASK_RESERVED;
        }
        if (stack.gear()) {
            return Worth.GEAR;
        }
        if (stack.precious()) {
            return Worth.PRECIOUS;
        }
        if (isJunk(stack, totalsInBackpack)) {
            return Worth.JUNK;
        }
        if (stack.food()) {
            return Worth.FOOD;
        }
        if (stack.buildingMaterial()) {
            return Worth.BUILDING_MATERIAL;
        }
        return Worth.ORDINARY_LOOT;
    }

    // 垃圾的账要算背包里的总数：圆石攒了三组，第三组往外的才是垃圾；一组以内还能搭桥垫脚。
    private static boolean isJunk(BackpackStack stack, Map<String, Integer> totalsInBackpack) {
        if (!JUNK_ITEM_IDS.contains(stack.itemId())) {
            return false;
        }
        if (stack.itemId().equals(ROTTEN_FLESH)) {
            return true;
        }
        int total = totalsInBackpack.getOrDefault(stack.itemId(), 0);
        return total > stack.maxStackSize();
    }
}
