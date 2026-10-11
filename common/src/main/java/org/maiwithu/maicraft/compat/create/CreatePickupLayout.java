// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;

/**
 * 包裹端口（青蛙港、邮筒）界面的布局证明：角色背包那一侧是普通的 36 格；容器侧是十八格包裹格
 * （Create 6.0.11 的 PackagePortMenu，只收包裹）。
 */
final class CreatePickupLayout implements MenuLayoutProof {

    static final String PICKUP_MENU = CreateMachineCatalog.MOD_ID + ":package_port";
    private static final int PACKAGE_SLOTS = 18;

    @Override public Set<String> menuTypes() {
        return Set.of(PICKUP_MENU);
    }

    // 容器侧正好十八格才认：数目对不上就是界面变了，不点。
    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots) {
        if (otherSlots.size() != PACKAGE_SLOTS) {
            return new MenuLayout.Unsupported("包裹端口的界面格数对不上（" + otherSlots.size() + " 格）");
        }
        return new MenuLayout.Supported(List.copyOf(playerSlots), List.copyOf(otherSlots), Set.of());
    }
}
