// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import net.minecraft.world.item.ItemStack;

/** 槽位快照：一个槽位在某一刻的样子（几件、什么物品、什么组件）；判定搬运结算时先在动手前取一份。 */
public record SlotSnapshot(ItemStack stack) {

    /** 冻结此刻的样子：复制一份，之后的同步与本地预测不会再改到这份快照。 */
    public static SlotSnapshot of(ItemStack stack) {
        return new SlotSnapshot(stack.copy());
    }

    /** 空格子。 */
    public static SlotSnapshot empty() {
        return new SlotSnapshot(ItemStack.EMPTY);
    }

    public boolean isEmpty() {
        return stack.isEmpty();
    }

    public int count() {
        return stack.getCount();
    }

    /** 两份快照是不是同一种物品同一副组件：空对空也算一样；搬运的增减核对都建立在这上面。 */
    public boolean sameIdentity(SlotSnapshot other) {
        return ItemStack.isSameItemSameComponents(stack, other.stack);
    }
}
