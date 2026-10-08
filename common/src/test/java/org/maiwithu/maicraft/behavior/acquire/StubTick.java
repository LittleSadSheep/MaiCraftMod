// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 测试替身：只有刻号的每刻上下文；获取的判断不碰角色本体，动作推进也用不到它。 */
record StubTick(long gameTick) implements TickContext {
    @Override public PlayerContext player() {
        return null;
    }
}
