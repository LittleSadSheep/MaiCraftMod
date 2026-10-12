// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.maiwithu.maicraft.game.player.BackpackStack;

import java.util.Map;
import java.util.Set;

/**
 * 物品价值分级的判断：给一格物品、这次任务要留的东西、背包里各种物品的总数，回答它值哪一级。
 *
 * <p>纯函数，只读输入；垃圾是哪几样按 {@link JunkItems} 这唯一一份，圆石要看背包里一共有多少——
 * 超过一组的才是垃圾，一组以内还是建材，所以总数由调用方按背包视图数好传进来。
 */
final class ItemWorth {

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
        // 顺手捡的零碎压过食物：生肉、蜘蛛眼能吃，可没人把它当口粮留着占格子。
        if (IncidentalLoot.is(stack.itemId())) {
            return Worth.INCIDENTAL;
        }
        if (stack.food()) {
            return Worth.FOOD;
        }
        if (stack.buildingMaterial()) {
            return Worth.BUILDING_MATERIAL;
        }
        // 其余的（做出来的零件、锭、机器方块、说不准的材料）都当有用的：不自作主张丢。
        return Worth.USEFUL;
    }

    // 垃圾的账要算背包里的总数：圆石攒了三组，第三组往外的才是垃圾；一组以内还能搭桥垫脚、做石器。
    private static boolean isJunk(BackpackStack stack, Map<String, Integer> totalsInBackpack) {
        return JunkItems.junkAt(stack.itemId(), totalsInBackpack.getOrDefault(stack.itemId(), 0), stack.maxStackSize());
    }
}
