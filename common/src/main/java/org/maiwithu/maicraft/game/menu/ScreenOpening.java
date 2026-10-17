// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 角色眼前开着的那份界面：画面上是哪个界面、身上是哪个菜单、哪一刻出现的、谁开的。看界面时靠它说"这是任务点开的还是人开的"，
 * 也靠它判断里面的东西同步过来没有：刚出现的界面要等过一次往返，才不把没同步的格子当成空的。
 *
 * @param screen        出现时画面上的界面；只有菜单对象、画面上没有界面时为 null
 * @param menu          出现时角色身上的菜单对象（没开容器界面时是角色自己的背包菜单）
 * @param stateIdAtOpen 出现时菜单的版本号；说不清是哪一刻开的时为 -1（菜单版本号不会是负数）
 * @param openedTick    第一次看到它的客户端刻
 * @param openedBy      谁开的
 */
public record ScreenOpening(Screen screen, AbstractContainerMenu menu, int stateIdAtOpen, long openedTick,
                            OpenedBy openedBy) {

    /** 谁开的这份界面。 */
    public enum OpenedBy {
        /** 自动化拿着角色、刚向游戏出过手（右键方块或实体、点开背包）之后出现的：任务点开的。 */
        TASK,
        /** 人拿着角色时出现的，或自动化这阵子没出过手时出现的（人按键开背包、服务器自己弹出来的）。 */
        PLAYER,
        /** 进世界、换身体时已经开着，没看到它是什么时候开的。 */
        UNKNOWN
    }
}
