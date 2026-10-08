// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.maiwithu.maicraft.kernel.result.Change;

import java.util.Optional;

/**
 * 丢弃接缝：把主背包里的东西丢到地上。丢出去是真实的世界变化，确认了才算数。
 *
 * <p>容器界面与交互轨之后才有实现；现在没有实现，腾地方只走合并和随身背包。
 */
public interface ItemDropper {

    /**
     * 丢掉多少件某种物品（通常是一整格）。丢出去并确认了才返回变化；
     * 东西已经不在了或还没确认就返回空。
     */
    Optional<Change> drop(String itemId, int count);
}
