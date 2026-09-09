package org.maiwithu.maicraft.core.task;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;

/**
 * 供任务反复调用的“把这格物品拿到主手”步骤。
 * 快捷栏里的物品可以直接切换；背包里的物品先显示背包、搬到快捷栏，等确认后关界面再使用。
 * 返回 RUNNING 时还没准备好，调用方不能把它当成已经拿到手里。
 */
public final class FirstPersonActionGate {
    public enum Status { RUNNING, READY, FAILED }

    private static final int CONFIRM_TICKS = 20;

    private MenuReceipt staging;
    private NativeActionReceipt selecting;
    private int requestedInventorySlot = -1;
    private int selectedHotbarSlot = -1;
    /** The S -> H swap was confirmed; both the cached S and rediscovered H name this transaction. */
    private boolean stagedToHotbar;
    private boolean ready;
    private String failure = "selection was not confirmed";
    private VisibleMenuSession menuSession = new VisibleMenuSession();
    private LocalPlayer owner;

    // 持续推进同一次物品选择：先确认背包交换，再关界面，最后确认快捷栏选中。
    // 交换后重新查找物品可能得到快捷栏的新编号，所以先把旧交换结算，再检查编号是否真的变了。
    public Status select(LocalPlayer player, int inventorySlot) {
        owner = player;
        // A confirmed main-inventory -> hotbar swap necessarily changes where a caller that
        // rediscovers the item will find it (source S becomes hotbar H). Settle that transaction
        // before validating/comparing the freshly discovered slot; otherwise a correct S -> H
        // transition is misreported as "selection target changed" while its receipt is pending.
        if (staging != null) {
            LocalPlayerContext context = ClientRuntime.requireContext(player);
            staging = context.menus().poll(context, staging);
            if (!staging.terminal()) return Status.RUNNING;
            if (staging.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
                failure = "inventory staging was not confirmed: " + staging.detail();
                return Status.FAILED;
            }
            staging = null;
            stagedToHotbar = true;
            return Status.RUNNING; // keep selection as a separate, later-tick native mutation
        }

        // 这里只支持背包和快捷栏前 36 格；副手、盔甲栏位不能经此方法搬到主手。
        if (inventorySlot < 0 || inventorySlot >= Math.min(36, player.getInventory().getContainerSize())) {
            failure = "inventory slot is unavailable: " + inventorySlot;
            return Status.FAILED;
        }
        boolean stagedAlias = stagedToHotbar && inventorySlot == selectedHotbarSlot;
        if (requestedInventorySlot != -1
                && requestedInventorySlot != inventorySlot
                && !stagedAlias) {
            failure = "selection target changed while a receipt was pending";
            return Status.FAILED;
        }
        if (requestedInventorySlot == -1) requestedInventorySlot = inventorySlot;
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        // 已选好后仍要保证界面允许世界操作；ready 记的是选择已完成，没有重新核对该格后来是否换了物品。
        if (ready) return menuSession.worldReady(context) ? Status.READY : Status.RUNNING;

        if (selecting != null) {
            selecting = context.actions().poll(context, selecting);
            if (!selecting.terminal()) return Status.RUNNING;
            if (selecting.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
                failure = "hotbar selection was not confirmed: " + selecting.detail();
                return Status.FAILED;
            }
            selecting = null;
            ready = true;
            return Status.RUNNING;
        }

        // Do not swap S back into H when a caller intentionally keeps passing its cached source
        // slot. The confirmed transaction has already made H the physical selection target.
        if (stagedToHotbar) {
            if (!menuSession.close(context) || !context.mutationAvailable()) return Status.RUNNING;
            if (player.getInventory().selected == selectedHotbarSlot) {
                ready = true;
                return Status.READY;
            }
            selecting = context.actions().selectHotbar(
                    context, selectedHotbarSlot, CONFIRM_TICKS);
            return Status.RUNNING;
        }

        selectedHotbarSlot = inventorySlot < 9
                ? inventorySlot : player.getInventory().selected;
        if (inventorySlot >= 9) {
            if (!menuSession.inventoryReady(context)) return Status.RUNNING;
            staging = context.menus().swapInventoryToHotbar(
                    context, inventorySlot, selectedHotbarSlot, CONFIRM_TICKS);
            return Status.RUNNING;
        }
        if (!menuSession.worldReady(context)) return Status.RUNNING;
        if (player.getInventory().selected == selectedHotbarSlot) {
            ready = true;
            return Status.READY;
        }
        selecting = context.actions().selectHotbar(context, selectedHotbarSlot, CONFIRM_TICKS);
        return Status.RUNNING;
    }

    public String failure() {
        return failure;
    }

    /** Close any inventory screen opened while staging, including interrupted transactions. */
    // 结束菜单会话并忘记本次选择；这里只清掉 selecting 变量，没有退役动作端口中可能仍待确认的快捷栏选择。
    public void reset() {
        if (owner != null) menuSession.cleanup(owner);
        menuSession = new VisibleMenuSession();
        owner = null;
        staging = null;
        selecting = null;
        requestedInventorySlot = -1;
        selectedHotbarSlot = -1;
        stagedToHotbar = false;
        ready = false;
    }
}
