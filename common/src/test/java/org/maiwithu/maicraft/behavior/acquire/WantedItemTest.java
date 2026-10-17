// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** 想要的东西：具体物品按 ID 匹配，标签按物品挂着的标签匹配；写法与游戏里的注册 ID 一致。 */
class WantedItemTest {

    @Test
    void 具体物品按ID相等匹配() {
        WantedItem wanted = WantedItem.ofItem("minecraft:coal");
        assertFalse(wanted.isTag());
        assertTrue(wanted.matches("minecraft:coal", Set.of()));
        assertFalse(wanted.matches("minecraft:charcoal", Set.of("minecraft:coals")));
    }

    @Test
    void 标签按物品挂着的标签匹配() {
        WantedItem wanted = WantedItem.ofTag("minecraft:logs");
        assertTrue(wanted.isTag());
        assertEquals("minecraft:logs", wanted.tagId());
        assertTrue(wanted.matches("minecraft:oak_log", Set.of("minecraft:logs", "minecraft:wood")));
        assertFalse(wanted.matches("minecraft:stone", Set.of()));
    }

    @Test
    void 写给人看的一句话() {
        assertEquals("minecraft:coal", WantedItem.ofItem("minecraft:coal").describe());
        assertEquals("#minecraft:logs 里的任一物品", WantedItem.ofTag("minecraft:logs").describe());
    }
}
