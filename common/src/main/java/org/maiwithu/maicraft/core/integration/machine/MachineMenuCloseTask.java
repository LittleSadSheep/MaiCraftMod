// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 关闭本流程机器菜单或已清空操作格的普通玩家背包，等待原生回执；其他界面与未结物品保留。
 */
public final class MachineMenuCloseTask extends AbstractCompanionTask<MachineMenuCloseTaskRecord> {
    private AbstractContainerMenu menu;
    private MenuReceipt receipt;
    private boolean closeAttempted;
    private boolean verified;
    private String failureCode;

    public MachineMenuCloseTask(LocalPlayer player, MachineMenuCloseTaskRecord record) { super(player, record); }

    @Override protected TaskState onTick() {
        var context = ClientRuntime.requireContext(player);
        if (receipt != null) {
            receipt = context.menus().poll(context, receipt);
            if (!receipt.terminal()) return TaskState.RUNNING;
            if (receipt.status() == MenuReceipt.Status.CONFIRMED_APPLIED
                    && player.containerMenu == player.inventoryMenu && context.minecraft().screen == null) {
                verified = true; return TaskState.SUCCESS;
            }
            return failure("machine_menu_close_unconfirmed", "The native machine-menu close was not confirmed; inspect the current menu.");
        }
        if (closeAttempted) return failure("machine_menu_close_uncertain", "A close was already entered without a complete receipt; no second close was sent.");
        if (player.containerMenu == player.inventoryMenu) {
            if (context.minecraft().screen == null) { verified = true; return TaskState.SUCCESS; }
            // 明确的关菜单请求可以交接普通空闲背包；有合成原料、鼠标物品或其他页面时不盲目关掉。
            if (!MenuVisibility.idlePlayerInventory(context.minecraft(), player))
                return failure("inventory_screen_not_idle", "The player inventory has unsettled items or another screen is open.");
            if (!context.mutationAvailable()) return TaskState.RUNNING;
            menu = player.inventoryMenu; closeAttempted = true;
            receipt = context.menus().close(context, 40); return TaskState.RUNNING;
        }
        if (menu == null) menu = player.containerMenu;
        if (player.containerMenu != menu || !MachineMenu.ownedMenu(player, menu)) {
            return failure("machine_menu_not_owned", "The active menu was not opened by this machine operation.");
        }
        if (!menu.getCarried().isEmpty()) return failure("machine_cursor_not_empty", "The menu cursor carries a stack; reconcile it before a normal close.");
        if (!context.mutationAvailable()) return TaskState.RUNNING;
        closeAttempted = true;
        receipt = context.menus().close(context, 40);
        return TaskState.RUNNING;
    }

    private TaskState failure(String code, String message) {
        failureCode = code; fail(message, FailureType.UNKNOWN); return TaskState.FAILED;
    }

    @Override public boolean mustSettleBeforeSatisfiedCancellation() { return closeAttempted && !verified && receipt != null && !receipt.terminal(); }
    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("menu_close_verified", verified);
        data.put("effects_started", closeAttempted);
        data.put("outcome_uncertain", closeAttempted && !verified);
        data.put("mechanical_retry_allowed", !closeAttempted);
        if (failureCode != null) data.put("failure_code", failureCode);
        return data;
    }
    @Override protected String successMessage() { return "The menu is closed and ordinary world interaction is available."; }
}
