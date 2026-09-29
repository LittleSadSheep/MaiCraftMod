// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.container;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.core.integration.backpack.BackpackMenuAccess;

/** 普通容器遵守物品叠数；精妙主存储使用原生升级容量，升级槽和玩家格仍按普通规则。 */
final class ContainerSlotCapacity {
    private ContainerSlotCapacity() {}
    static int limit(LocalPlayer player, AbstractContainerMenu menu, int index, ItemStack kind) {
        int capacity = menu.getSlot(index).getMaxStackSize(kind);
        return storage(player, menu, index) ? capacity : Math.min(kind.getMaxStackSize(), capacity);
    }
    static boolean storage(LocalPlayer player, AbstractContainerMenu menu, int index) {
        if (!BackpackMenuAccess.supports(menu)) return false;
        var observed = BackpackMenuAccess.read(player);
        if (observed.snapshot() == null || observed.snapshot().menu() != menu)
            throw new IllegalArgumentException("backpack storage layout is not synchronized");
        // 无限槽未证明扣物语义，不能让普通精确搬运把一个图标当成有限可减少的堆叠。
        if (observed.snapshot().infiniteSlots().contains(index))
            throw new IllegalArgumentException("infinite backpack slots need separate native transfer evidence");
        return observed.snapshot().storageSlots().contains(index);
    }
}
