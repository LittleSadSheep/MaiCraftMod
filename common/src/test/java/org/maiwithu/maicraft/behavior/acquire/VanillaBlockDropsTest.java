// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 原版方块掉落对照：矿石对上矿、石头掉圆石、泥土对上自己，玻璃什么也不掉。 */
class VanillaBlockDropsTest {

    @Test
    void oresPointAtTheirOreBlocks() {
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:coal")
                .contains("minecraft:coal_ore"));
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:coal")
                .contains("minecraft:deepslate_coal_ore"));
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:raw_iron")
                .contains("minecraft:iron_ore"));
    }

    @Test
    void stoneFamilyDropsTheCobbledForm() {
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:cobblestone")
                .contains("minecraft:stone"));
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:cobbled_deepslate")
                .contains("minecraft:deepslate"));
    }

    @Test
    void commonUndergroundStonesAreWorthDiggingDownFor() {
        // 要圆石、安山岩这类：看不见就往下挖楼梯去找；原木、铁锭不是挖楼梯挖得到的。
        assertTrue(VanillaBlockDrops.buriedUnderfoot("minecraft:cobblestone"));
        assertTrue(VanillaBlockDrops.buriedUnderfoot("minecraft:andesite"));
        assertTrue(VanillaBlockDrops.buriedUnderfoot("minecraft:cobbled_deepslate"));
        assertFalse(VanillaBlockDrops.buriedUnderfoot("minecraft:oak_log"));
        assertFalse(VanillaBlockDrops.buriedUnderfoot("minecraft:iron_ingot"));
    }

    @Test
    void blocksWithTheSameNameFallBackToThemselves() {
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:dirt")
                .contains("minecraft:dirt"));
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:dirt")
                .contains("minecraft:grass_block"));
        assertEquals("minecraft:sand", VanillaBlockDrops.droppedBy("minecraft:sand"));
    }

    @Test
    void blocksThatDropSomethingElseDoNotDropThemselves() {
        // 要石头、要钻石矿石：挖石头掉圆石、挖矿石掉矿物，都不是这次要的东西。
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:stone").isEmpty());
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:diamond_ore").isEmpty());
    }

    @Test
    void glassDropsNothingAtAll() {
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:glass").isEmpty());
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:ice").isEmpty());
    }
}
