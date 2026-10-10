// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;



import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 容器存取接缝：经容器界面把主背包里的一堆存进一个已知容器。
 *
 * <p>实现走过去、点开容器、快速移动进去、确认后关上（读端 {@link ClientContainerDeposits}）；
 * 接缝没接上（传 {@code Optional.empty()}）时腾地方跳过存箱这一步。
 */
public interface ContainerDeposits {

    /**
     * 把一堆存进容器：存进去并确认了是做成；走过去、开界面、点击等确认都算还在做；
     * 容器不在了、界面开不了、放不下是做不了。
     *
     * @param permissions 这次任务的许可：走过去能动多少地形按它来
     */
    SpaceStepResult deposit(KnownContainer container, BackpackStack stack, Permissions permissions,
            TickContext context);
}
