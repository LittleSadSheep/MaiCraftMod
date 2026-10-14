// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import java.util.function.IntFunction;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;

/**
 * 拉着弓、装着弩或吃着东西要中途停手时切到快捷栏哪一格：原版只有切槽才能不放箭地停手。
 * 优先切到剑、斧上（停手多半是怪贴近了、要换近战），其次空格，再其次不是箭的那格；整排都是箭才切下一格。
 * 实机 10-14 自卫射完停手，切到挨着弓的那格箭上，之后一路拿着箭走。
 */
final class UseCancelSlot {

    private UseCancelSlot() {
    }

    /** 停手要切到的快捷栏格；不会是当前这一格。 */
    static int pick(Inventory inventory) {
        return pick(inventory.selected, inventory::getItem);
    }

    // 从当前格往后挨个看一圈快捷栏。
    static int pick(int selected, IntFunction<ItemStack> hotbar) {
        int empty = -1;
        int other = -1;
        for (int step = 1; step < Inventory.getSelectionSize(); step++) {
            int slot = (selected + step) % Inventory.getSelectionSize();
            ItemStack stack = hotbar.apply(slot);
            if (stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem) return slot;
            if (stack.isEmpty()) {
                if (empty < 0) empty = slot;
            } else if (other < 0 && !(stack.getItem() instanceof ArrowItem)) {
                other = slot;
            }
        }
        if (empty >= 0) return empty;
        if (other >= 0) return other;
        return (selected + 1) % Inventory.getSelectionSize();
    }
}
