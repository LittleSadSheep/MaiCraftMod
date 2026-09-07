package org.maiwithu.maicraft.core.task.move;

import java.util.UUID;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** One physical boarding outcome, deliberately not a static world-coordinate receipt. */
public final class BoardStructureTaskRecord extends TaskRecord {
    static { TaskFactory.register(BoardStructureTaskRecord.class, BoardStructureTask::new); }
    public final UUID structureId;
    public BoardStructureTaskRecord(String callId, long deadline, UUID structureId) {
        super("board_structure",callId,deadline);
        this.structureId = java.util.Objects.requireNonNull(structureId);
    }
    @Override public String describe() { return "飞上目标飞艇并在甲板站稳"; }
}
