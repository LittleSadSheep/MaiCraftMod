package org.maiwithu.maicraft.core.task.locate;

import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 保存查找结构的原始名字或标签，以及这次请求的截止时间。实际检查在客户端任务里进行。
 */
public final class LocateStructureTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "locate_structure";

    /**
     * 保留输入的资源名；以 # 开头表示标签，具体是否存在由执行任务检查。
     */
    public final String structure;

    public LocateStructureTaskRecord(String toolCallId, long deadlineGameTime, String structure) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.structure = structure;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + structure;
    }
}
