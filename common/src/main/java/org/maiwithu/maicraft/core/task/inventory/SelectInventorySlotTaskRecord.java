package org.maiwithu.maicraft.core.task.inventory;

import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

public final class SelectInventorySlotTaskRecord extends TaskRecord {
    static { TaskFactory.register(SelectInventorySlotTaskRecord.class, SelectInventorySlotCompanionTask::new); }
    public final int slot;
    public SelectInventorySlotTaskRecord(String callId, long deadline, int slot) {
        super("select_inventory_slot", callId, deadline); this.slot = slot;
    }
}
