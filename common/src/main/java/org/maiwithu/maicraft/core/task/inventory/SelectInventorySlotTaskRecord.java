package org.maiwithu.maicraft.core.task.inventory;

import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

// 保存要选择的 Inventory 下标；合法范围在执行器中检查。
public final class SelectInventorySlotTaskRecord extends TaskRecord {
    // 第一次初始化这个记录类时会登记执行器；MaiCraftCore 中也有同类登记，注册位置并不集中。
    static { TaskFactory.register(SelectInventorySlotTaskRecord.class, SelectInventorySlotCompanionTask::new); }
    public final int slot;
    public SelectInventorySlotTaskRecord(String callId, long deadline, int slot) {
        super("select_inventory_slot", callId, deadline); this.slot = slot;
    }
}
