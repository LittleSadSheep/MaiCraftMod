// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 关界面前把光标上的东西放回背包：像玩家一样，先找背包里能把它整个装下的一格放好，再关界面；
 * 不留给原版的关闭流程去还——背包满时那个流程会把东西丢在脚下。
 *
 * <p>先叠到同样的东西上，叠不下再找空格；空格先找主背包，快捷栏放最后，免得把手边的工具位占了。
 * 只认角色自己背包（主背包 27 格加快捷栏 9 格）的格子，盔甲、副手、容器那一侧都不放。一格都装不下就给 -1，
 * 调用方照常关界面。
 */
final class CursorStowing {

    /** 快捷栏在角色背包里的格号是 0 到 8，主背包是 9 到 35。 */
    private static final int HOTBAR_SIZE = 9;
    private static final int MAIN_INVENTORY_END = 36;

    private CursorStowing() {}

    /** 一格候选：界面里的槽位号、背包里的格号、现在放着什么、这一格对光标上的东西最多能放几件。 */
    record Spot(int slotId, int inventoryIndex, ItemStack current, int capacity) {}

    /** 光标上的东西放进这份界面里哪一格；装不下为 -1。 */
    static int slotFor(AbstractContainerMenu menu, ItemStack carried) {
        List<Spot> spots = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory) || slot.getContainerSlot() >= MAIN_INVENTORY_END) continue;
            if (!slot.mayPlace(carried)) continue;
            spots.add(new Spot(slot.index, slot.getContainerSlot(), slot.getItem(),
                    Math.min(slot.getMaxStackSize(carried), carried.getMaxStackSize())));
        }
        return choose(spots, carried);
    }

    /** 从候选里挑：能整个叠上去的同样东西优先，再主背包空格，最后快捷栏空格；都不行为 -1。 */
    static int choose(List<Spot> spots, ItemStack carried) {
        for (Spot spot : spots) {
            if (!spot.current().isEmpty() && ItemStack.isSameItemSameComponents(spot.current(), carried)
                    && spot.current().getCount() + carried.getCount() <= spot.capacity()) {
                return spot.slotId();
            }
        }
        int hotbarEmpty = -1;
        for (Spot spot : spots) {
            if (!spot.current().isEmpty() || carried.getCount() > spot.capacity()) continue;
            if (spot.inventoryIndex() >= HOTBAR_SIZE) return spot.slotId();
            if (hotbarEmpty < 0) hotbarEmpty = spot.slotId();
        }
        return hotbarEmpty;
    }
}
