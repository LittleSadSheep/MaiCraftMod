// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.MenuLayout.Layout;
import org.maiwithu.maicraft.behavior.menu.MenuLayout.Supported;
import org.maiwithu.maicraft.behavior.menu.MenuLayout.Unsupported;

/** 界面布局：原版箱、熔炉一族、漏斗的分侧；玩家侧不足 36 格与认不出的模组类型都证明不了。 */
class MenuLayoutTest {

    // 替身：前 playerCount 格背后是角色背包，其余是容器格。
    private static MenuSlots slots(String typeId, int total, int playerCount) {
        return new MenuSlots() {
            @Override public String menuTypeId() { return typeId; }
            @Override public int slotCount() { return total; }
            @Override public boolean playerBacked(int slot) { return slot >= total - playerCount; }
        };
    }

    private static void assertPlayerSideIsLast36(Layout layout, int total) {
        Supported supported = assertInstanceOf(Supported.class, layout);
        assertEquals(36, supported.playerSlots().size());
        Set<Integer> expected = new HashSet<>();
        for (int slot = total - 36; slot < total; slot++) expected.add(slot);
        assertEquals(expected, new HashSet<>(supported.playerSlots()));
    }

    @Test
    void 原版箱两侧各就各位() {
        // 大箱子的菜单类型是 generic_9x6，共 90 格：54 容器格 + 36 角色格；菜单类型不是方块 ID，认 minecraft:chest 认不出。
        Layout layout = MenuLayout.classify(slots("minecraft:generic_9x6", 90, 36));
        assertInstanceOf(Unsupported.class, MenuLayout.classify(slots("minecraft:chest", 90, 36)));
        Supported supported = assertInstanceOf(Supported.class, layout);
        assertPlayerSideIsLast36(layout, 90);
        assertEquals(54, supported.containerSlots().size());
        assertTrue(supported.machineSlots().isEmpty(), "普通箱没有机器槽");
    }

    @Test
    void 漏斗与投掷器也是普通容器() {
        Layout hopper = MenuLayout.classify(slots("minecraft:hopper", 41, 36));
        Supported supported = assertInstanceOf(Supported.class, hopper);
        assertEquals(5, supported.containerSlots().size());
        assertTrue(supported.machineSlots().isEmpty());
    }

    @Test
    void 熔炉的燃料槽与产出格按机器槽核对() {
        // FurnaceMenu 共 39 格：投入口 0、燃料 1、产出 2，角色格在 3..38。
        Layout layout = MenuLayout.classify(slots("minecraft:furnace", 39, 36));
        Supported supported = assertInstanceOf(Supported.class, layout);
        assertEquals(List.of(0, 1, 2), supported.containerSlots());
        assertEquals(Set.of(1, 2), supported.machineSlots());
        // 高炉、烟熏炉同一族。
        assertInstanceOf(Supported.class, MenuLayout.classify(slots("minecraft:blast_furnace", 39, 36)));
        assertInstanceOf(Supported.class, MenuLayout.classify(slots("minecraft:smoker", 39, 36)));
    }

    @Test
    void 玩家侧不足36格证明不了() {
        // 模组界面的角色格被换成了别的物品格，或数量对不上：不冒充认得出。
        Layout layout = MenuLayout.classify(slots("somemod:bank", 50, 30));
        Unsupported unsupported = assertInstanceOf(Unsupported.class, layout);
        assertTrue(unsupported.reason().contains("36"), "原因里写明角色侧格数对不上");
    }

    @Test
    void 认不出的模组界面证明不了() {
        // 界面形状看着像箱子也不行：类型没证明过就不点。
        Layout layout = MenuLayout.classify(slots("somemod:chest_like", 81, 36));
        Unsupported unsupported = assertInstanceOf(Unsupported.class, layout);
        assertTrue(unsupported.reason().contains("somemod:chest_like"), "原因里写明认不出的界面类型");
    }

    @Test
    void 合成台与石切台的两格产出投入都认得出() {
        // 合成台容器侧 10 格（产出格加合成格），石切台 2 格（投入口加产出格），点击都按普通槽核对。
        Layout crafting = MenuLayout.classify(slots("minecraft:crafting", 46, 36));
        Supported supported = assertInstanceOf(Supported.class, crafting);
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), supported.containerSlots());
        assertTrue(supported.machineSlots().isEmpty());

        Layout stonecutter = MenuLayout.classify(slots("minecraft:stonecutter", 38, 36));
        Supported stoneSupported = assertInstanceOf(Supported.class, stonecutter);
        assertEquals(List.of(0, 1), stoneSupported.containerSlots());
        assertTrue(stoneSupported.machineSlots().isEmpty());
    }
}
