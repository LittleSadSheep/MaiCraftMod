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

    /**
     * 经模组自己的协议对当前界面做一次操作（例如 AE2 终端里取一件，发的是模组的包，不是原版点格子）。
     * 和点格子同一套规矩：上一项操作结清、当前界面画过一帧、占本刻唯一的一次交互机会，再调 send 发包；
     * 之后按调用方给的确认条件等到期限。send 抛异常时按不确定收场，不自动再发一次。
     *
     * @param what 这一下在做什么，写进日志，例如"AE2 终端取一件"
     * @param send 真正发模组的包的那一下；联动包里经模组读写端调用
     */
    default PendingMenuAction submitModAction(PlayerContext context, String what, Runnable send,
                                              MenuConfirmation confirmation, int timeoutTicks) {
        throw new UnsupportedOperationException("mod menu actions are not supported");
    }

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
