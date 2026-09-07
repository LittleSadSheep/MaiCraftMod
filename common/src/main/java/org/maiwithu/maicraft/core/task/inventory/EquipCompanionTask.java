package org.maiwithu.maicraft.core.task.inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.act.Interaction;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.base.Precondition;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;
import org.maiwithu.maicraft.task.TaskState;

/** Equip using only synchronized hotbar selection, native item use, or an offhand SWAP click. */
public final class EquipCompanionTask extends AbstractCompanionTask<EquipTaskRecord> {
    private final FirstPersonActionGate selection = new FirstPersonActionGate();
    private int sourceSlot;
    private Item item;
    private EquipmentSlot targetSlot;
    private Interaction use;
    private MenuReceipt menuReceipt;
    private boolean offhandApplied;
    private final VisibleMenuSession menuSession = new VisibleMenuSession();
    private String message = "";
    private String slotName = "";

    public EquipCompanionTask(LocalPlayer player, EquipTaskRecord record) { super(player, record); }

    @Override protected List<Precondition> preconditions() {
        return List.of(() -> findItem(player.getInventory()) >= 0 ? null
                : new Precondition.Failure("no " + r.label + " in inventory to equip",
                        FailureType.NO_MATERIAL));
    }

    @Override protected void onStart() {
        sourceSlot = findItem(player.getInventory());
        var stack = player.getInventory().getItem(sourceSlot);
        item = stack.getItem();
        EquipmentSlot naturalSlot = player.getEquipmentSlotForItem(stack);
        targetSlot = r.slot == null ? naturalSlot : r.slot;
        if (!player.canUseSlot(targetSlot) || targetSlot != EquipmentSlot.MAINHAND
                && targetSlot != EquipmentSlot.OFFHAND && targetSlot != naturalSlot) {
            fail(r.label + " cannot be equipped in " + targetSlot.getName(), FailureType.UNKNOWN);
        }
    }

    @Override protected TaskState onTick() {
        if (targetSlot == EquipmentSlot.OFFHAND) return equipOffhand();
        FirstPersonActionGate.Status selected = selection.select(player, sourceSlot);
        if (selected == FirstPersonActionGate.Status.RUNNING) return TaskState.RUNNING;
        if (selected == FirstPersonActionGate.Status.FAILED) {
            fail("couldn't select " + r.label + ": " + selection.failure(), FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        if (targetSlot == EquipmentSlot.MAINHAND) return verifyEquipped();
        if (use == null) {
            var held = player.getMainHandItem();
            if (!held.is(item) || player.getEquipmentSlotForItem(held) != targetSlot) {
                fail("the selected item no longer matches the requested equipment slot", FailureType.TARGET_LOST);
                return TaskState.FAILED;
            }
            use = Interaction.useInAir(player, InteractionHand.MAIN_HAND, Interaction.Timing.once());
        }
        return switch (use.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case FAILED -> { fail("native equip failed: " + use.failReason(), FailureType.UNKNOWN); yield TaskState.FAILED; }
            case DONE -> verifyEquipped();
        };
    }

    private TaskState equipOffhand() {
        var context = ClientRuntime.requireContext(player);
        if (offhandApplied) {
            return menuSession.close(context) ? TaskState.SUCCESS : TaskState.RUNNING;
        }
        if (menuReceipt != null) {
            menuReceipt = context.menus().poll(context, menuReceipt);
            if (!menuReceipt.terminal()) return TaskState.RUNNING;
            if (menuReceipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
                fail("offhand transaction was not confirmed: " + menuReceipt.detail(), FailureType.UNKNOWN);
                return TaskState.FAILED;
            }
            menuReceipt = null;
            if (verifyEquipped() == TaskState.FAILED) return TaskState.FAILED;
            offhandApplied = true;
            return TaskState.RUNNING;
        }
        if (!menuSession.inventoryReady(context)) return TaskState.RUNNING;
        sourceSlot = findItem(player.getInventory());
        if (sourceSlot < 0) {
            fail("the item disappeared before offhand equip", FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        int menuSlot = sourceSlot < 9 ? 36 + sourceSlot : sourceSlot;
        var before = player.getOffhandItem().copy();
        menuReceipt = context.menus().click(context, menuSlot, 40, ClickType.SWAP,
                (c, ignored) -> c.player().getOffhandItem().is(item)
                        && !same(c.player().getOffhandItem(), before)
                        ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING,
                20);
        return TaskState.RUNNING;
    }

    private TaskState verifyEquipped() {
        if (!player.getItemBySlot(targetSlot).is(item)) {
            fail(r.label + " was not observed in " + targetSlot.getName(),
                    FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        slotName = targetSlot.getName();
        message = targetSlot == EquipmentSlot.MAINHAND ? "holding " + r.label + " in main hand"
                : "equipped " + r.label + " in " + slotName;
        return TaskState.SUCCESS;
    }

    private int findItem(Inventory inv) {
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            if (!inv.getItem(i).isEmpty() && inv.getItem(i).is(r.item)) return i;
        }
        return -1;
    }
    private static boolean same(net.minecraft.world.item.ItemStack a, net.minecraft.world.item.ItemStack b) {
        return a.getCount() == b.getCount() && net.minecraft.world.item.ItemStack.isSameItemSameComponents(a, b);
    }
    @Override protected void cleanup() {
        if (use != null) use.stop();
        selection.reset();
        menuSession.cleanup(player);
        menuReceipt = null;
    }
    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>(); data.put("item", r.label);
        if (!slotName.isEmpty()) data.put("slot", slotName); return data;
    }
    @Override protected String successMessage() { return message; }
    @Override protected String cancelledMessage() { return "equip interrupted"; }
}
