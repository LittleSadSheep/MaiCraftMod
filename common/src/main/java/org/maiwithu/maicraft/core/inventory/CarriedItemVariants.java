// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.inventory;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;

/** 交互时按已观察的组件身份取工件；未指定变体则优先继续使用当前手持物，避免换成另一进度。 */
public final class CarriedItemVariants {
    private CarriedItemVariants() {}

    public static int find(Inventory inventory, Item item, String resourceId, HolderLookup.Provider registries) {
        int limit = Math.min(PlayerInv.BUILDABLE_SLOTS, inventory.items.size()), held = inventory.selected;
        if (held >= 0 && held < limit && matches(inventory.getItem(held), item, resourceId, registries)) return held;
        for (int slot = 0; slot < limit; slot++)
            if (slot != held && matches(inventory.getItem(slot), item, resourceId, registries)) return slot;
        return -1;
    }

    public static boolean matches(ItemStack stack, Item item, String resourceId, HolderLookup.Provider registries) {
        if (stack.isEmpty() || !stack.is(item)) return false;
        if (resourceId == null) return true;
        try { return resourceId.equals(ResourceIdentity.key(ResourceIdentity.item(stack, registries))); }
        catch (RuntimeException unavailable) { return false; } // 组件未读全时不猜测匹配，也不退回另一个同名工件。
    }
}
