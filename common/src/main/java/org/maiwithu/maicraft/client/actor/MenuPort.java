// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.crafting.RecipeHolder;

/** Serialized client menu transactions. A later click is forbidden until the prior receipt settles. */
public interface MenuPort {
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

    MenuReceipt poll(LocalPlayerContext context, MenuReceipt receipt);
}
