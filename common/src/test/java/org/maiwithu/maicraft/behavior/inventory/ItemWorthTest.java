// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.BackpackStack;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 物品价值分级：任务留用的最贵，垃圾压过食物和建材，但压不过装备与贵重品。 */
class ItemWorthTest {

    private static BackpackStack stack(String itemId, int count, boolean gear, boolean food,
                                       boolean precious, boolean building) {
        return new BackpackStack(itemId, count, 64, gear, food, precious, building);
    }

    @Test
    void 任务留用的东西压过一切() {
        BackpackStack pickaxe = stack("minecraft:diamond_pickaxe", 1, true, false, true, false);
        assertEquals(Worth.TASK_RESERVED,
                ItemWorth.of(pickaxe, Set.of("minecraft:diamond_pickaxe"), Map.of()));
    }

    @Test
    void 装备和贵重品各归各级() {
        assertEquals(Worth.GEAR,
                ItemWorth.of(stack("minecraft:iron_sword", 1, true, false, false, false), Set.of(), Map.of()));
        assertEquals(Worth.PRECIOUS,
                ItemWorth.of(stack("minecraft:ender_pearl", 4, false, false, true, false), Set.of(), Map.of()));
    }

    @Test
    void 超过一组的圆石是垃圾_一组以内是建材() {
        BackpackStack cobble = stack("minecraft:cobblestone", 64, false, false, false, true);
        assertEquals(Worth.JUNK,
                ItemWorth.of(cobble, Set.of(), Map.of("minecraft:cobblestone", 128)));
        assertEquals(Worth.BUILDING_MATERIAL,
                ItemWorth.of(cobble, Set.of(), Map.of("minecraft:cobblestone", 40)));
    }

    @Test
    void 腐肉随时都是垃圾_压过能吃这一条() {
        // 腐肉在游戏里带食物组件，但没人把它当口粮留着。
        assertEquals(Worth.JUNK,
                ItemWorth.of(stack("minecraft:rotten_flesh", 3, false, true, false, false), Set.of(), Map.of()));
    }

    @Test
    void 食物_有用的_零碎依次落级() {
        assertEquals(Worth.FOOD,
                ItemWorth.of(stack("minecraft:cooked_beef", 16, false, true, false, false), Set.of(), Map.of()));
        // 做出来的链条、桶不是"普通掉落"：有用的，不自作主张丢。
        assertEquals(Worth.USEFUL,
                ItemWorth.of(stack("minecraft:chain", 32, false, false, false, false), Set.of(), Map.of()));
        assertTrue(Worth.USEFUL.tooValuableToDrop());
        // 骨头、蜘蛛眼、生羊肉、树苗、蘑菇是顺手捡的零碎：生肉能吃也不当口粮留着。
        assertEquals(Worth.INCIDENTAL,
                ItemWorth.of(stack("minecraft:bone", 2, false, false, false, false), Set.of(), Map.of()));
        assertEquals(Worth.INCIDENTAL,
                ItemWorth.of(stack("minecraft:mutton", 2, false, true, false, false), Set.of(), Map.of()));
        assertEquals(Worth.INCIDENTAL,
                ItemWorth.of(stack("biomesoplenty:fir_sapling", 3, false, false, false, true), Set.of(), Map.of()));
        assertEquals(Worth.INCIDENTAL,
                ItemWorth.of(stack("minecraft:brown_mushroom", 1, false, false, false, false), Set.of(), Map.of()));
    }

    @Test
    void 任务要用的零碎不算零碎() {
        // 正在为过滤器打蜘蛛拿线：线是这次任务留用的。
        assertEquals(Worth.TASK_RESERVED,
                ItemWorth.of(stack("minecraft:string", 3, false, false, false, false), Set.of("minecraft:string"),
                        Map.of()));
    }

    @Test
    void 垃圾压不过装备和贵重品() {
        // 带附魔的铁镐即使堆了三组也还是贵重品；装备不会因为堆多了变垃圾。
        assertEquals(Worth.PRECIOUS,
                ItemWorth.of(stack("minecraft:enchanted_book", 1, false, false, true, false), Set.of(),
                        Map.of()));
    }
}
