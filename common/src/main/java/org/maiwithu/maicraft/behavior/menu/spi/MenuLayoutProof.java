// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu.spi;

import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;

/**
 * 一个模组界面的布局证明：联动模组说明自己的界面两侧怎么分、哪些格子能点。
 * 没有证明的模组界面一格都不点（按不支持结束），有了证明才和原版界面一样读两侧、搬东西。
 *
 * <p>只证明模组自己的界面类型，不能改写原版界面的判定。
 */
public interface MenuLayoutProof {

    /** 证明的是哪些界面类型：菜单类型的注册 ID，例如 ae2:item_terminal；不是方块 ID。 */
    Set<String> menuTypes();

    /**
     * 角色侧已经核对是恰好 36 格之后，给出两侧怎么分。
     * 模组界面的其余格子里可能有升级格、视图元件格、合成格这类不该当成"容器那一侧"去点的，
     * 证明只把真能存取的格子放进容器侧；形状对不上已知的样子时给 {@link MenuLayout.Unsupported}。
     *
     * @param slots       界面的类型与每一格背后装的是谁
     * @param playerSlots 背后是角色背包的 36 个槽位号
     * @param otherSlots  其余槽位号
     */
    MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots);
}
