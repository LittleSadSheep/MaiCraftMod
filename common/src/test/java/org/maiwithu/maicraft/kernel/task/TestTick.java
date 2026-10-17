// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.game.player.PlayerContext;

/** 测试用的本刻上下文：只有游戏刻号，没有角色；分阶段任务的测试不需要碰游戏。 */
record TestTick(long gameTick) implements TickContext {
    @Override public PlayerContext player() {
        return null;
    }
}
