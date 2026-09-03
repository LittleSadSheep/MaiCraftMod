package org.maiwithu.maicraft.core.task.inventory;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.base.Precondition;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;
import org.maiwithu.maicraft.task.TaskState;

/** One creative slot packet per tick, followed by a later inventory-fact confirmation. */
public final class CreativeTakeItemsCompanionTask
        extends AbstractCompanionTask<CreativeTakeItemsTaskRecord> {
    private int beforeTotal;
    private int added;
    private int pendingInventorySlot = -1;
    private ItemStack expected = ItemStack.EMPTY;
    private NativeActionReceipt receipt;
    private final VisibleMenuSession menuSession = new VisibleMenuSession();

    public CreativeTakeItemsCompanionTask(LocalPlayer player, CreativeTakeItemsTaskRecord record) {
        super(player, record);
    }
    @Override protected java.util.List<Precondition> preconditions() {
        return java.util.List.of(() -> player.getAbilities().instabuild ? null
                : new Precondition.Failure("take_items is available only in creative mode",
                        FailureType.UNSUPPORTED));
    }
    @Override protected void onStart() {
        beforeTotal = PlayerInv.count(player.getInventory(), r.template.getItem());
    }
    @Override protected TaskState onTick() {
        var context = ClientRuntime.requireContext(player);
        if (added >= r.count) {
            return menuSession.close(context) ? TaskState.SUCCESS : TaskState.RUNNING;
        }
        if (receipt != null) {
            if (!receipt.terminal()) return TaskState.RUNNING;
            if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
                fail("creative inventory slot update was not confirmed: " + receipt.detail(),
                        FailureType.UNKNOWN);
                return TaskState.FAILED;
            }
            added = PlayerInv.count(player.getInventory(), r.template.getItem()) - beforeTotal;
            receipt = null;
            pendingInventorySlot = -1;
            expected = ItemStack.EMPTY;
            return TaskState.RUNNING;
        }

        if (!menuSession.inventoryReady(context)) return TaskState.RUNNING;
        int remaining = r.count - added;
        int slot = destinationSlot();
        if (slot < 0) {
            fail("creative inventory has no compatible or empty main-inventory slot",
                    FailureType.NO_SPACE);
            return TaskState.FAILED;
        }
        ItemStack current = player.getInventory().getItem(slot);
        int capacity = r.template.getMaxStackSize() - (current.isEmpty() ? 0 : current.getCount());
        int amount = Math.min(remaining, capacity);
        expected = r.template.copyWithCount((current.isEmpty() ? 0 : current.getCount()) + amount);
        pendingInventorySlot = slot;
        receipt = context.actions().creativeSetSlot(context, slot, expected, 20);
        return TaskState.RUNNING;
    }
    private int destinationSlot() {
        for (int i = 0; i < Math.min(36, player.getInventory().getContainerSize()); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, r.template)
                    && stack.getCount() < stack.getMaxStackSize()) return i;
        }
        for (int i = 0; i < Math.min(36, player.getInventory().getContainerSize()); i++) {
            if (player.getInventory().getItem(i).isEmpty()) return i;
        }
        return -1;
    }
    @Override protected void cleanup() {
        menuSession.cleanup(player);
        receipt = null;
        pendingInventorySlot = -1;
        expected = ItemStack.EMPTY;
    }
    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("requested", r.count); data.put("added", added);
        data.put("item", r.template.getItem().toString()); return data;
    }
    @Override protected String successMessage() { return "created " + added + " item(s) in creative inventory"; }
    @Override protected String cancelledMessage() { return "creative take interrupted"; }
}
