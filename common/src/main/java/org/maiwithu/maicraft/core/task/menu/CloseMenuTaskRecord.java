package org.maiwithu.maicraft.core.task.menu;

import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

// 保存“关闭当前菜单”的请求与时限；没有保存预期菜单编号或对象。
public final class CloseMenuTaskRecord extends TaskRecord {
    // 类第一次初始化时登记对应执行器。
    static { TaskFactory.register(CloseMenuTaskRecord.class, CloseMenuCompanionTask::new); }
    public CloseMenuTaskRecord(String callId, long deadline) { super("close_menu", callId, deadline); }
}
