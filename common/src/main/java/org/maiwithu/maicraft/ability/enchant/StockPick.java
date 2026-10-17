// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;

/**
 * 选料判据：身上第一件与点名匹配、台子肯收的东西。
 *
 * <p>已附魔或含储存附魔的不再被台子接受，不选；选定了就不换另一件——
 * 到了台子前找不回原物就中止，不偷偷换一件。
 */
final class StockPick {

    private StockPick() {}

    /**
     * 挑身上第一件可附魔的匹配物品，返回它在 carriedSlots 里的下标；没有返回 -1。
     * 堆叠的也挑：装料前先腾出一个空格接成品，不拆开别人的堆。
     */
    static int pick(List<ItemFacts> slots, WantedItem wanted, ReadsItemTags tags) {
        for (int i = 0; i < slots.size(); i++) {
            ItemFacts slot = slots.get(i);
            if (qualifies(slot, wanted, tags.tagsOf(slot.itemId()))) return i;
        }
        return -1;
    }

    /** 选料判据（纯匹配）：与点名对得上，且台子肯收——未附魔、无储存附魔。 */
    static boolean qualifies(ItemFacts slot, WantedItem wanted, Set<String> slotTags) {
        return !slot.isEmpty() && slot.unenchanted() && wanted.matches(slot.itemId(), slotTags);
    }
}
