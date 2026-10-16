// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 服务端换页：同一次交互里，服务端把开着的界面换成另一份——界面编号是新的，类型也可能变了，
 * 例如 ME 终端里点"合成"换成填数量页，定下数量换成确认页，开工后又退回终端。
 * 哪些换法算这次操作接着用，由打开界面的调用方声明：声明认的，已打开的界面就换绑到新的一页，
 * 读数、点击、模组协议的操作和关闭都对着它；不认的照旧当成界面被关掉或被别的界面顶掉，一格都不碰。
 */
@FunctionalInterface
public interface PageSwaps {

    /** 不认任何换页：界面换了就是被关掉或被顶掉。 */
    PageSwaps NONE = (from, to) -> false;

    /**
     * 服务端把 from 换成了 to，算不算这次操作接着用。
     *
     * @param from 之前认领、正在用的那一页
     * @param to   此刻开着的界面；角色自己的背包界面（界面被关上）不会问到这里
     */
    boolean follows(AbstractContainerMenu from, AbstractContainerMenu to);
}
