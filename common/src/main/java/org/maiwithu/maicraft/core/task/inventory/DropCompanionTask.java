package org.maiwithu.maicraft.core.task.inventory;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.FailureType;

import org.maiwithu.maicraft.task.TaskState;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.DiscardedItems;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * 停步朝远处看 → 原生分堆 → 整份丢弃 → 确认扣数并保留实际掉落物避让。
 * 每次菜单点击单独核实，未知投掷绝不重发；数量不足时最多丢现有数量。
 */
public final class DropCompanionTask extends AbstractCompanionTask<DropItemsTaskRecord> {
    private static final long DROP_PROGRESS_LEASE_TICKS = 10L * 20L;

    private int dropped;
    private String doneMessage = "done";
    private MenuReceipt receipt;
    private int target;
    private int batches, attempts;
    private boolean initialized, uncertain;
    private List<DropBatchPlan.Click> plan = List.of();
    private int step;
    private DropBatchPlan.Click pendingClick;
    private final DropAim aim = new DropAim();
    private final List<DiscardedItems.Watch> watches = new ArrayList<>();
    private final VisibleMenuSession menuSession = new VisibleMenuSession();

    public DropCompanionTask(LocalPlayer player, DropItemsTaskRecord record) {
        super(player, record);
    }

    @Override
    // 先结清旧界面返料，再固定总量；分堆不是丢弃，只有最终投掷的精确回执才能累计完成量。
    protected TaskState onTick() {
        var context = ClientRuntime.requireContext(player);
        if (!initialized) {
            if (!menuSession.worldReady(context)) return TaskState.RUNNING;
            target = Math.min(r.count, PlayerInv.count(player.getInventory(), r.item)); initialized = true;
            if (target == 0) { fail("no " + r.label + " in inventory to drop", FailureType.NO_MATERIAL); return TaskState.FAILED; }
        }
        if (receipt != null) {
            receipt = context.menus().poll(context, receipt);
            if (!receipt.terminal()) return TaskState.RUNNING;
            if (receipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
                uncertain |= pendingClick.dropped() > 0 && receipt.status() != MenuReceipt.Status.CONFIRMED_NOT_APPLIED;
                fail("item drop or split was not confirmed: " + receipt.detail(), FailureType.UNKNOWN);
                return TaskState.FAILED;
            }
            acceptClick();
            r.extendDeadlineTo(player.level().getGameTime() + DROP_PROGRESS_LEASE_TICKS);
        }
        if (dropped >= target) {
            doneMessage = "dropped " + dropped + "x " + r.label
                    + (dropped < r.count ? " (only had " + dropped + ")" : "");
            return menuSession.close(context) ? TaskState.SUCCESS : TaskState.RUNNING;
        }
        if (!aim.ready(context) || !menuSession.inventoryReady(context)) return TaskState.RUNNING;
        if (step >= plan.size()) {
            int inventorySlot = DropBatchPlan.source(player.getInventory(), r.item, target - dropped);
            if (inventorySlot < 0) {
                fail("the item stack disappeared before all requested drops were confirmed", FailureType.TARGET_LOST);
                return TaskState.FAILED;
            }
            int amount = Math.min(player.getInventory().getItem(inventorySlot).getCount(), target - dropped);
            plan = DropBatchPlan.plan(player.containerMenu, player.getInventory(), inventorySlot, amount); step = 0;
        }
        var click = plan.get(step);
        if (!click.matches(player.containerMenu, false)) {
            fail("drop batch inventory or cursor changed before submission", FailureType.TARGET_LOST); return TaskState.FAILED;
        }
        // 在原生提交前冻结实体基线；即使投掷途中取消，客户端仍跟踪已发出的物品，后继寻路不能重新捡回。
        if (click.dropped() > 0) {
            ItemStack kind = click.slot() == -999 ? player.containerMenu.getCarried() : player.containerMenu.getSlot(click.slot()).getItem();
            watches.add(DiscardedItems.watch(player, kind, click.dropped())); attempts++;
        }
        pendingClick = click;
        receipt = context.menus().click(context, click.slot(), click.button(), click.type(), click.confirmation(), 40);
        return TaskState.RUNNING;
    }

    private void acceptClick() {
        if (pendingClick.dropped() > 0) batches++;
        dropped += pendingClick.dropped(); step++; receipt = null; pendingClick = null;
    }

    @Override
    protected void cleanup() {
        // 取消时保留已经冻结的完成量；未结投掷明确报告未知，鼠标上的未丢余料由原生关闭背包返还。
        if (receipt != null && receipt.status() == MenuReceipt.Status.CONFIRMED_APPLIED) acceptClick();
        uncertain |= pendingClick != null && pendingClick.dropped() > 0
                && (receipt == null || receipt.status() != MenuReceipt.Status.CONFIRMED_NOT_APPLIED);
        menuSession.cleanup(player); super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        data.put("dropped", dropped);
        data.put("remaining_in_inventory", PlayerInv.count(player.getInventory(), r.item));
        data.put("drop_batches", batches);
        data.put("drop_attempts", attempts);
        data.put("outcome_uncertain", uncertain);
        data.put("discarded_item_avoidance", watches.stream().map(DiscardedItems.Watch::result).toList());
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
