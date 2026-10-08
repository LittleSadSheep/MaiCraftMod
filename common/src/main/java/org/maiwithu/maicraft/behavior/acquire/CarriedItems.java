// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;

/**
 * 身上清点：主背包加副手里，有多少件是这次要的东西。
 * "身上有多少"这一条游戏事实全仓只有这一处数法，引擎重新清点与身上来源报价都用它。
 */
public final class CarriedItems {

    private CarriedItems() {}

    /** 身上（主背包与副手）有多少件请求要的东西。 */
    public static int matching(BackpackView backpack, OffhandContents offhand,
            ItemRequest request, ReadsItemTags tags) {
        return matching(backpack, offhand, request.wanted(), tags);
    }

    /** 身上（主背包与副手）有多少件想要的东西；来源问价与引擎清点共用这一个数法。 */
    public static int matching(BackpackView backpack, OffhandContents offhand,
            WantedItem wanted, ReadsItemTags tags) {
        int total = 0;
        for (var stack : backpack.stacks()) {
            if (wanted.matches(stack.itemId(), tags.tagsOf(stack.itemId()))) {
                total += stack.count();
            }
        }
        if (offhand != null) {
            total += offhand.heldInOffhand()
                    .filter(stack -> wanted.matches(stack.itemId(), tags.tagsOf(stack.itemId())))
                    .map(BackpackStack::count)
                    .orElse(0);
        }
        return total;
    }

    /** 身上有没有一件够格的工具：挖矿前先看它，缺了才去准备。 */
    public static boolean hasToolThatSuffices(BackpackView backpack, OffhandContents offhand,
            String blockType, ReadsToolRequirements requirements) {
        for (var stack : backpack.stacks()) {
            if (requirements.sufficient(stack.itemId(), blockType)) return true;
        }
        return offhand != null && offhand.heldInOffhand().isPresent()
                && requirements.sufficient(offhand.heldInOffhand().get().itemId(), blockType);
    }

    private static int matchingCount(BackpackStack stack, ItemRequest request, ReadsItemTags tags) {
        return request.wanted().matches(stack.itemId(), tags.tagsOf(stack.itemId())) ? stack.count() : 0;
    }
}
