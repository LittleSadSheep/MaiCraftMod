package org.maiwithu.maicraft.core.task.inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;
import org.maiwithu.maicraft.task.TaskState;

/** Unequip through confirmed hotbar selection or inventory-menu QUICK_MOVE clicks. */
public final class UnequipCompanionTask extends AbstractCompanionTask<UnequipTaskRecord> {
    private final FirstPersonActionGate selection = new FirstPersonActionGate();
    private final List<String> removed = new ArrayList<>();
    private final List<String> kept = new ArrayList<>();
    private int index;
    private MenuReceipt receipt;
    private EquipmentSlot pendingSlot;
    private ItemStack pendingPiece = ItemStack.EMPTY;
    private final VisibleMenuSession menuSession = new VisibleMenuSession();
    private String message = "";

    public UnequipCompanionTask(LocalPlayer player, UnequipTaskRecord record) { super(player, record); }

    @Override protected TaskState onTick() {
        var context = ClientRuntime.requireContext(player);
        if (receipt != null) {
            receipt = context.menus().poll(context, receipt);
            if (!receipt.terminal()) return TaskState.RUNNING;
            if (receipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
                fail("unequip transaction was not confirmed: " + receipt.detail(), FailureType.NO_SPACE);
                return TaskState.FAILED;
            }
            receipt = null;
            removed.add(itemName(pendingPiece) + " (" + pendingSlot.getName() + ")");
            index++; pendingSlot = null; pendingPiece = ItemStack.EMPTY;
        }
        while (index < r.slots.size() && player.getItemBySlot(r.slots.get(index)).isEmpty()) index++;
        if (index >= r.slots.size()) {
            if (removed.isEmpty()) message = "nothing to take off — " + r.label + " already empty";
            else message = "took off " + String.join(", ", removed)
                    + (kept.isEmpty() ? "" : "; still wearing " + String.join(", ", kept));
            return menuSession.close(context) ? TaskState.SUCCESS : TaskState.RUNNING;
        }
        pendingSlot = r.slots.get(index);
        pendingPiece = player.getItemBySlot(pendingSlot).copy();
        if (pendingSlot == EquipmentSlot.MAINHAND) return freeMainHand();
        if (player.getInventory().getFreeSlot() < 0) {
            kept.add(itemName(pendingPiece) + " (" + pendingSlot.getName() + ")");
            index++;
            return TaskState.RUNNING;
        }
        int menuSlot = equipmentMenuSlot(pendingSlot);
        if (menuSlot < 0) {
            fail("unsupported equipment slot: " + pendingSlot.getName(), FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        if (!menuSession.inventoryReady(context)) return TaskState.RUNNING;
        receipt = context.menus().click(context, menuSlot, 0, ClickType.QUICK_MOVE,
                (c, ignored) -> c.player().getItemBySlot(pendingSlot).isEmpty()
                        ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING,
                20);
        return TaskState.RUNNING;
    }

    private TaskState freeMainHand() {
        Inventory inv = player.getInventory();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (!inv.getItem(slot).isEmpty()) continue;
            FirstPersonActionGate.Status status = selection.select(player, slot);
            if (status == FirstPersonActionGate.Status.RUNNING) return TaskState.RUNNING;
            if (status == FirstPersonActionGate.Status.FAILED) {
                fail(selection.failure(), FailureType.UNKNOWN); return TaskState.FAILED;
            }
            removed.add("main hand freed (selected empty hotbar slot)");
            index++; selection.reset(); pendingSlot = null; pendingPiece = ItemStack.EMPTY;
            return TaskState.RUNNING;
        }
        if (inv.getFreeSlot() < 0) {
            kept.add(itemName(pendingPiece) + " (mainhand)"); index++; return TaskState.RUNNING;
        }
        int menuSlot = 36 + inv.selected;
        var context = ClientRuntime.requireContext(player);
        if (!menuSession.inventoryReady(context)) return TaskState.RUNNING;
        receipt = context.menus().click(context, menuSlot, 0, ClickType.QUICK_MOVE,
                (c, ignored) -> c.player().getMainHandItem().isEmpty()
                        ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING,
                20);
        return TaskState.RUNNING;
    }

    private static int equipmentMenuSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> 5; case CHEST -> 6; case LEGS -> 7; case FEET -> 8;
            case OFFHAND -> 45; default -> -1;
        };
    }
    private static String itemName(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }
    @Override protected void cleanup() {
        menuSession.cleanup(player);
        receipt = null;
        selection.reset();
    }
    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        if (!removed.isEmpty()) data.put("removed", List.copyOf(removed));
        if (!kept.isEmpty()) data.put("still_worn", List.copyOf(kept)); return data;
    }
    @Override protected String successMessage() { return message; }
    @Override protected String cancelledMessage() { return "unequip interrupted"; }
}
