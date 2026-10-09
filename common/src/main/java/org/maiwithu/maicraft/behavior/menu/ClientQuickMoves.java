// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 整堆快速移动的读端：经游戏接口层的菜单入口，把角色背包侧的一个槽位整堆送往另一侧。
 *
 * <p>上一次快速移动还没确认完就不能点下一次——两次未经确认的点击无法分清哪次搬走了什么；
 * 真正的结算（源格确实少了、对面确实多了）由搬运计划按快照核对，这里只负责把这一下点出去。
 */
public final class ClientQuickMoves {

    /** 一次整堆搬运等确认的期限；到点没确认按不确定记，调用方以重新清点为准。 */
    private static final int QUICK_MOVE_TIMEOUT_TICKS = 40;

    private PendingMenuAction pending;

    /** 把角色侧的一个菜单槽位整堆送往另一侧；上一笔还没结清时本刻不动手，由调用方下一刻再来。 */
    public void quickMove(PlayerContext context, int playerMenuSlotId) {
        // 上一笔搬运还在等确认：不点下一次，等它结清（或到点）后再继续。
        if (pending != null && !pending.terminal()) {
            pending = context.menuActions().poll(context, pending);
            return;
        }
        pending = null;
        AbstractContainerMenu menu = context.localPlayer().containerMenu;
        if (playerMenuSlotId < 0 || playerMenuSlotId >= menu.slots.size()) {
            throw new IllegalArgumentException("快速移动的槽位号越界：" + playerMenuSlotId);
        }
        // 冻结源格送出前的样子：确认条件是"这一格确切地变少或变空"。
        var before = menu.getSlot(playerMenuSlotId).getItem().copy();
        pending = context.menuActions().click(context, playerMenuSlotId, 0, ClickType.QUICK_MOVE,
                sourceShrank(playerMenuSlotId, before), QUICK_MOVE_TIMEOUT_TICKS);
    }

    // 源格比送出前少（或空了）就算这一下搬出去了；等服务器同步期间先不判定，到点由菜单入口按不确定收场。
    private static MenuConfirmation sourceShrank(int slot, ItemStack before) {
        return (context, pending) -> {
            var menu = context.localPlayer().containerMenu;
            if (menu.containerId != pending.containerId()) return MenuConfirmation.Verdict.PENDING;
            ItemStack now = menu.getSlot(slot).getItem();
            boolean shrank = now.isEmpty() && !before.isEmpty()
                    || (!now.isEmpty() && !before.isEmpty()
                        && ItemStack.isSameItemSameComponents(now, before)
                        && now.getCount() < before.getCount());
            return shrank ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING;
        };
    }
}
