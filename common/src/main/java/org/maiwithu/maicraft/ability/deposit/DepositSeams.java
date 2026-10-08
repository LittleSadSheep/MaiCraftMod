// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 存东西能力的执行接缝。由启动时创建并登记接上；没接（null）时相应环节按"这条路还没通"处理。
 */
final class DepositSeams {

    private DepositSeams() {}

    /**
     * 界面里的整堆快速移动：把角色背包侧的一个槽位整堆送往另一侧。
     * 游戏接口层的菜单点击支持修饰键后由它实现；没接上时存不进去，按不支持说。
     */
    interface QuickMoves {
        void quickMove(int playerSlotId);
    }

    /** 挖开压住容器盖子的天然方块（树叶这类）；没接上或不该挖时给空。 */
    interface DigsLid {
        Optional<Action> dig(BlockPos lidCell);
    }

    /** 背包物品标签判断：一个物品 ID 在不在一个标签里；接缝没接上时永远为假。 */
    interface ReadsItemTags {
        boolean taggedIn(String itemId, String tagId);
    }
}
