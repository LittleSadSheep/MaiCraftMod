// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 垫脚挑料：垃圾方块先垫，圆石最后；正要用的留够数，多出来的才垫。 */
class ScaffoldBlocksTest {

    @Test
    void 泥土废石先垫_圆石排最后_不是垫脚料的不垫() {
        Map<String, Integer> carried = Map.of("minecraft:cobblestone", 20, "minecraft:dirt", 5,
                "minecraft:andesite", 3, "minecraft:oak_planks", 30, "minecraft:gravel", 2);

        assertEquals(List.of("minecraft:dirt", "minecraft:andesite", "minecraft:gravel", "minecraft:cobblestone"),
                ScaffoldBlocks.usable(itemId -> carried.getOrDefault(itemId, 0), ScaffoldBlocks.Keeps.NOTHING));
    }

    @Test
    void 刚挖来要用的三块圆石不垫_多出来的才垫() {
        // 实机：挖了三块圆石做石镐，爬上来时又把这三块垫了下去。
        ScaffoldBlocks.Keeps keepThree = itemId -> itemId.equals("minecraft:cobblestone") ? 3 : 0;

        assertEquals(List.of(), ScaffoldBlocks.usable(
                itemId -> itemId.equals("minecraft:cobblestone") ? 3 : 0, keepThree));
        assertEquals(List.of("minecraft:cobblestone"), ScaffoldBlocks.usable(
                itemId -> itemId.equals("minecraft:cobblestone") ? 5 : 0, keepThree));
    }
}
