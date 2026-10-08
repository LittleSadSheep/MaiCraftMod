// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 测试用的本刻上下文：只有游戏刻号，没有角色；目标推进的测试不需要碰游戏。 */
record GoalTestTick(long gameTick) implements TickContext {
    @Override public PlayerContext player() {
        return null;
    }
}
