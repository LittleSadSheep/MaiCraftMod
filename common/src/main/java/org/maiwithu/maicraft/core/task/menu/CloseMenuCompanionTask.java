package org.maiwithu.maicraft.core.task.menu;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

// 关闭任务指定的菜单并等待确认；绑定对象被替换时停止，避免跨游戏刻把另一个炉子或箱子关掉。
public final class CloseMenuCompanionTask extends AbstractCompanionTask<CloseMenuTaskRecord> {
    private MenuReceipt receipt;
    public CloseMenuCompanionTask(LocalPlayer player, CloseMenuTaskRecord record) { super(player, record); }
    // 第一次发关闭请求，后续只等待同一个请求；确认成功才结束，失败则保留原因。
    @Override protected TaskState onTick() {
        var context = ClientRuntime.requireContext(player);
        if (receipt == null) {
            // 菜单已经由玩家关好就直接完成；换成另一份菜单时，即使编号复用也不能向它发关闭请求。
            if (r.expectedMenu != null && player.containerMenu != r.expectedMenu) {
                if (player.containerMenu == player.inventoryMenu) return TaskState.SUCCESS;
                fail("the requested menu was replaced before closing", FailureType.TARGET_LOST);
                return TaskState.FAILED;
            }
            if (r.expectedMenu != null && !r.expectedMenu.getCarried().isEmpty()) {
                fail("the requested menu still has a carried stack; it was left open", FailureType.UNKNOWN);
                return TaskState.FAILED;
            }
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
    @Override protected String successMessage() { return "the requested menu is closed"; }
    @Override protected String cancelledMessage() { return "menu close interrupted"; }
}
