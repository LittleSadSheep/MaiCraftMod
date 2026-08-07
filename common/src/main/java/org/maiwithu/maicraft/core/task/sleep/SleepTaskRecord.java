package org.maiwithu.maicraft.core.task.sleep;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

public final class SleepTaskRecord extends TaskRecord {
    static { TaskFactory.register(SleepTaskRecord.class, SleepCompanionTask::new); }
    public final BlockPos bed;
    public SleepTaskRecord(String callId, long deadline, BlockPos bed) {
        super("sleep", callId, deadline); this.bed = bed.immutable();
    }
}
