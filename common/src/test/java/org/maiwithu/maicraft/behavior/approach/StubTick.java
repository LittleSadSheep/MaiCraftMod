// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 本刻上下文替身：只有刻号；站位判断与靠近动作的离线测试不碰角色对象。 */
record StubTick(long gameTick) implements TickContext {
    @Override public PlayerContext player() {
        throw new IllegalStateException("这些测试不应该碰到角色对象");
    }
}
