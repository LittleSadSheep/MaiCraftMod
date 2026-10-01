package org.maiwithu.maicraft.core.task.mine;

import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;

/**
 * 记下某次无路结果对应的起点、目标集合和判定时刻，避免把旧结果套到已经变化的局面上。
 * 起点目前用 Vec3 精确比较，细小位置变化也会视为新的搜索条件。
 */
public record NoPathVerdict(Vec3 source, GoalCompiler.CompiledFingerprint goals, String detail, long attemptTick) {
    public enum Next { SEARCH, WAIT_FOR_QUERY, FAIL }

    // 寻路失败是时点观察而非永久事实：世界可能在结论之后变化而不触发任何事件。
    // 与 StockEvidence 的 MAX_AGE_TICKS 同一过期契约——超龄或时钟回拨的旧结论不再裁决，强制重扫。
    public static final long MAX_AGE_TICKS = 1200;

    // 出发位置或目标集合变了就允许再搜；证据超龄同样重扫；都没变时，查询还没完成则继续等，查完了才接受无路结论。
    public Next next(Vec3 currentSource, GoalCompiler.CompiledFingerprint currentGoals,
                     boolean queryComplete, long currentTick) {
        long age = currentTick - attemptTick;
        if (age < 0 || age > MAX_AGE_TICKS) return Next.SEARCH;
        if (!source.equals(currentSource) || !goals.equals(currentGoals)) return Next.SEARCH;
        return queryComplete ? Next.FAIL : Next.WAIT_FOR_QUERY;
    }
}
