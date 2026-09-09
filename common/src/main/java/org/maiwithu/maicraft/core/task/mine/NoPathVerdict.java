package org.maiwithu.maicraft.core.task.mine;

import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;

/**
 * 记下某次无路结果对应的起点和目标集合，避免把旧结果套到已经变化的局面上。
 * 起点目前用 Vec3 精确比较，细小位置变化也会视为新的搜索条件。
 */
public record NoPathVerdict(Vec3 source, GoalCompiler.CompiledFingerprint goals, String detail) {
    public enum Next { SEARCH, WAIT_FOR_QUERY, FAIL }

    // 出发位置或目标集合变了就允许再搜；都没变时，查询还没完成则继续等，查完了才接受无路结论。
    public Next next(Vec3 currentSource, GoalCompiler.CompiledFingerprint currentGoals, boolean queryComplete) {
        if (!source.equals(currentSource) || !goals.equals(currentGoals)) return Next.SEARCH;
        return queryComplete ? Next.FAIL : Next.WAIT_FOR_QUERY;
    }
}
