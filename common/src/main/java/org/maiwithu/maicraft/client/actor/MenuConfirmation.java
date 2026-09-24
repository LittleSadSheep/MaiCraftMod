// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.world.item.ItemStack;
import java.util.function.BiPredicate;

/** 描述一次菜单操作预期看到的变化；只读取当前菜单，不在确认过程中再点别的槽位。 */
@FunctionalInterface
public interface MenuConfirmation {
    enum Verdict { PENDING, APPLIED, NOT_APPLIED, DIVERGED }

    Verdict observe(LocalPlayerContext context, MenuReceipt receipt);

    static MenuConfirmation stateChanged() {
        // 只证明同一菜单的版本变过；真正需要某物品出现时，调用方还要核对具体槽位。
        return (context, receipt) -> {
            var menu = context.player().containerMenu;
            return menu.containerId == receipt.containerId()
                    && menu.getStateId() != receipt.beforeStateId()
                    ? Verdict.APPLIED : Verdict.PENDING;
        };
    }

    static MenuConfirmation inventorySwap(
            int sourceInventorySlot,
            int hotbarSlot,
            ItemStack sourceBefore,
            ItemStack hotbarBefore) {
        return inventorySwap(sourceInventorySlot, hotbarSlot, sourceBefore, hotbarBefore, MenuConfirmation::same);
    }

    /** 动态电量等由对应模组定义可变化字段；交换仍须同时核对两端物品数量与其余身份。 */
    static MenuConfirmation inventorySwap(int sourceInventorySlot, int hotbarSlot,
            ItemStack sourceBefore, ItemStack hotbarBefore, BiPredicate<ItemStack, ItemStack> equivalent) {
        // 保存交换前两叠物品，之后区分已对调、完全没变和出现第三种情况。
        ItemStack frozenSource = sourceBefore.copy();
        ItemStack frozenHotbar = hotbarBefore.copy();
        return (context, receipt) -> {
            ItemStack source = context.player().getInventory().getItem(sourceInventorySlot);
            ItemStack hotbar = context.player().getInventory().getItem(hotbarSlot);
            if (equivalent.test(source, frozenHotbar) && equivalent.test(hotbar, frozenSource)) return Verdict.APPLIED;
            if (equivalent.test(source, frozenSource) && equivalent.test(hotbar, frozenHotbar)) return Verdict.NOT_APPLIED;
            return Verdict.DIVERGED;
        };
    }

    static MenuConfirmation closedToInventory() {
        // 本地已回到默认物品栏菜单且屏幕关闭，才满足这个“关闭”的观察条件。
        return (context, receipt) -> context.player().containerMenu == context.player().inventoryMenu
                && context.minecraft().screen == null
                ? Verdict.APPLIED : Verdict.PENDING;
    }

    static MenuConfirmation pending() {
        return (context, receipt) -> Verdict.PENDING;
    }

    private static boolean same(ItemStack left, ItemStack right) {
        return left.getCount() == right.getCount() && ItemStack.isSameItemSameComponents(left, right);
    }
}
