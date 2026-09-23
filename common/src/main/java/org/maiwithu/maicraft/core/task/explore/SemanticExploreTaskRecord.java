package org.maiwithu.maicraft.core.task.explore;

import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;

/** 一个有界的语义搜索与移动目标。 */
public final class SemanticExploreTaskRecord extends TaskRecord
        implements InternalPositionReceipt {
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
    public final TransportMode transportMode;
    private Position verifiedPosition;

    public SemanticExploreTaskRecord(
            String toolCallId, long deadlineGameTime, String target,
            int maxDistance, boolean mayAlterTerrain) {
        this(toolCallId, deadlineGameTime, target, maxDistance, mayAlterTerrain, TransportMode.AUTO);
    }

    public SemanticExploreTaskRecord(
            String toolCallId, long deadlineGameTime, String target,
            int maxDistance, boolean mayAlterTerrain, TransportMode transportMode) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.transportMode = transportMode == null ? TransportMode.AUTO : transportMode;
        if (this.transportMode == TransportMode.JETPACK || this.transportMode == TransportMode.ELEVATOR) {
            throw new IllegalArgumentException("explore transport_mode must be auto or ground; "
                    + "jetpack/elevator travel needs a located destination first.");
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException(
                    "explore target is required: coast, a namespaced biome id, or #biome_tag");
        }
        this.target = target.trim();
        this.maxDistance = Math.clamp(maxDistance, MIN_DISTANCE, MAX_DISTANCE);
        int rings = Math.max(1, (this.maxDistance + WAYPOINT_GRID - 1) / WAYPOINT_GRID);
        // 覆盖范围由目标区域决定，不使用任意尝试次数上限。在 MAX_DISTANCE 下仍是较小的有限网格，运行时会对已访问边界去重。
        this.maxWaypoints = Math.max(8, rings * rings * 4);
        this.mayAlterTerrain = mayAlterTerrain;
    }

    /** 调用此方法会在 Mod 初始化期间强制完成静态任务注册。 */
    public static void ensureRegistered() {}

    void retainVerifiedPosition(Position position) {
        verifiedPosition = position;
    }

    @Override
    public Position internalVerifiedPosition() {
        return verifiedPosition;
    }

    @Override public String describe() {
        return "探索 " + target + "，范围 " + maxDistance + " 格"
                + (mayAlterTerrain ? "（允许开路）" : "");
    }
}
