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
import net.minecraft.world.phys.Vec3;

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
    private DiscardSitePreparation site = new DiscardSitePreparation();
    private boolean siteReady;
    private DiscardFire fire;
    private DiscardRecovery recovery;
    private boolean recoveryAccounted;
    private boolean allowBurn = true;
    private int recovered, thrown;
    private final List<Map<String, Object>> disposalAttempts = new ArrayList<>();
    private final List<DiscardedItems.Watch> watches = new ArrayList<>();
    private VisibleMenuSession menuSession = new VisibleMenuSession();

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
        if (recovery != null) {
            TaskState state = recovery.tick(player); r.extendDeadlineTo(player.level().getGameTime() + DROP_PROGRESS_LEASE_TICKS);
            if (state == TaskState.RUNNING) return state;
            accountRecovery();
            if (state != TaskState.SUCCESS) { fail("unburned passage items could not be recovered; see native pickup evidence", FailureType.UNKNOWN); return state; }
            // 真正回收入包后才重选位置；第二次只用不堵路的空地或侧袋，不再对同一份耐火余物反复点火。
            recovery = null; fire = null; site = new DiscardSitePreparation(false); siteReady = false; allowBurn = false;
            menuSession = new VisibleMenuSession(); plan = List.of(); step = 0;
        }
        if (!siteReady) {
            // 有打火石先选可尝试销毁的位置；其余情况先确认通道外空地或挖好侧袋，准备期间物品仍留在背包里。
            TaskState preparation = site.tick(context);
            if (preparation == TaskState.FAILED) { fail(site.failure(), FailureType.NO_PATH); return preparation; }
            r.extendDeadlineTo(player.level().getGameTime() + DROP_PROGRESS_LEASE_TICKS);
            if (preparation != TaskState.SUCCESS) return TaskState.RUNNING;
            siteReady = true; aim.direction(site.plan().direction());
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
            if (!menuSession.close(context)) return TaskState.RUNNING;
            // 丢弃数量已确认之后才按真实落点点火；销毁检查和扑火不能改写已经完成的原生投掷数量。
            if (fire == null) fire = new DiscardFire(watches, allowBurn);
            r.extendDeadlineTo(player.level().getGameTime() + DROP_PROGRESS_LEASE_TICKS);
            TaskState state = fire.tick(context);
            if (state == TaskState.SUCCESS && site.plan().requiresBurn()) {
                if (watches.stream().anyMatch(DiscardedItems.Watch::pending)) return TaskState.RUNNING;
                if (!fire.remaining().isEmpty()) {
                    fire.close(context);
                    recovery = new DiscardRecovery(player, r.getToolCallId(), watches, fire.remaining());
                    recoveryAccounted = false;
                    return TaskState.RUNNING;
                }
            }
            return state;
        }
        if (Vec3.atBottomCenterOf(site.plan().stance()).subtract(player.position()).horizontalDistanceSqr() > .09) {
            fail("discard stance changed before throwing; remaining items were retained", FailureType.STANCE_DUD); return TaskState.FAILED;
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
        thrown += pendingClick.dropped();
        dropped += pendingClick.dropped(); step++; receipt = null; pendingClick = null;
    }

    private void accountRecovery() {
        // 回收已经有原生入包证据时立即保留数量；取消恰好落在实体移除等待窗口，也不能丢掉已拿回的物品事实。
        if (recoveryAccounted) return;
        disposalAttempts.add(Map.of("site", site.result(), "fire", fire.result(), "recovery", recovery.result()));
        recovered += recovery.collected(); dropped = Math.max(0, dropped - recovery.collected()); recoveryAccounted = true;
    }

    @Override
    protected void cleanup() {
        // 取消时保留已经冻结的完成量；未结投掷明确报告未知，鼠标上的未丢余料由原生关闭背包返还。
        if (receipt != null && receipt.status() == MenuReceipt.Status.CONFIRMED_APPLIED) acceptClick();
        uncertain |= pendingClick != null && pendingClick.dropped() > 0
                && (receipt == null || receipt.status() != MenuReceipt.Status.CONFIRMED_NOT_APPLIED);
        menuSession.cleanup(player);
        try {
            var context = ClientRuntime.requireContext(player); site.close(context);
            if (fire != null) fire.close(context);
            if (recovery != null) { recovery.close(player); accountRecovery(); }
        } catch (RuntimeException unavailable) { /* 身体交接后保留未结现场，不借新身体重复投掷或点火。 */ }
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        data.put("dropped", dropped);
        data.put("remaining_in_inventory", PlayerInv.count(player.getInventory(), r.item));
        data.put("drop_batches", batches);
        data.put("drop_attempts", attempts);
        data.put("confirmed_thrown_units", thrown);
        data.put("recovered_after_burn", recovered);
        data.put("disposal_attempts", List.copyOf(disposalAttempts));
        data.put("outcome_uncertain", uncertain);
        data.put("discarded_item_avoidance", watches.stream().map(DiscardedItems.Watch::result).toList());
        data.put("discard_site", site.result());
        if (fire != null) data.put("discard_fire", fire.result());
        return data;
    }

    /** 面板行动行的一句话汇报；物品名来自任务单标签，计数是已确认丢出的数量；销毁与回收阶段各有独立说法。 */
    @Override public String describeCurrentAction() {
        if (recovery != null) return "正在回收丢弃物";
        if (!siteReady) return "正在准备丢弃位置";
        return "正在丢弃 " + r.label + " (" + dropped + "/" + target + ")";
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
