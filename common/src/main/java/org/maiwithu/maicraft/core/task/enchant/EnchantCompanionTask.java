// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.enchant;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskState;

/** 找背包中的一件可附魔物品 → 不改地形走近已有台子 → 原生开界面 → 一次附魔 → 核验取回并关闭。 */
public final class EnchantCompanionTask extends AbstractCompanionTask<EnchantTaskRecord> {
    private enum Phase { APPROACH, OPEN, WAIT_MENU, ENCHANT }
    private Phase phase = Phase.APPROACH;
    private final VisibleMenuSession travelMenu = new VisibleMenuSession();
    private EnchantInventory inventory;
    private EnchantMenuFlow flow;
    private Task activeChild;
    private TaskRecord activeRecord;
    private boolean openRequested;
    private long waitMenuUntil;
    private String issue;

    public EnchantCompanionTask(LocalPlayer player, EnchantTaskRecord record) { super(player, record); }

    @Override protected void onStart() {
        // 已有人正在用菜单或拿着光标物品时拒绝接管；不会为了准备附魔清空对方的工作槽。
        if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) {
            failIssue("enchantment_existing_menu_or_cursor_in_use", FailureType.INTERRUPTED); return;
        }
        for (int slot = 1; slot <= 4; slot++) if (!player.inventoryMenu.getSlot(slot).getItem().isEmpty()) {
            failIssue("enchantment_inventory_crafting_grid_in_use", FailureType.INTERRUPTED); return;
        }
        if (!tablePresent()) { failIssue("enchantment_table_missing_or_unloaded", FailureType.TARGET_LOST); return; }
        try { inventory = EnchantInventory.prepare(player, r); }
        catch (IllegalArgumentException unavailable) {
            failIssue(unavailable.getMessage(), unavailable.getMessage().contains("space") ? FailureType.NO_SPACE : FailureType.NO_MATERIAL);
        }
    }

    @Override protected TaskState onTick() {
        var context = ClientRuntime.requireContext(player);
        if (flow != null) {
            // 台子消失只通知一次；已经进入安全归还后仍需让同一搬运子任务确认，不能每刻重新打断它。
            if (!tablePresent() && !flow.hasFailure()) flow.abort("enchantment_table_changed", FailureType.TARGET_LOST);
            TaskState state = flow.tick(context);
            if (state == TaskState.FAILED) failIssue(flow.failure(), flow.failureType());
            return state;
        }
        if (!context.permitsNativeActions()) return failIssue("enchantment_control_handed_over", FailureType.INTERRUPTED);
        if (!tablePresent()) return failIssue("enchantment_table_missing_or_unloaded", FailureType.TARGET_LOST);
        if (activeChild != null) return tickChild();
        return switch (phase) {
            case APPROACH -> {
                if (!travelMenu.worldReady(context)) yield TaskState.RUNNING;
                if (player.distanceToSqr(Vec3.atCenterOf(r.table)) <= 3.75 * 3.75) {
                    phase = Phase.OPEN; yield TaskState.RUNNING;
                }
                // 到台子附近只允许地面通行；不挖、搭路、放落地水或自动建造另一张附魔台。
                yield startChild(new MoveToTaskRecord(r.getToolCallId() + "-enchant-approach", deadline(1200),
                        (double) r.table.getX(), (double) r.table.getY(), (double) r.table.getZ(), null,
                        false, false, TransportMode.GROUND, false, false, 2, 1));
            }
            case OPEN -> {
                if (!travelMenu.worldReady(context)) yield TaskState.RUNNING;
                openRequested = true;
                yield startChild(new InteractAtTaskRecord(r.getToolCallId() + "-enchant-open", deadline(200),
                        MouseButton.RIGHT, r.table, 0, null, null, Blocks.ENCHANTING_TABLE));
            }
            case WAIT_MENU -> {
                if (player.containerMenu instanceof EnchantmentMenu menu) {
                    if (!menu.getCarried().isEmpty() || !menu.getSlot(0).getItem().isEmpty() || !menu.getSlot(1).getItem().isEmpty())
                        yield failIssue("enchantment_opened_menu_already_occupied", FailureType.INTERRUPTED);
                    if (!context.menus().ensureVisible(context)) yield TaskState.RUNNING;
                    // 绑定这一张已可见、两格均为空的原生附魔菜单；后续换菜单或报价都不能沿用旧操作。
                    flow = new EnchantMenuFlow(player, r, menu, inventory, r::prepareNativeConsumptionBoundary);
                    phase = Phase.ENCHANT; yield TaskState.RUNNING;
                }
                if (player.containerMenu != player.inventoryMenu)
                    yield failIssue("enchantment_unexpected_menu_opened", FailureType.TARGET_LOST);
                if (player.level().getGameTime() >= waitMenuUntil)
                    yield failIssue("enchantment_native_menu_did_not_open", FailureType.TIMED_OUT);
                yield TaskState.RUNNING;
            }
            case ENCHANT -> throw new IllegalStateException("enchantment_menu_flow_missing");
        };
    }

    private TaskState startChild(TaskRecord record) {
        activeRecord = record; activeChild = TaskFactory.create(player, record); return TaskState.RUNNING;
    }

    private TaskState tickChild() {
        // 导航与原生右键各自检查超时；每次子任务结束都调用 result，避免把按键或未收尾操作留给下一阶段。
        TaskState state = player.level().getGameTime() >= activeRecord.getDeadlineGameTime() ? TaskState.TIMEOUT : runChild(activeChild);
        r.extendDeadlineTo(activeRecord.getDeadlineGameTime());
        if (state == null) return TaskState.RUNNING;
        if (state != TaskState.SUCCESS) activeChild.stop(player, Task.StopReason.REPLACED);
        var result = activeChild.result(state); activeChild = null; activeRecord = null;
        if (state != TaskState.SUCCESS) return failIssue("enchantment_" + phase.name().toLowerCase(java.util.Locale.ROOT)
                + "_failed: " + result.message(), state == TaskState.TIMEOUT ? FailureType.TIMED_OUT : FailureType.UNKNOWN);
        if (phase == Phase.APPROACH) phase = Phase.OPEN;
        else { phase = Phase.WAIT_MENU; waitMenuUntil = player.level().getGameTime() + 60; }
        return TaskState.RUNNING;
    }

    private boolean tablePresent() {
        return player.level().isLoaded(r.table) && player.level().getBlockState(r.table).is(Blocks.ENCHANTING_TABLE);
    }
    private long deadline(long ticks) {
        long deadline = player.level().getGameTime() + ticks; r.extendDeadlineTo(deadline); return deadline;
    }
    private TaskState failIssue(String code, FailureType type) { issue = code; fail(code, type); return TaskState.FAILED; }

    @Override public void stop(LocalPlayer companion, Task.StopReason reason) {
        // 先把暂停传给正在控制身体的子任务；永久结束时 cleanup 再完成其 GUI 边界收尾。
        if (activeChild != null) activeChild.stop(player, reason);
        if (flow != null) flow.stop(reason);
        super.stop(companion, reason);
    }

    @Override protected void cleanup() {
        if (activeChild != null) {
            activeChild.stop(player, Task.StopReason.REPLACED); activeChild.result(TaskState.CANCELLED);
            activeChild = null; activeRecord = null;
        }
        if (flow != null) flow.cleanup();
        else if (openRequested && player.containerMenu instanceof EnchantmentMenu menu
                && menu.getCarried().isEmpty() && menu.getSlot(0).getItem().isEmpty() && menu.getSlot(1).getItem().isEmpty()) {
            // 尚未认领工作槽就结束时，只能关闭自己打开且仍为空的附魔界面；有人放入物品后原样保留。
            try {
                var context = ClientRuntime.requireContext(player);
                if (context.permitsNativeActions()) context.menus().closeForTaskBoundary(context, 40, "enchantment opening ended");
            } catch (RuntimeException ignored) { }
        }
        super.cleanup();
    }

    @Override protected Map<String, Object> resultData() {
        var data = new LinkedHashMap<String, Object>();
        data.put("item_id", r.itemId.toString()); data.put("requested_count", 1); data.put("offer_tier", r.offerTier);
        data.put("max_levels_spent", r.maxLevelsSpent); data.put("max_lapis", r.maxLapis);
        data.put("mechanical_retry_allowed", !r.nativeConsumptionReserved()); data.put("outcome_uncertain", false);
        if (issue != null) data.put("issue_code", issue);
        if (flow != null) data.putAll(flow.data());
        return data;
    }

    @Override public Map<String, Object> progress() {
        // 状态查询只组装已观察数据，不推进子任务或菜单；报价、所选档位与返还状态在进行中即可由总任务转发。
        var data = new LinkedHashMap<String, Object>(super.progress());
        data.put("phase", phase.name().toLowerCase(java.util.Locale.ROOT));
        if (flow != null) data.putAll(flow.data());
        return data;
    }
    @Override protected String successMessage() { return "enchanted one " + r.itemId + ", verified its return and costs, and closed the native menu"; }
    @Override protected String timeoutMessage() { return "enchantment timed out; inspect the recorded quote, consumption boundary and cleanup status before continuing"; }
    @Override protected String cancelledMessage() { return "enchantment interrupted; any submitted consumption is not repeated, and cleanup is reported only as observed"; }
}
