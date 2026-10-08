// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;
import org.maiwithu.maicraft.behavior.inventory.KnownContainer;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 开箱取物的执行接缝：走到容器跟前，打开界面，核对现场后把要的物品搬进背包，再关上界面。
 * 记忆只是线索，箱子里有什么开了才算数，到了要核对——这些现场动作（靠近、点开、按搬运计划取放、
 * 关界面、把看到的内容记进世界记忆）由游戏接口层用靠近与界面会话的公开接口实现，测试用替身。
 * 原料来源不是容器、或现在打不开时返回 empty。
 */
public interface ContainerTakes {

    /** 为一次取物生成动作：做完时想要的东西已在背包里（拿到多少以重新清点为准）。 */
    Optional<Action> take(KnownContainer container, ItemRequest request);
}
