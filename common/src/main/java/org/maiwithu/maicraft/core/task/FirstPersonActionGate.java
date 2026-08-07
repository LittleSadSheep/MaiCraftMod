package org.maiwithu.maicraft.core.task;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/**
 * Per-task gate for selecting a real hotbar item. Receipts deliberately live on the task instance:
 * no later body action may assume a selection or inventory swap until the server-synchronized
 * result has settled on a later client tick.
 */
public final class FirstPersonActionGate {
    public enum Status { RUNNING, READY, FAILED }

    private static final int CONFIRM_TICKS = 20;

    private MenuReceipt staging;
    private NativeActionReceipt selecting;
    private int requestedInventorySlot = -1;
    private int selectedHotbarSlot = -1;
    private boolean ready;
    private String failure = "selection was not confirmed";

    public Status select(LocalPlayer player, int inventorySlot) {
        if (inventorySlot < 0 || inventorySlot >= Math.min(36, player.getInventory().getContainerSize())) {
            failure = "inventory slot is unavailable: " + inventorySlot;
            return Status.FAILED;
        }
        if (requestedInventorySlot != -1 && requestedInventorySlot != inventorySlot) {
            failure = "selection target changed while a receipt was pending";
            return Status.FAILED;
        }
        requestedInventorySlot = inventorySlot;
        if (ready) return Status.READY;
        LocalPlayerContext context = ClientRuntime.requireContext(player);

        if (staging != null) {
            staging = context.menus().poll(context, staging);
            if (!staging.terminal()) return Status.RUNNING;
            if (staging.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
                failure = "inventory staging was not confirmed: " + staging.detail();
                return Status.FAILED;
            }
            staging = null;
            return Status.RUNNING; // one native mutation per tick; select on the next tick
        }

        if (selecting != null) {
            selecting = context.actions().poll(context, selecting);
            if (!selecting.terminal()) return Status.RUNNING;
            if (selecting.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
                failure = "hotbar selection was not confirmed: " + selecting.detail();
                return Status.FAILED;
            }
            selecting = null;
            ready = true;
            return Status.READY;
        }

        selectedHotbarSlot = inventorySlot < 9
                ? inventorySlot : player.getInventory().selected;
        if (inventorySlot >= 9) {
            staging = context.menus().swapInventoryToHotbar(
                    context, inventorySlot, selectedHotbarSlot, CONFIRM_TICKS);
            return Status.RUNNING;
        }
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

    /** Logical reset only. The actor boundary owns orphaned native/menu receipt cleanup. */
    public void reset() {
        staging = null;
        selecting = null;
        requestedInventorySlot = -1;
        selectedHotbarSlot = -1;
        ready = false;
    }
}
