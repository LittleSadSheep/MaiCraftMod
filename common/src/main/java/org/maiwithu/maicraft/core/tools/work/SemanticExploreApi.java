package org.maiwithu.maicraft.core.tools.work;

import org.maiwithu.maicraft.agent.tool.ToolRegistry;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.task.explore.SemanticExploreTaskRecord;

/** Registration and direct-record API for semantic exploration. */
public final class SemanticExploreApi {
    public static final int DEFAULT_MAX_DISTANCE = 768;
    private static final long MIN_INITIAL_LEASE_TICKS = 3L * 60L * 20L;
    private static final long MAX_INITIAL_LEASE_TICKS = 20L * 60L * 20L;

    private SemanticExploreApi() {}

    /** Root registration point: call once from the existing tool-registration method. */
    public static void register() {
        SemanticExploreTaskRecord.ensureRegistered();
        ToolRegistry.register(new SemanticExploreTool());
    }

    /**
     * Intent/runtime entry point when a tool wrapper is not needed. Dispatch the returned record
     * through TaskDispatch; no route or waypoint is exposed to the model.
     */
    public static SemanticExploreTaskRecord newRecord(
            ToolContext context, String target, Integer maxDistance, Boolean mayAlterTerrain) {
        int distance = Math.clamp(
                maxDistance == null ? DEFAULT_MAX_DISTANCE : maxDistance,
                SemanticExploreTaskRecord.MIN_DISTANCE,
                SemanticExploreTaskRecord.MAX_DISTANCE);
        long initialLease = Math.clamp(60L * 20L + distance * 12L,
                MIN_INITIAL_LEASE_TICKS, MAX_INITIAL_LEASE_TICKS);
        return new SemanticExploreTaskRecord(
                context.toolCallId(), context.deadline(initialLease), target, distance,
                Boolean.TRUE.equals(mayAlterTerrain));
    }
}
