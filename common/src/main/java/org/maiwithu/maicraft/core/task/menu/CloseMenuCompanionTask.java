package org.maiwithu.maicraft.core.task.menu;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

// 关闭执行时正在打开的菜单并等待确认。记录没有绑定某个旧菜单，调用方要负责确认当前菜单确实归它处理。
public final class CloseMenuCompanionTask extends AbstractCompanionTask<CloseMenuTaskRecord> {
    private MenuReceipt receipt;
    public CloseMenuCompanionTask(LocalPlayer player, CloseMenuTaskRecord record) { super(player, record); }
    // 第一次发关闭请求，后续只等待同一个请求；确认成功才结束，失败则保留原因。
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
