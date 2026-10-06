// SPDX-License-Identifier: GPL-3.0-only
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
import net.minecraft.world.item.ItemStack;

/**
 * 把某种物品放到指定主手、副手或盔甲栏；没写栏位时使用该物品通常的装备位置。
 * 它按物品种类查找，不是在同名物品中挑附魔最好的一件。
 */
public final class EquipCompanionTask extends AbstractCompanionTask<EquipTaskRecord> {
    private final FirstPersonActionGate selection = new FirstPersonActionGate();
    private int sourceSlot;
    private Item item;
    private EquipmentSlot targetSlot;
    private Interaction use;
    private MenuReceipt menuReceipt;
    private MenuReceipt pullReceipt;
    private boolean offhandApplied;
    private boolean offhandPulled, pullSubmitted;
    private final VisibleMenuSession menuSession = new VisibleMenuSession();
    private String message = "";
    private String slotName = "";

    public EquipCompanionTask(LocalPlayer player, EquipTaskRecord record) { super(player, record); }

    // 来源查主背包和快捷栏；主背包没有而副手握着同类物品时，先把那一叠换进空主格再装备。
    // 当前不先承认已经穿在身上的同类装备，因此已戴好头盔也可能报缺料。
    @Override protected List<Precondition> preconditions() {
        return List.of(() -> findItem(player.getInventory()) >= 0 || offhandPullPossible()
                || r.slot == EquipmentSlot.OFFHAND && offhandMatches(r.item) ? null
                : new Precondition.Failure("no " + r.label + " in inventory to equip",
                        FailureType.NO_MATERIAL));
    }

    /** 主背包没有该物品、副手有、且目标不是副手本身时，允许先把副手那叠换进空主格。 */
    private boolean offhandPullPossible() {
        var offhand = player.getOffhandItem();
        if (offhand.isEmpty() || r.slot == EquipmentSlot.OFFHAND) return false;
        if (r.exact == null ? !offhand.is(r.item) : !ItemStack.isSameItemSameComponents(offhand, r.exact)) return false;
        return OffhandSupplyTask.firstEmptyMainSlot(player.getInventory()) >= 0;
    }

    // 没指定栏位时采用物品自然对应的栏位；明确指定盔甲栏时要与物品类型匹配，主手／副手则单独允许。
    // 主背包没有该物品时从副手取：物品身份与目标栏位都按副手里那一叠认定。
    @Override protected void onStart() {
        int mainSlot = findItem(player.getInventory());
        // 主背包没有该物品时，前置检查已确认副手握着它且有空主格可接；身份与目标栏位按副手那一叠认定。
        boolean fromOffhand = mainSlot < 0;
        var stack = fromOffhand ? player.getOffhandItem() : player.getInventory().getItem(mainSlot);
        item = stack.getItem();
        EquipmentSlot naturalSlot = player.getEquipmentSlotForItem(stack);
        targetSlot = r.slot == null ? naturalSlot : r.slot;
        // 明确要求装备到副手而它已经在副手里时，无需换位，直接按已装备核对收场。
        if (targetSlot == EquipmentSlot.OFFHAND && offhandMatches(item)) {
            offhandApplied = true;
        }
        if (!player.canUseSlot(targetSlot) || targetSlot != EquipmentSlot.MAINHAND
                && targetSlot != EquipmentSlot.OFFHAND && targetSlot != naturalSlot) {
            fail(r.label + " cannot be equipped in " + targetSlot.getName(), FailureType.UNKNOWN);
        }
    }

    private boolean offhandMatches(Item expected) {
        var offhand = player.getOffhandItem();
        return !offhand.isEmpty() && offhand.is(expected);
    }

