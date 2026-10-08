// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.platform.body.BodyContext;

/** 执行器和子步骤每刻拿到的上下文：本刻的游戏刻号与身体。只在本刻有效，不能保存到下一刻。 */
public interface TickContext {

    /** 当前游戏刻（世界的 gameTime），用于判断"本刻"与计算等待时长。 */
    long gameTick();

    /** 本刻的身体；离线测试里可以是替身。 */
    BodyContext body();
}
