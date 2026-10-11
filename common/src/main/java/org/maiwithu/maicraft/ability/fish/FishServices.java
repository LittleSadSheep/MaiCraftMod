// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fish;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.ProtectedCells;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.behavior.inventory.MovesToMainhand;
import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.game.player.FishingHookFacts;

/**
 * 钓鱼任务组合的玩家行为：缺竿去拿、换手、走到钓位、捡起钓获、腾背包、读鱼钩与保护判断。
 * 服务从构造函数传入；测试换成各自的替身。
 */
record FishServices(ItemNeeds obtain, MovesToMainhand toMainhand, WalkTo walks,
                    PicksUpDrops pickups, InventorySpace space,
                    FishingHookFacts hooks, ProtectedCells protectedCells) {

    /** 保护接缝没接上时没有格子被当成受保护。 */
    static ProtectedCells nothingProtected() {
        return at -> false;
    }
}
