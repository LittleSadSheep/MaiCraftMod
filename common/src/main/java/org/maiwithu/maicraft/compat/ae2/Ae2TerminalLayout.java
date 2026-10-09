// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;

/**
 * ME 终端界面的布局证明：角色背包那一侧是普通的 36 格；网络里的货不在格子里，
 * 是 AE2 自己同步过来的一份存货表，所以容器那一侧一格都没有。
 * 界面里另外的格子（视图元件格、合成终端的合成格、样板编码终端的样板格）不是存取用的，一格都不点。
 */
public final class Ae2TerminalLayout implements MenuLayoutProof {

    /**
     * 证明的菜单类型：ME 终端、合成终端、样板编码终端。这里是菜单类型的注册 ID，不是方块或物品 ID；
     * 三者都是 AE2 的 MEStorageMenu。类别：游戏事实（AE2 菜单类型注册）。
     */
    private static final Set<String> TERMINAL_MENUS = Set.of("ae2:item_terminal", "ae2:craftingterm", "ae2:patternterm");

    @Override public Set<String> menuTypes() {
        return TERMINAL_MENUS;
    }

    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots) {
        return new MenuLayout.Supported(List.copyOf(playerSlots), List.of(), Set.of());
    }
}
