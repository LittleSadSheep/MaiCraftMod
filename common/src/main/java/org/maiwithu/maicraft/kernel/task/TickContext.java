// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.game.player.PlayerContext;

/** 任务和动作每刻拿到的上下文：本刻的游戏刻号与角色。只在本刻有效，不能保存到下一刻。 */
public interface TickContext {

    /** 当前游戏刻（世界的 gameTime），用于判断"本刻"与计算等待时长。 */
    long gameTick();

    /** 本刻的角色；离线测试里可以是替身。 */
    PlayerContext player();
}
