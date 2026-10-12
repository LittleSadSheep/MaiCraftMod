// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.spi;

import java.util.Optional;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 走路前要收起来的随身东西：开着会碍事的，像误开的喷气背包——开着走路一起跳就被推高、
 * 走下台阶悬在半空慢慢沉。走路每刻推进前问一遍：要收就给收起来的做法，走路先停下等它收好；不用收为空。
 * 由联动模组登记。
 */
public interface StowBeforeWalking {

    /** 此刻要不要先收起来：要就给收起来的做法，不用为空。 */
    Optional<Action> stow(PlayerContext context);
}
