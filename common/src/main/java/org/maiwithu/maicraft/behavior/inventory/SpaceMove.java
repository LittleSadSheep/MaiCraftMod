// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.maiwithu.maicraft.game.player.BackpackStack;

/**
 * 腾挪的一步：计划只说"动哪一堆、怎么动"，怎么点击、怎么丢是执行接缝的事。
 */
sealed interface SpaceMove {

    /** 把背包里同一种物品的散堆并起来，腾出整格；多少格由当前散堆的数目算出来。 */
    record MergeStacks(String itemId, int slotsFreed) implements SpaceMove {
    }

    /** 把主背包里的一堆放进随身背包。 */
    record ToCarriedBackpack(BackpackStack stack) implements SpaceMove {
    }

    /** 把主背包里的一堆存进一个已知容器。 */
    record ToKnownContainer(KnownContainer container, BackpackStack stack) implements SpaceMove {
    }

    /** 把主背包里的一堆丢到地上，从最不值钱的开始；数量是一整格。 */
    record DropStack(BackpackStack stack) implements SpaceMove {
    }
}
