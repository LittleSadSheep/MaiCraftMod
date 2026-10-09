// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void blocksWithTheSameNameFallBackToThemselves() {
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:dirt")
                .contains("minecraft:dirt"));
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:dirt")
                .contains("minecraft:grass_block"));
        assertEquals("minecraft:sand", VanillaBlockDrops.droppedBy("minecraft:sand"));
    }

    @Test
    void glassDropsNothingAtAll() {
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:glass").isEmpty());
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:ice").isEmpty());
    }
}
