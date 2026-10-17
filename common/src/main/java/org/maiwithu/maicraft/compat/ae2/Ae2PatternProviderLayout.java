// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;

/**
 * 样板供应器界面的布局证明：角色背包那一侧是普通的 36 格；容器那一侧只认放样板的那几格，
 * 退回格（网络放不回去的东西暂存处）不点。哪几格放样板按 AE2 自己给界面槽位标的用途认。
 */
final class Ae2PatternProviderLayout implements MenuLayoutProof {

    /** 样板供应器的菜单类型（方块与线缆上的部件共用）。类别：游戏事实（AE2 PatternProviderMenu）。 */
    static final String PROVIDER_MENU = "ae2:pattern_provider";

    private final Ae2Compat compat;

    Ae2PatternProviderLayout(Ae2Compat compat) {
        this.compat = Objects.requireNonNull(compat, "compat");
    }

    @Override public Set<String> menuTypes() {
        return Set.of(PROVIDER_MENU);
    }

    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots) {
        Set<Integer> patterns = compat.openPatternSlots();
        List<Integer> container = otherSlots.stream().filter(patterns::contains).toList();
        if (container.isEmpty()) return new MenuLayout.Unsupported("样板供应器的界面里认不出放样板的格子");
        return new MenuLayout.Supported(List.copyOf(playerSlots), container, Set.of());
    }
}
