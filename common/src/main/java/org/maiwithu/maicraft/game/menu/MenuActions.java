// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.crafting.RecipeHolder;

import org.maiwithu.maicraft.game.player.PlayerContext;

/** 容器界面的原生操作：前一次还没结束就不能点下一次；查询结果本身不发新点击。谁打开谁关闭的规矩在玩家行为层。 */
public interface MenuActions {

    /** 一次原生副手交换，保持世界画面和主手槽不变；不支持时由调用方等待。 */
    default PendingMenuAction swapInventoryToOffhand(PlayerContext context, int inventorySlot, int timeoutTicks) {
        throw new UnsupportedOperationException("native offhand staging is unavailable");
    }

    /** 聊天等后继动作先等已提交的搬运或关闭结清，不能用新界面打断物品同步。 */
    boolean hasPendingTransaction();

    /** 世界动作被旧页面挡住时原生关闭并等待；当前所需菜单的点击仍使用 {@link #ensureVisible}。 */
    boolean ensureWorldVisible(PlayerContext context);

    /** 必要时显示玩家背包，并等对应界面真正可操作。 */
    boolean ensureVisible(PlayerContext context);

    /** 登记经模组原生协议提交的菜单操作，让后续任务等待对应确认。 */
    void interactionSubmitted(PlayerContext context);

    PendingMenuAction click(PlayerContext context, int slot, int button, ClickType clickType,
                            MenuConfirmation confirmation, int timeoutTicks);

    /** 附魔等菜单按钮先走原生本地校验，再提交一次；调用方必须提供具体物品和成本变化的确认条件。 */
    default PendingMenuAction pressButton(PlayerContext context, int button,
                                          MenuConfirmation confirmation, int timeoutTicks) {
        throw new UnsupportedOperationException("native menu buttons are not supported");
    }

    PendingMenuAction swapInventoryToHotbar(
            PlayerContext context,
            int sourceInventorySlot,
            int hotbarSlot,
            int timeoutTicks);

    PendingMenuAction placeRecipe(PlayerContext context, RecipeHolder<?> recipe, boolean shift,
                                  MenuConfirmation confirmation, int timeoutTicks);

    PendingMenuAction close(PlayerContext context, int timeoutTicks);

    /** 任务结束时先结束旧等待再关菜单；即使原任务对象被移走，动作入口也会继续完成这次关闭。 */
    PendingMenuAction closeForTaskBoundary(
            PlayerContext context, int timeoutTicks, String boundaryReason);

    PendingMenuAction poll(PlayerContext context, PendingMenuAction pending);
}
