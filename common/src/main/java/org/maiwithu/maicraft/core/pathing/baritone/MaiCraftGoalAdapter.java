// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.pathing.goals.Goal;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;

/**
 * 把项目的目标判断交给 Baritone：把三个坐标转成脚位格，再转发到达判断和搜索估价；这里不额外增加距离或高度条件。
 */
final class MaiCraftGoalAdapter implements Goal {
    private final NavGoal delegate;

    MaiCraftGoalAdapter(NavGoal delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    NavGoal delegate() {
        return delegate;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return delegate.isAt(new BlockPos(x, y, z));
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return delegate.heuristic(new BlockPos(x, y, z));
    }

    @Override
    public String toString() {
        return "MaiCraftGoal{" + delegate.getClass().getSimpleName()
                + " center=" + delegate.center().toShortString() + '}';
    }
}
