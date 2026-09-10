package org.maiwithu.maicraft.core.task.move;

import java.util.UUID;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 记录要登上的移动结构编号。结构会移动，所以成功表示在它的甲板站稳，不保存一个固定世界坐标冒充登船结果。
 */
public final class BoardStructureTaskRecord extends TaskRecord {
    static { TaskFactory.register(BoardStructureTaskRecord.class, BoardStructureTask::new); }
    public final UUID structureId;
    public BoardStructureTaskRecord(String callId, long deadline, UUID structureId) {
        super("board_structure",callId,deadline);
        this.structureId = java.util.Objects.requireNonNull(structureId);
    }
    @Override public String describe() { return "飞上目标飞艇并在甲板站稳"; }
}
