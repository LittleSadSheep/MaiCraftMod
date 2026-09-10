package org.maiwithu.maicraft.core.task.locate;

import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存查找群系的原始名字或标签，以及这次请求的截止时间。实际检查在客户端任务里进行。
 */
public final class LocateBiomeTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "locate_biome";

    /**
     * 保留输入的资源名；以 # 开头表示标签，具体是否存在由执行任务检查。
     */
    public final String biome;

    public LocateBiomeTaskRecord(String toolCallId, long deadlineGameTime, String biome) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.biome = biome;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + biome;
    }
}
