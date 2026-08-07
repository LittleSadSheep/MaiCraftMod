package org.maiwithu.maicraft.core.task.menu;

import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

public final class CloseMenuTaskRecord extends TaskRecord {
    static { TaskFactory.register(CloseMenuTaskRecord.class, CloseMenuCompanionTask::new); }
    public CloseMenuTaskRecord(String callId, long deadline) { super("close_menu", callId, deadline); }
}
