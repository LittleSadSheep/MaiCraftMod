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
    private final NavGoal.SemanticFingerprint fingerprint;

    MaiCraftGoalAdapter(NavGoal delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        // 同一静止目标每刻重新包装仍属同一搜索请求；真正移动或改变容差时才让旧失败失效。
        this.fingerprint = delegate.semanticFingerprint();
    }

    NavGoal delegate() {
        return delegate;
    }

    /** 取回包装前的项目目标；非本适配器包装的目标（调试或原版目标）返回 null。 */
    static NavGoal unwrap(Goal goal) {
        return goal instanceof MaiCraftGoalAdapter adapter ? adapter.delegate() : null;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return delegate.isAt(new BlockPos(x, y, z));
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return delegate.heuristic(new BlockPos(x, y, z));
    }

    @Override public boolean equals(Object other) {
        return other instanceof MaiCraftGoalAdapter adapter && fingerprint.equals(adapter.fingerprint);
    }
    @Override public int hashCode() { return fingerprint.hashCode(); }

    @Override
    public String toString() {
        return "MaiCraftGoal{" + delegate.getClass().getSimpleName()
                + " center=" + delegate.center().toShortString() + '}';
    }
}
