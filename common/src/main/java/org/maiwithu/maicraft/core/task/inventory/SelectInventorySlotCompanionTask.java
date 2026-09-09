package org.maiwithu.maicraft.core.task.inventory;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

// 把“选择背包这一格”包装成可调度任务。具体搬到快捷栏、切换和等待由 FirstPersonActionGate 负责。
public final class SelectInventorySlotCompanionTask
        extends AbstractCompanionTask<SelectInventorySlotTaskRecord> {
    private final FirstPersonActionGate gate = new FirstPersonActionGate();
    public SelectInventorySlotCompanionTask(LocalPlayer player, SelectInventorySlotTaskRecord record) {
        super(player, record);
    }
    // 选择器仍在操作就继续等；准备好才成功，选择失败则把原因传给任务结果。
    @Override protected TaskState onTick() {
        return switch (gate.select(player, r.slot)) {
            case RUNNING -> TaskState.RUNNING;
            case READY -> TaskState.SUCCESS;
            case FAILED -> { fail(gate.failure(), FailureType.UNKNOWN); yield TaskState.FAILED; }
        };
    }
    @Override protected void cleanup() { gate.reset(); }
    @Override protected String successMessage() { return "selected inventory slot " + r.slot; }
    @Override protected String cancelledMessage() { return "inventory selection interrupted"; }
}
