// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.mekanism;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;

/**
 * 通用机械机器界面的布局证明：角色背包那一侧是普通的 36 格；机器那一侧只认背后真是机器物品格的格子
 * （输入、输出、电池、附加格），升级、配方显示这类虚拟格与只看不存的格一格都不点。
 * 机器格里的东西机器随时会吃掉或产出，搬运按机器槽核对（源格确切减少、光标空）。
 * 哪一格收什么由格子自己的 mayPlace 回答，只放一部分时不往输出格、电池格里塞。
 */
final class MekanismMachineLayout implements MenuLayoutProof {

    /** 物流分拣器的界面：只有规则与开关，没有物品格；开它是为了看规则、改设置。 */
    static final String SORTER_MENU = "mekanism:logistical_sorter";

    /**
     * 证明的菜单类型：认得的加工机器、能量立方、工厂与物流分拣器。这里是菜单类型的注册 ID，不是方块 ID；
     * 加工机器的菜单与方块同名，各级工厂共用 mekanism:factory。类别：游戏事实（MekanismContainerTypes）。
     */
    static final Set<String> MACHINE_MENUS = Set.of(
            "mekanism:enrichment_chamber", "mekanism:crusher", "mekanism:osmium_compressor",
            "mekanism:metallurgic_infuser", "mekanism:energized_smelter", "mekanism:purification_chamber",
            "mekanism:combiner", "mekanism:precision_sawmill", "mekanism:chemical_injection_chamber",
            "mekanism:energy_cube", "mekanism:factory", SORTER_MENU);

    private final MekanismCompat compat;

    MekanismMachineLayout(MekanismCompat compat) {
        this.compat = Objects.requireNonNull(compat, "compat");
    }

    @Override public Set<String> menuTypes() {
        return MACHINE_MENUS;
    }

    // 机器那一侧按读写端认出的真机器格筛：界面里的其余格不进容器侧，一个真机器格都没有就证明不了。
    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots) {
        // 分拣器的界面没有物品格：认得它、一格都不点，规则与开关经 Mekanism 自己的包改。
        if (SORTER_MENU.equals(slots.menuTypeId())) {
            return new MenuLayout.Supported(List.copyOf(playerSlots), List.of(), Set.of());
        }
        Set<Integer> real = compat.openMachineSlots();
        List<Integer> machine = otherSlots.stream().filter(real::contains).toList();
        if (machine.isEmpty()) {
            return new MenuLayout.Unsupported("这个通用机械界面（" + slots.menuTypeId() + "）里认不出能存取的机器格");
        }
        return new MenuLayout.Supported(List.copyOf(playerSlots), machine, Set.copyOf(machine));
    }
}
