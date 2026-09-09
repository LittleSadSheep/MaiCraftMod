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

/**
 * 逐个清空请求的装备栏，把物品收回背包；不会为了腾空间主动丢物品。
 * 主手可以通过切到空快捷栏来腾空。放不下的装备会记入 kept，当前完成状态与提示存在 A44／A45 的问题。
 */
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

    // 按请求的栏位顺序逐件处理，空栏位直接略过；已经发出的搬运要先等结果，再处理下一件。
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
        // 当前处理完列表就返回成功；全部因为空间不足而留下时，removed 仍为空，文字却会说本来就空（A45）。
        if (index >= r.slots.size()) {
            if (removed.isEmpty()) message = "nothing to take off — " + r.label + " already empty";
            else message = "took off " + String.join(", ", removed)
                    + (kept.isEmpty() ? "" : "; still wearing " + String.join(", ", kept));
            return menuSession.close(context) ? TaskState.SUCCESS : TaskState.RUNNING;
        }
        pendingSlot = r.slots.get(index);
        pendingPiece = player.getItemBySlot(pendingSlot).copy();
        if (pendingSlot == EquipmentSlot.MAINHAND) return freeMainHand();
        // 当前一定要有空格才尝试卸下；副手物品本可并入已有堆叠时也会被挡住，见 A44。
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

    // 腾空主手优先切到空快捷栏格；没有空快捷栏时，尝试把当前手持格快速移入主背包。
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

    // 把装备栏名换成玩家背包界面的槽号：头到脚是 5～8，副手是 45。这与 Inventory 的下标不是一套编号。
    private static int equipmentMenuSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> 5; case CHEST -> 6; case LEGS -> 7; case FEET -> 8;
            case OFFHAND -> 45; default -> -1;
        };
    }
    private static String itemName(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }
    // 结束背包会话，清掉搬运与快捷栏选择的局部状态。
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
