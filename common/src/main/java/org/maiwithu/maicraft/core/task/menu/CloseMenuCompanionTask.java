package org.maiwithu.maicraft.core.task.menu;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

public final class CloseMenuCompanionTask extends AbstractCompanionTask<CloseMenuTaskRecord> {
    private MenuReceipt receipt;
    public CloseMenuCompanionTask(LocalPlayer player, CloseMenuTaskRecord record) { super(player, record); }
    @Override protected TaskState onTick() {
        var context = ClientRuntime.requireContext(player);
        if (receipt == null) {
            receipt = context.menus().close(context, 20);
            return TaskState.RUNNING;
        }
        receipt = context.menus().poll(context, receipt);
        if (!receipt.terminal()) return TaskState.RUNNING;
        if (receipt.status() == MenuReceipt.Status.CONFIRMED_APPLIED) return TaskState.SUCCESS;
        fail("menu close was not confirmed: " + receipt.detail(), FailureType.UNKNOWN);
        return TaskState.FAILED;
    }
    @Override protected void cleanup() { receipt = null; }
    @Override protected String successMessage() { return "closed the active menu"; }
    @Override protected String cancelledMessage() { return "menu close interrupted"; }
}
