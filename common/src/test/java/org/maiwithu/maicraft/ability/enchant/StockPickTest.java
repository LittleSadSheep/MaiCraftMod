// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;

/**
 * 选料判据的离线场景：已附魔、含储存附魔的不选；与点名对不上的不选；挑的是第一件合格的。
 */
class StockPickTest {

    /** 标签替身：铁的装备挂同一个标签，其余没有。 */
    private static final ReadsItemTags TAGS = itemId ->
            itemId.endsWith("_iron") || itemId.contains("iron_") ? Set.of("minecraft:iron_tools") : Set.of();

    private static ItemFacts slot(String itemId, int count) {
        return new ItemFacts(itemId, count, Map.of(), Map.of(), "plain");
    }

    private static ItemFacts enchanted(String itemId) {
        return new ItemFacts(itemId, 1, Map.of("minecraft:sharpness", 3), Map.of(), "plain");
    }

    private static ItemFacts stored(String itemId) {
        return new ItemFacts(itemId, 1, Map.of(), Map.of("minecraft:sharpness", 1), "plain");
    }

    @Test
    void 挑身上第一件匹配且台子肯收的() {
        assertEquals(1, StockPick.pick(List.of(
                slot("minecraft:dirt", 3), slot("minecraft:iron_sword", 1), slot("minecraft:iron_sword", 1)),
                WantedItem.ofItem("minecraft:iron_sword"), TAGS));
    }

    @Test
    void 已附魔与含储存附魔的不选() {
        assertEquals(2, StockPick.pick(List.of(
                enchanted("minecraft:iron_sword"), stored("minecraft:iron_sword"), slot("minecraft:iron_sword", 1)),
                WantedItem.ofItem("minecraft:iron_sword"), TAGS));
    }

    @Test
    void 标签点名时按挂的标签认() {
        assertEquals(0, StockPick.pick(List.of(slot("minecraft:iron_sword", 1)),
                WantedItem.ofTag("minecraft:iron_tools"), TAGS));
    }

    @Test
    void 身上没有合格的给负一先去拿() {
        assertEquals(-1, StockPick.pick(List.of(slot("minecraft:dirt", 3), enchanted("minecraft:iron_sword")),
                WantedItem.ofItem("minecraft:iron_sword"), TAGS));
    }
}
