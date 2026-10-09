// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.MenuLayout.Supported;
import org.maiwithu.maicraft.behavior.menu.MenuLayout.Unsupported;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;

/** 认得出哪些界面：原版照旧，模组界面有证明才认，证明不能改写原版、不能一种界面两份。 */
class MenuLayoutsTest {

    // 替身：前 playerCount 格之外是容器格，最后 playerCount 格背后是角色背包。
    private static MenuSlots slots(String typeId, int total, int playerCount) {
        return new MenuSlots() {
            @Override public String menuTypeId() { return typeId; }
            @Override public int slotCount() { return total; }
            @Override public boolean playerBacked(int slot) { return slot >= total - playerCount; }
        };
    }

    // 终端那样的证明：网络里的货不是格子，容器侧为空；视图元件格这类其余格子一格都不碰。
    private static MenuLayoutProof terminal(String... types) {
        return new MenuLayoutProof() {
            @Override public Set<String> menuTypes() { return Set.of(types); }
            @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots,
                    List<Integer> otherSlots) {
                return new Supported(playerSlots, List.of(), Set.of());
            }
        };
    }

    @Test
    void 模组界面有证明就按证明分侧() {
        MenuLayouts layouts = new MenuLayouts(List.of(terminal("ae2:item_terminal")));
        Supported supported = assertInstanceOf(Supported.class, layouts.classify(slots("ae2:item_terminal", 41, 36)));
        assertEquals(36, supported.playerSlots().size());
        assertEquals(List.of(), supported.containerSlots());
    }

    @Test
    void 没有证明的模组界面照旧证明不了_原版照旧认得() {
        MenuLayouts layouts = new MenuLayouts(List.of(terminal("ae2:item_terminal")));
        assertInstanceOf(Unsupported.class, layouts.classify(slots("othermod:screen", 41, 36)));
        assertInstanceOf(Supported.class, layouts.classify(slots("minecraft:generic_9x3", 63, 36)));
        assertInstanceOf(Unsupported.class, MenuLayouts.VANILLA.classify(slots("ae2:item_terminal", 41, 36)));
    }

    @Test
    void 角色侧不是36格时不问证明() {
        // 角色侧的形状对不上说明界面不是想的那样，证明再怎么说也不点。
        MenuLayouts layouts = new MenuLayouts(List.of(terminal("ae2:item_terminal")));
        assertInstanceOf(Unsupported.class, layouts.classify(slots("ae2:item_terminal", 41, 27)));
    }

    @Test
    void 证明不能改写原版界面也不能一种界面两份() {
        assertThrows(IllegalArgumentException.class,
                () -> new MenuLayouts(List.of(terminal("minecraft:generic_9x3"))));
        assertThrows(IllegalArgumentException.class,
                () -> new MenuLayouts(List.of(terminal("ae2:item_terminal"), terminal("ae2:item_terminal"))));
    }
}
