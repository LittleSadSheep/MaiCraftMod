package org.maiwithu.maicraft.core.task.sleep;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

public final class SleepTaskRecord extends TaskRecord {
    static { TaskFactory.register(SleepTaskRecord.class, SleepCompanionTask::new); }
    public final BlockPos bed;
    /** 自动夜间休息要等自然醒；旧的显式“上床”原语仍可只确认躺下。 */
    public boolean waitUntilAwake;
    public SleepTaskRecord untilAwake() { waitUntilAwake = true; return this; }
    public SleepTaskRecord(String callId, long deadline, BlockPos bed) {
        super("sleep", callId, deadline); this.bed = bed.immutable();
    }
}
