// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.base.Precondition;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 把副手中点名物品的那一叠换进一个空主格。副手物品对"按 36 个主格计数与取用"的动作不可见，
 * 这是副手物资回到主背包口径的原生转移入口；一次任务单只换一叠。
 * 交换原语要求世界视野（不开任何界面），因此等待期间只关闭挡路页面，不打开背包屏。
 */
public final class OffhandSupplyTask extends AbstractCompanionTask<OffhandSupplyTaskRecord> {
    private static final int SWAP_TIMEOUT_TICKS = 60;
    private MenuReceipt receipt;
    private int beforeMain;
    private boolean submitted, moved;
    private boolean uncertain;
    private String movedItemId;

    public OffhandSupplyTask(LocalPlayer player, OffhandSupplyTaskRecord record) { super(player, record); }

    @Override protected List<Precondition> preconditions() {
        return List.of(() -> matchingOffhand() != null ? null
                : new Precondition.Failure("the offhand does not carry any requested item", FailureType.NO_MATERIAL));
    }

    @Override protected void onStart() { beforeMain = mainCount(); }

    @Override protected TaskState onTick() {
        var context = ClientRuntime.requireContext(player);
        InputDriver.halt(player);
        if (moved) return TaskState.SUCCESS;
        if (receipt != null) return settle(context);
        if (!worldViewReady(context)) return TaskState.RUNNING;
        var stack = matchingOffhand();
        if (stack == null) {
            fail("the offhand stack disappeared before the swap", FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        int slot = firstEmptyMainSlot(player.getInventory());
        if (slot < 0) {
            fail("no empty main inventory slot to receive the offhand stack", FailureType.NO_SPACE);
            return TaskState.FAILED;
        }
        beforeMain = mainCount();
        movedItemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        receipt = context.menus().swapInventoryToOffhand(context, slot, SWAP_TIMEOUT_TICKS);
        submitted = true;
        return TaskState.RUNNING;
    }

    /** 已提交的换位必须结清回执；目标数被上一刻拾取满足时也不能留下未确认的点击。 */
    @Override public boolean mustSettleBeforeSatisfiedCancellation() { return submitted && receipt != null; }

    @Override public void requestSatisfiedSettlement() { /* 已提交的回执照常推进，结清后按实际结果收场 */ }

    /** 交换原语要求无挡路页面、默认背包菜单和空鼠标；不满足时先交由世界视野准备关闭，下一刻重新核对。 */
    private boolean worldViewReady(LocalPlayerContext context) {
        if (context.minecraft().screen == null
                && context.player().containerMenu == context.player().inventoryMenu
                && context.player().inventoryMenu.getCarried().isEmpty()) return true;
        context.menus().ensureWorldVisible(context);
        return false;
    }

    private TaskState settle(LocalPlayerContext context) {
        receipt = context.menus().poll(context, receipt);
        if (!receipt.terminal()) return TaskState.RUNNING;
        var status = receipt.status();
        receipt = null;
        if (status == MenuReceipt.Status.UNCERTAIN) {
            uncertain = true;
            fail("offhand swap entered native submission; outcome unknown: preserve the current scene for manual review",
                    FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        if (status != MenuReceipt.Status.CONFIRMED_APPLIED) {
            fail("offhand swap was rejected by the server", FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        var stack = matchingOffhand();
        int afterMain = mainCount();
        if (stack != null || afterMain <= beforeMain) {
            // 服务端确认与本地观察各查一遍：副手应清空对应物品，主背包净增才认账。
            uncertain = true;
            fail("offhand swap was confirmed but the inventory delta did not match", FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        moved = true;
        return TaskState.SUCCESS;
    }

    /** 副手中第一格点名物品；没有返回 null。 */
    private ItemStack matchingOffhand() {
        var stack = player.getOffhandItem();
        return stack.isEmpty() || r.items.stream().noneMatch(id -> stack.is(BuiltInRegistries.ITEM.get(id)))
                ? null : stack;
    }

    /** 第一个空主格：先看快捷栏（选中即可直接使用），再走主背包；返回背包 0-35 格号。 */
    static int firstEmptyMainSlot(net.minecraft.world.entity.player.Inventory inventory) {
        for (int i = 0; i < 9; i++) if (inventory.getItem(i).isEmpty()) return i;
        for (int i = 9; i < 36; i++) if (inventory.getItem(i).isEmpty()) return i;
        return -1;
    }

    /** 主背包 36 格中点名物品的总数，即转移前后对账的口径。 */
    private int mainCount() {
        int n = 0;
        for (ResourceLocation id : r.items) n += PlayerInv.buildableCount(player.getInventory(), BuiltInRegistries.ITEM.get(id));
        return n;
    }

    @Override protected void cleanup() {
        if (receipt != null) uncertain = true;
        receipt = null;
        super.cleanup();
    }

    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("outcome_uncertain", uncertain);
        data.put("effects_started", submitted);
        if (movedItemId != null) data.put("item_id", movedItemId);
        data.put("moved_main_delta", Math.max(0, mainCount() - beforeMain));
        return data;
    }
    @Override protected String successMessage() { return "the offhand stack was swapped into the main inventory"; }
    @Override protected String cancelledMessage() { return "offhand swap interrupted"; }
    /** 面板行动行的一句话汇报。 */
    @Override public String describeCurrentAction() { return "正在把副手物品换进主背包"; }
}
