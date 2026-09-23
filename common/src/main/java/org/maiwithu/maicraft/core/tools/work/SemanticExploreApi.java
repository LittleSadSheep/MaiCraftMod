package org.maiwithu.maicraft.core.tools.work;

import org.maiwithu.maicraft.agent.tool.ToolRegistry;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.task.explore.SemanticExploreTaskRecord;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;

/** 语义探索的注册入口和直接任务记录 API。 */
public final class SemanticExploreApi {
    public static final int DEFAULT_MAX_DISTANCE = 768;
    private static final long MIN_INITIAL_LEASE_TICKS = 3L * 60L * 20L;
    private static final long MAX_INITIAL_LEASE_TICKS = 20L * 60L * 20L;

    private SemanticExploreApi() {}

    /** 根注册入口：从现有工具注册方法中调用一次。 */
    public static void register() {
        SemanticExploreTaskRecord.ensureRegistered();
        ToolRegistry.register(new SemanticExploreTool());
    }

    /**
     * 不需要工具封装器时，供意图或运行时直接调用的入口。返回的记录通过 TaskDispatch 派发；不会向模型暴露路线或航点。
     */
    public static SemanticExploreTaskRecord newRecord(
            ToolContext context, String target, Integer maxDistance, Boolean mayAlterTerrain) {
        return newRecord(context, target, maxDistance, mayAlterTerrain, null);
    }

    public static SemanticExploreTaskRecord newRecord(
            ToolContext context, String target, Integer maxDistance, Boolean mayAlterTerrain, String transportMode) {
        int distance = Math.clamp(
                maxDistance == null ? DEFAULT_MAX_DISTANCE : maxDistance,
                SemanticExploreTaskRecord.MIN_DISTANCE,
                SemanticExploreTaskRecord.MAX_DISTANCE);
        long initialLease = Math.clamp(60L * 20L + distance * 12L,
                MIN_INITIAL_LEASE_TICKS, MAX_INITIAL_LEASE_TICKS);
        return new SemanticExploreTaskRecord(
                context.toolCallId(), context.deadline(initialLease), target, distance,
                Boolean.TRUE.equals(mayAlterTerrain), TransportMode.parse(transportMode));
    }
}
