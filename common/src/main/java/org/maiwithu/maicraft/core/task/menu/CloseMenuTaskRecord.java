package org.maiwithu.maicraft.core.task.menu;

import java.util.Objects;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;

// 组合任务可绑定自己打开的菜单；菜单换掉后不能关闭新界面。无绑定构造保留“关闭当前菜单”的显式用途。
public final class CloseMenuTaskRecord extends TaskRecord {
    // 类第一次初始化时登记对应执行器。
    static { TaskFactory.register(CloseMenuTaskRecord.class, CloseMenuCompanionTask::new); }
    public final AbstractContainerMenu expectedMenu;
    public CloseMenuTaskRecord(String callId, long deadline) {
        super("close_menu", callId, deadline);
        expectedMenu = null;
    }
    public CloseMenuTaskRecord(String callId, long deadline, AbstractContainerMenu expectedMenu) {
        // 父任务只安排关闭已结清、鼠标为空的界面；若中途又拿起物品，执行器保留界面交回处理。
        super("close_menu", callId, deadline);
        this.expectedMenu = Objects.requireNonNull(expectedMenu, "expectedMenu");
    }
}
