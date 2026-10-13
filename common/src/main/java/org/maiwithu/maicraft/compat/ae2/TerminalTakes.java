// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 从终端取货的执行接缝：走到 ME 终端跟前，点在面板上打开，核对现场后把要的东西逐笔取进背包，再关上。
 * 现场动作由 {@link MenuTerminalTakes} 用靠近与界面会话的公开接口实现，测试用替身。
 */
public interface TerminalTakes {

    /**
     * 为一次取货生成动作：做完时想要的东西已在背包里（拿到多少以引擎重新清点为准）。
     *
     * @param terminal    去哪一台终端
     * @param dimension   终端所在的维度，记网络存货时用
     * @param request     要什么、还要多少
     * @param permissions 这次任务的许可：走过去能动多少地形按它来
     */
    Optional<Action> take(Ae2Terminals.Terminal terminal, String dimension, ItemRequest request,
            Permissions permissions);

    /**
     * 拿出身上的无线终端取货：换到主手右键打开，其余与在终端前取一样。不用走路。
     *
     * @param terminalItemId 身上那件无线终端的物品 ID
     */
    default Optional<Action> takeWireless(String terminalItemId, ItemRequest request) {
        return Optional.empty();
    }

    /** 拿出身上的无线终端打开，等存货同步齐记下来就关上，什么都不拿；做不了给空。 */
    default Optional<Action> lookWireless(String terminalItemId, ItemRequest request) {
        return Optional.empty();
    }
}
