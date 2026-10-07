// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.pathing.goals.Goal;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;

/**
 * 跨包读取包装目标的公开入口：包装器本身包私有，寻路内核只从这里取回项目目标语义。
 */
public final class MaiCraftGoals {

    private MaiCraftGoals() {}

    /** 取回包装在项目目标适配器里的目标；非项目目标（调试或原版目标）返回 null。 */
    public static NavGoal unwrap(Goal goal) {
        return MaiCraftGoalAdapter.unwrap(goal);
    }
}
