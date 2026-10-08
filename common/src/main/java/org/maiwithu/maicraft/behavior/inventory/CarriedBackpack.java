// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.result.Change;

import java.util.Optional;

/**
 * 随身背包接缝：便携模组给的随身容器（背包、潜影盒之类），腾地方时先把东西塞进去。
 *
 * <p>联动模组轨之后才有实现；现在没有实现，腾地方跳过这一步。
 */
public interface CarriedBackpack {

    /** 随身背包还空着几格。 */
    int freeSlots();

    /**
     * 把主背包里的一堆放进随身背包。放进去并确认了才返回变化；
     * 放不下或还没确认就返回空，调用方下一刻重看背包视图再决定。
     */
    Optional<Change> store(BackpackStack stack);
}
