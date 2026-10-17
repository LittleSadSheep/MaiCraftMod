// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;

/**
 * 蓝图桌与蓝图加农炮界面的布局证明：角色背包那一侧是普通的 36 格；蓝图桌的容器侧是输入、输出两格，
 * 加农炮是蓝图、用完的蓝图、书、材料清单、火药五格（Create 6.0.11 的 SchematicTableMenu、SchematicannonMenu 按这个顺序加格子）。
 */
final class CreateSchematicLayout implements MenuLayoutProof {

    static final String TABLE_MENU = CreateMachineCatalog.MOD_ID + ":schematic_table";
    static final String CANNON_MENU = CreateMachineCatalog.MOD_ID + ":schematicannon";

    @Override public Set<String> menuTypes() {
        return Set.of(TABLE_MENU, CANNON_MENU);
    }

    // 两种界面的容器侧只有这两格或五格；数目对不上就是界面变了，不点。
    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots) {
        if (otherSlots.size() != 2 && otherSlots.size() != 5) {
            return new MenuLayout.Unsupported("蓝图桌或蓝图加农炮的界面格数对不上（" + otherSlots.size() + " 格）");
        }
        return new MenuLayout.Supported(List.copyOf(playerSlots), List.copyOf(otherSlots), Set.of());
    }
}
