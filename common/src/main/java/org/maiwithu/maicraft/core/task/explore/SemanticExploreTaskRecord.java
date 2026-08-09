package org.maiwithu.maicraft.core.task.explore;

import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** One bounded semantic search-and-travel goal. */
public final class SemanticExploreTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "explore";
    public static final int MIN_DISTANCE = 64;
    public static final int MAX_DISTANCE = 2_048;
    private static final int WAYPOINT_GRID = 64;

    static {
        TaskFactory.register(SemanticExploreTaskRecord.class,
                SemanticExploreCompanionTask::new);
    }

    public final String target;
    public final int maxDistance;
    public final int maxWaypoints;
    public final boolean mayAlterTerrain;

    public SemanticExploreTaskRecord(
            String toolCallId, long deadlineGameTime, String target,
            int maxDistance, boolean mayAlterTerrain) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException(
                    "explore target is required: coast, a namespaced biome id, or #biome_tag");
        }
        this.target = target.trim();
        this.maxDistance = Math.clamp(maxDistance, MIN_DISTANCE, MAX_DISTANCE);
        int rings = Math.max(1, (this.maxDistance + WAYPOINT_GRID - 1) / WAYPOINT_GRID);
        this.maxWaypoints = Math.clamp(rings * rings * 4, 8, 96);
        this.mayAlterTerrain = mayAlterTerrain;
    }

    /** Calling this method forces static task registration during Mod initialization. */
    public static void ensureRegistered() {}

    @Override public String describe() {
        return "探索 " + target + "，范围 " + maxDistance + " 格"
                + (mayAlterTerrain ? "（允许开路）" : "");
    }
}
