// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

/** 长程挖矿挖到哪一层：在矿最多那层上面就挖到那层；在那层或以下、还在范围里就原地横挖；在范围以下不挖。 */
class OreHeightsTest {

    @Test
    void 所有者给的几种矿最多的那一层() {
        assertEquals(16, OreHeights.of("minecraft:iron_ore").orElseThrow().peakY());
        assertEquals(16, OreHeights.of("minecraft:deepslate_iron_ore").orElseThrow().peakY(), "深层矿同一份");
        assertEquals(-16, OreHeights.of("minecraft:gold_ore").orElseThrow().peakY());
        assertEquals(-59, OreHeights.of("minecraft:diamond_ore").orElseThrow().peakY());
        assertEquals(-58, OreHeights.of("minecraft:redstone_ore").orElseThrow().peakY());
        assertEquals(0, OreHeights.of("minecraft:lapis_ore").orElseThrow().peakY());
        assertEquals(48, OreHeights.of("minecraft:copper_ore").orElseThrow().peakY());
        assertTrue(OreHeights.of("create:zinc_ore").isEmpty(), "模组矿不知道在哪层，不下探");
    }

    @Test
    void 在上面挖到那层_在范围里原地横挖_在范围以下不挖() {
        OreHeights.Band iron = OreHeights.of("minecraft:iron_ore").orElseThrow();
        assertEquals(OptionalInt.of(16), OreHeights.workingY(iron, 70));
        assertEquals(OptionalInt.of(-20), OreHeights.workingY(iron, -20), "在最多那层以下、还在范围里：原地横挖");
        OreHeights.Band copper = OreHeights.of("minecraft:copper_ore").orElseThrow();
        assertEquals(OptionalInt.empty(), OreHeights.workingY(copper, -40), "铜矿最低 -16，在它下面要往上爬，不挖");
        OreHeights.Band coal = OreHeights.of("minecraft:coal_ore").orElseThrow();
        assertEquals(OptionalInt.of(64), OreHeights.workingY(coal, 64), "煤最多在 95，人在 64 就原地横挖");
    }
}
