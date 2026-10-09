// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import java.util.List;
import java.util.OptionalInt;

/**
 * 背包视图：只读地看角色背包主格子（36 格）里有什么、还空几格。
 *
 * <p>同一条游戏事实只在这里实现一次：捡东西、合成取出、交易、腾地方都从这一个视图读背包，
 * 不各自去翻玩家的物品栏。盔甲与副手不占主格子，不在视图里。
 */
public interface BackpackView {

    /** 背包主格里非空的物品堆，按格子顺序。 */
    List<BackpackStack> stacks();

    /** 已经放了这个角色东西的主格数量。 */
    int usedSlots();

    /** 背包主格的总数。 */
    int totalSlots();

    /**
     * 快捷栏（主格的前 9 格）里第一格放着这种物品的格号（0–8）；没有时为空。
     * 读不到快捷栏的视图（只关心数量的替身）按"没有"回答。
     */
    default OptionalInt hotbarSlotOf(String itemId) {
        return OptionalInt.empty();
    }

    /** 现在手上选中的是快捷栏第几格（0–8）；读不到时为 -1。 */
    default int selectedHotbarSlot() {
        return -1;
    }

    /** 还空着几格；捡东西、存取之前先看它。 */
    default int freeSlots() {
        return totalSlots() - usedSlots();
    }
}
