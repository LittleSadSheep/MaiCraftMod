// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import org.maiwithu.maicraft.game.player.BackpackStack;


/**
 * 容器存取接缝：经容器界面把主背包里的一堆存进一个已知容器。
 *
 * <p>容器界面轨之后才有实现；现在没有实现，腾地方跳过存箱这一步。
 */
public interface ContainerDeposits {

    /**
     * 把一堆存进容器：存进去并确认了是做成；走过去、开界面、点击等确认都算还在做；
     * 容器不在了、界面开不了是做不了。
     */
    SpaceStepResult deposit(KnownContainer container, BackpackStack stack);
}
