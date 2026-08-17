// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.world.item.ItemStack;

/** Read-only exact postcondition for a submitted menu transaction. */
@FunctionalInterface
public interface MenuConfirmation {
    enum Verdict { PENDING, APPLIED, NOT_APPLIED, DIVERGED }

    Verdict observe(LocalPlayerContext context, MenuReceipt receipt);

    static MenuConfirmation stateChanged() {
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
        ItemStack frozenSource = sourceBefore.copy();
        ItemStack frozenHotbar = hotbarBefore.copy();
        return (context, receipt) -> {
            ItemStack source = context.player().getInventory().getItem(sourceInventorySlot);
            ItemStack hotbar = context.player().getInventory().getItem(hotbarSlot);
            if (same(source, frozenHotbar) && same(hotbar, frozenSource)) return Verdict.APPLIED;
            if (same(source, frozenSource) && same(hotbar, frozenHotbar)) return Verdict.NOT_APPLIED;
            return Verdict.DIVERGED;
        };
    }

    static MenuConfirmation closedToInventory() {
        return (context, receipt) -> context.player().containerMenu == context.player().inventoryMenu
                ? Verdict.APPLIED : Verdict.PENDING;
    }

    static MenuConfirmation pending() {
        return (context, receipt) -> Verdict.PENDING;
    }

    private static boolean same(ItemStack left, ItemStack right) {
        return left.getCount() == right.getCount() && ItemStack.isSameItemSameComponents(left, right);
    }
}
