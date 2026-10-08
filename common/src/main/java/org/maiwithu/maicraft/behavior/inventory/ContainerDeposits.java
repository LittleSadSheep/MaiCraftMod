// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.result.Change;

import java.util.Optional;

/**
 * 容器存取接缝：经容器界面把主背包里的一堆存进一个已知容器。
 *
 * <p>容器界面轨之后才有实现；现在没有实现，腾地方跳过存箱这一步。
 */
public interface ContainerDeposits {

    /**
     * 把一堆存进容器。存进去并确认了才返回变化；
     * 容器不在了、界面开不了或还没确认就返回空，调用方下一刻重看现场再决定。
     */
    Optional<Change> deposit(KnownContainer container, BackpackStack stack);
}
