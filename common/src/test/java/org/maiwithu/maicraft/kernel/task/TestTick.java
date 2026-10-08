// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.platform.body.BodyContext;

/** 测试用的本刻上下文：只有游戏刻号，没有身体；执行器框架的测试不需要碰游戏。 */
record TestTick(long gameTick) implements TickContext {
    @Override public BodyContext body() {
        return null;
    }
}
