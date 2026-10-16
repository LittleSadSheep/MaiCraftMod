// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Change;

/** 正要用的东西：上一个目标拿到的，加上手上正在拿的；下一个目标结束就换掉。 */
class ItemsInUseTest {

    private final FakeTags tags = new FakeTags();
    private final ItemsInUse inUse = new ItemsInUse(tags);

    @Test
    void 上一个目标拿到的留着_下一个目标结束就换掉() {
        inUse.goalFinished(List.of(Change.of(Change.Kind.ITEM_GAINED, "minecraft:cobblestone", 3),
                Change.of(Change.Kind.BLOCK_BROKEN, "minecraft:stone", 3)));
        assertEquals(3, inUse.keep("minecraft:cobblestone"));
        assertEquals(0, inUse.keep("minecraft:stone"), "挖掉的方块不是拿到的东西");

        inUse.goalFinished(List.of(Change.of(Change.Kind.ITEM_GAINED, "minecraft:oak_log", 4)));
        assertEquals(0, inUse.keep("minecraft:cobblestone"));
    }

    @Test
    void 要的是标签时_挂着这个标签的料都按这个数留() {
        tags.put("minecraft:cobblestone", "minecraft:stone_tool_materials");
        inUse.goalFinished(List.of(Change.of(Change.Kind.ITEM_GAINED, "#minecraft:stone_tool_materials", 3)));

        assertEquals(3, inUse.keep("minecraft:cobblestone"));
        assertEquals(0, inUse.keep("minecraft:dirt"));
    }

    @Test
    void 手上正在拿的也留着() {
        tags.put("minecraft:cobblestone", "minecraft:stone_tool_materials");
        inUse.watch(() -> List.of(new ItemRequest(WantedItem.ofItem("minecraft:stone_pickaxe"), 1, "挖铁"),
                new ItemRequest(WantedItem.ofTag("minecraft:stone_tool_materials"), 3, "石镐的原料")));

        assertEquals(3, inUse.keep("minecraft:cobblestone"));
    }

    @Test
    void 要的本来就是吃的_拿到的不算正要用的() {
        // 10-15 直播：obtain #maicraft:foods/edible 拿到 1 块生羊肉，三颗心饿着却因为"上一个目标刚拿到"不吃。
        tags.put("minecraft:mutton", "maicraft:foods/edible");
        tags.put("minecraft:bread", "c:foods/bread", "maicraft:foods/edible");
        tags.put("minecraft:dried_kelp", "c:foods", "maicraft:foods/edible");
        inUse.goalFinished(List.of(Change.of(Change.Kind.ITEM_GAINED, "#maicraft:foods/edible", 1),
                Change.of(Change.Kind.ITEM_GAINED, "#c:foods/bread", 3),
                Change.of(Change.Kind.ITEM_GAINED, "minecraft:dried_kelp", 8)));

        assertEquals(0, inUse.keep("minecraft:mutton"));
        assertEquals(0, inUse.keep("minecraft:bread"));
        assertEquals(8, inUse.keep("minecraft:dried_kelp"), "点名要的具体食物（要交任务的熟海带）照旧留着");
    }
}
