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
    public final net.minecraft.world.phys.Vec3 interactionFocus;
    public BoardStructureTaskRecord(String callId, long deadline, UUID structureId) {
        this(callId,deadline,structureId,null);
    }
    public BoardStructureTaskRecord(String callId,long deadline,UUID structureId,net.minecraft.world.phys.Vec3 interactionFocus) {
        super("board_structure",callId,deadline);
        this.structureId = java.util.Objects.requireNonNull(structureId);
        this.interactionFocus=interactionFocus;
    }
    @Override public String describe() { return "登上目标物理结构并在支撑面站稳"; }
}
