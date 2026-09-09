// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.crafting.RecipeHolder;

/** 菜单操作接口：前一次还没结束就不能点下一次；查询结果本身不发新点击。 */
public interface MenuPort {
    /** 必要时显示玩家背包，并等对应界面真正可操作。 */
    boolean ensureVisible(LocalPlayerContext context);

    /** Mark a GUI operation submitted through a mod's native protocol. */
    void interactionSubmitted(LocalPlayerContext context);

    MenuReceipt click(LocalPlayerContext context, int slot, int button, ClickType clickType,
                      MenuConfirmation confirmation, int timeoutTicks);

    MenuReceipt swapInventoryToHotbar(
            LocalPlayerContext context,
            int sourceInventorySlot,
            int hotbarSlot,
            int timeoutTicks);

    MenuReceipt placeRecipe(LocalPlayerContext context, RecipeHolder<?> recipe, boolean shift,
                            MenuConfirmation confirmation, int timeoutTicks);

    MenuReceipt close(LocalPlayerContext context, int timeoutTicks);

    /** 任务结束时先结束旧等待再关菜单；即使原任务对象被移走，动作入口也会继续完成这次关闭。 */
    MenuReceipt closeForTaskBoundary(
            LocalPlayerContext context, int timeoutTicks, String boundaryReason);

    MenuReceipt poll(LocalPlayerContext context, MenuReceipt receipt);
}
