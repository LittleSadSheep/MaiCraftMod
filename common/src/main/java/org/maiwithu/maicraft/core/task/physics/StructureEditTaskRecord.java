package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonArray;
import java.util.UUID;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

/** 保存起飞前选定的局部方块补丁，施工时重新投影到当前世界位置。 */
public final class StructureEditTaskRecord extends TaskRecord {
    static { TaskFactory.register(StructureEditTaskRecord.class,StructureEditTask::new); }
    final UUID structureId; final JsonArray edits;
    public StructureEditTaskRecord(String callId,long deadline,UUID id,JsonArray edits) {
        super("edit_physical_structure",callId,deadline); structureId=id; this.edits=edits.deepCopy();
    }
    @Override public String describe() { return "起飞前修改物理结构的 "+edits.size()+" 格方块"; }
}
