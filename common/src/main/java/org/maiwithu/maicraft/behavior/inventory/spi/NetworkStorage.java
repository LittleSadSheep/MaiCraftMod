// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.spi;

import java.util.Optional;

import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 网络存储接缝：身上带着连着物流网络的无线终端（AE2 的无线终端）时，腾地方、整理背包先把东西存进网络，
 * 像玩家一样拿出终端打开、把那一堆快速移动进去、关上——玩家整理背包就是往 AE 里放，不往地上扔。
 *
 * <p>实现由联动模组提供，启动时按模组是否安装登记；没有实现方、身上没有终端时，腾地方跳过这一步。
 */
public interface NetworkStorage {

    /** 身上有没有能存东西的终端（上次打开时连着网络、没看到说连不上）。 */
    boolean available();

    /**
     * 把主背包里的一堆存进网络的动作：存进去并确认了算做完，终端打不开、没连上、网络放不下按问题失败；
     * 确认存进去的（按背包少了几件记成存进了网络）与没能确认的交互由动作记进 records。身上没有终端时给空。
     *
     * @param purpose 为什么存，写进变化备注，例如"腾地方（捡起掉落的铁锭）"
     */
    Optional<Action> store(BackpackStack stack, String purpose, TaskRecords records);
}
