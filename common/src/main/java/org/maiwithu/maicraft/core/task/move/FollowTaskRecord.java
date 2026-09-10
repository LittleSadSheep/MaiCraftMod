package org.maiwithu.maicraft.core.task.move;

import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存要长期跟随的实体、保持距离和开路许可，不设置完成时限。靠近后只是暂停走路，任务仍然保留。
 */
public final class FollowTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "follow";

    /** 跟到这么近就算到位(米)。 */
    public final double keepWithin;

    /**
     * 目标当前的运行编号。现用工具要求非空；遗留的空值不会自动解析成某个主人。
     */
    public final Integer entityId;

    /**
     * 用稳定身份防止重连后运行编号被别的实体复用。它只用于核对，不会自动在新世界重新找回目标。
     */
    public final java.util.UUID targetUuid;

    /** 路上可以挖/垫/架桥。默认 false:跟着走不动世界。 */
    public final boolean mayAlterTerrain;

    public FollowTaskRecord(String toolCallId, double keepWithin, Integer entityId,
                            java.util.UUID targetUuid, boolean mayAlterTerrain) {
        super(TOOL_NAME, toolCallId, NO_DEADLINE);
        this.keepWithin = keepWithin;
        this.entityId = entityId;
        this.targetUuid = targetUuid;
        this.mayAlterTerrain = mayAlterTerrain;
    }

    @Override
    /**
     * 给任务状态界面显示的一句话，说明跟谁、保持多远和是否允许开路。
     */
    public String describe() {
        String who = entityId == null ? "你" : "实体 " + entityId;
        return "跟着" + who + ",保持 " + (int) keepWithin + " 米" + (mayAlterTerrain ? "(可开路)" : "");
    }
}
