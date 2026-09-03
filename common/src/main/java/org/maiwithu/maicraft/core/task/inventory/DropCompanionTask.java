package org.maiwithu.maicraft.core.task.inventory;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.FailureType;

import org.maiwithu.maicraft.task.TaskState;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.base.Precondition;
import net.minecraft.world.inventory.ClickType;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Toss inventory items through visible, confirmed inventory-menu clicks. */
public final class DropCompanionTask extends AbstractCompanionTask<DropItemsTaskRecord> {
    private static final long DROP_PROGRESS_LEASE_TICKS = 10L * 20L;

    private int dropped;
    private String doneMessage = "done";
    private MenuReceipt receipt;
    private int target;
    private int pendingDrop;
    private final VisibleMenuSession menuSession = new VisibleMenuSession();

    public DropCompanionTask(LocalPlayer player, DropItemsTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(
                () -> PlayerInv.count(player.getInventory(), r.item) > 0 ? null
                        : new Precondition.Failure("no " + r.label + " in inventory to drop",
                                FailureType.NO_MATERIAL));
    }

    @Override
    protected void onStart() {
        Inventory inv = player.getInventory();
        int have = PlayerInv.count(inv, r.item);
        target = Math.min(r.count, have);
    }

    @Override
    protected TaskState onTick() {
        var context = ClientRuntime.requireContext(player);
        if (receipt != null) {
            receipt = context.menus().poll(context, receipt);
            if (!receipt.terminal()) return TaskState.RUNNING;
            if (receipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
                fail("item drop was not confirmed: " + receipt.detail(), FailureType.UNKNOWN);
                return TaskState.FAILED;
            }
            receipt = null;
            dropped += pendingDrop;
            pendingDrop = 0;
            r.extendDeadlineTo(player.level().getGameTime() + DROP_PROGRESS_LEASE_TICKS);
        }
        if (dropped >= target) {
            doneMessage = "dropped " + dropped + "x " + r.label
                    + (dropped < r.count ? " (only had " + dropped + ")" : "");
            return menuSession.close(context) ? TaskState.SUCCESS : TaskState.RUNNING;
        }
        if (!menuSession.inventoryReady(context)) return TaskState.RUNNING;
        int inventorySlot = PlayerInv.findSlot(player.getInventory(), r.item);
        if (inventorySlot < 0) {
            fail("the item stack disappeared before all requested drops were confirmed",
                    FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        int menuSlot = inventorySlot < 9 ? 36 + inventorySlot : inventorySlot;
        int before = player.getInventory().getItem(inventorySlot).getCount();
        int button = before <= target - dropped ? 1 : 0;
        int expectedDrop = button == 1 ? before : 1;
        receipt = context.menus().click(context, menuSlot, button, ClickType.THROW,
                (c, ignored) -> c.player().getInventory().getItem(inventorySlot).getCount() < before
                        ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING,
                20);
        pendingDrop = expectedDrop;
        return TaskState.RUNNING;
    }

    @Override
    protected void cleanup() { menuSession.cleanup(player); receipt = null; }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        data.put("dropped", dropped);
        data.put("remaining_in_inventory", PlayerInv.count(player.getInventory(), r.item));
        return data;
    }

    @Override
    protected String successMessage() {
        return doneMessage;
    }

    @Override
    protected String timeoutMessage() {
        return "drop timed out unexpectedly";
    }

    @Override
    protected String cancelledMessage() {
        return "drop interrupted";
    }
}