    /** 副手取用：把那一叠换进空主格后再回到常规选物流程；一次任务最多换一次，失败不重试。 */
    private TaskState pullFromOffhand() {
        var context = ClientRuntime.requireContext(player);
        if (pullReceipt == null) {
            // 交换原语要求世界视野（不开任何界面）；不满足时先关闭挡路页面，下一刻重新核对。
            if (!(context.minecraft().screen == null
                    && player.containerMenu == player.inventoryMenu
                    && player.inventoryMenu.getCarried().isEmpty())) {
                context.menus().ensureWorldVisible(context);
                return TaskState.RUNNING;
            }
            int slot = OffhandSupplyTask.firstEmptyMainSlot(player.getInventory());
            if (slot < 0) {
                fail("no empty main inventory slot to receive the offhand stack", FailureType.NO_SPACE);
                return TaskState.FAILED;
            }
            pullSubmitted = true;
            pullReceipt = context.menus().swapInventoryToOffhand(context, slot, 40);
            return TaskState.RUNNING;
        }
        pullReceipt = context.menus().poll(context, pullReceipt);
        if (!pullReceipt.terminal()) return TaskState.RUNNING;
        var status = pullReceipt.status();
        pullReceipt = null;
        if (status != MenuReceipt.Status.CONFIRMED_APPLIED) {
            fail("offhand pull was not confirmed: " + status.name(), FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        sourceSlot = findItem(player.getInventory());
        if (sourceSlot < 0) {
            fail("the offhand stack was confirmed moved but was not observed in the main inventory", FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        offhandPulled = true;
        return TaskState.RUNNING;
    }

    // 副手走背包交换；主手先选择物品；盔甲先拿到主手再使用一次，最后查目标栏位。
    @Override protected TaskState onTick() {
        if (targetSlot == EquipmentSlot.OFFHAND) return equipOffhand();
        if (!offhandPulled) {
            // 已提交的换位先结清回执；未提交且主背包没有该物品时才发起副手取用。
            if (pullSubmitted) return pullFromOffhand();
            if (findItem(player.getInventory()) < 0 && offhandPullPossible()) return pullFromOffhand();
        }
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

    // 显示背包，把来源格与副手交换，等确认后再关闭。交换已经完成时不重复点击。
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
        // 目前要求副手既是目标物品、又与交换前不同；若原先已有完全相同的一份，交换后的外观不变就无法确认。
        var before = player.getOffhandItem().copy();
        menuReceipt = context.menus().click(context, menuSlot, 40, ClickType.SWAP,
                (c, ignored) -> c.player().getOffhandItem().is(item)
                        && !same(c.player().getOffhandItem(), before)
                        ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING,
                20);
        return TaskState.RUNNING;
    }

    // 按物品种类检查目标栏位，不比较附魔、名字、耐久等完整组件。
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
        // 任务单指定原件时只认组件完全相同的那一件，避免把同种但不同附魔或耐久的另一件穿上去。
        for (int i = 0; i < Math.min(36, inv.getContainerSize()); i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty() && (r.exact == null ? stack.is(r.item) : ItemStack.isSameItemSameComponents(stack, r.exact))) return i;
        }
        return -1;
    }
    private static boolean same(ItemStack a, ItemStack b) {
        return a.getCount() == b.getCount() && ItemStack.isSameItemSameComponents(a, b);
    }
    // 结束物品使用和菜单操作，清掉局部记录；没有确认的动作仍需依赖对应接口正确收尾。
    @Override protected void cleanup() {
        if (use != null) use.stop();
        selection.reset();
        menuSession.cleanup(player);
        menuReceipt = null;
        pullReceipt = null;
    }
    /** 面板行动行的一句话汇报；物品名来自任务单标签，全程动作都是把它放到目标栏位。 */
    @Override public String describeCurrentAction() {
        return "正在装备 " + r.label;
    }

    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>(); data.put("item", r.label);
        if (offhandPulled) data.put("offhand_pulled", true);
        if (!slotName.isEmpty()) data.put("slot", slotName); return data;
    }
    @Override protected String successMessage() { return message; }
    @Override protected String cancelledMessage() { return "equip interrupted"; }
}
