// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * 原版"打掉哪种生物掉什么"的对照：羊毛按颜色对上羊、生肉皮革对上牛、对照没有的物品如实说没有；
 * 羊毛需求要辨色，标签要任一羊毛时什么颜色都行。
 */
class VanillaEntityDropsTest {

    private final FakeTags tags = new FakeTags();

    @Test
    void 羊毛按颜色对上羊() {
        List<String> types = VanillaEntityDrops.creatureTypes(WantedItem.ofItem("minecraft:white_wool"), tags);
        assertEquals(List.of("minecraft:sheep"), types);
        assertEquals(Optional.of("white"), VanillaEntityDrops.sheepColorNeeded(
                WantedItem.ofItem("minecraft:white_wool"), tags));
    }

    @Test
    void 标签要任一羊毛时什么颜色都行_但剪过毛的仍要排除() {
        tags.put("minecraft:black_wool", "minecraft:wool");
        List<String> types = VanillaEntityDrops.creatureTypes(WantedItem.ofTag("minecraft:wool"), tags);
        assertEquals(List.of("minecraft:sheep"), types);
        assertTrue(VanillaEntityDrops.sheepColorNeeded(WantedItem.ofTag("minecraft:wool"), tags).isEmpty());
        assertTrue(VanillaEntityDrops.woolRequest(WantedItem.ofTag("minecraft:wool"), tags));
    }

    @Test
    void 生肉皮革牛肉各对上各自的生物() {
        assertEquals(List.of("minecraft:cow"),
                VanillaEntityDrops.creatureTypes(WantedItem.ofItem("minecraft:leather"), tags));
        assertEquals(List.of("minecraft:pig"),
                VanillaEntityDrops.creatureTypes(WantedItem.ofItem("minecraft:porkchop"), tags));
        assertEquals(List.of("minecraft:creeper"),
                VanillaEntityDrops.creatureTypes(WantedItem.ofItem("minecraft:gunpowder"), tags));
    }

    @Test
    void 对照里没有的物品如实说没有生物掉它() {
        // 铁锭是烧出来的，兔子脚在对照里、面包不在。
        assertTrue(VanillaEntityDrops.creatureTypes(WantedItem.ofItem("minecraft:iron_ingot"), tags).isEmpty());
        assertTrue(VanillaEntityDrops.creatureTypes(WantedItem.ofItem("minecraft:bread"), tags).isEmpty());
        assertEquals(List.of("minecraft:rabbit"),
                VanillaEntityDrops.creatureTypes(WantedItem.ofItem("minecraft:rabbit_foot"), tags));
    }

    @Test
    void 不是羊毛的东西不要求毛色() {
        assertTrue(VanillaEntityDrops.sheepColorNeeded(WantedItem.ofItem("minecraft:mutton"), tags).isEmpty());
        assertTrue(!VanillaEntityDrops.woolRequest(WantedItem.ofItem("minecraft:mutton"), tags));
    }
}
