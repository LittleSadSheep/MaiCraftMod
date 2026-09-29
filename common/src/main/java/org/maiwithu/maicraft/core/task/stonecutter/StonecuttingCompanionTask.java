// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.stonecutter;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.StonecutterMenu;
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
import java.util.Locale;
import org.maiwithu.maicraft.core.integration.machine.MachineMenu;

/** 找到背包中的输入材料 → 不改地形走近已有切石机 → 原生开界面 → 选配方逐件切制 → 核验取回并关闭。 */
public final class StonecuttingCompanionTask extends AbstractCompanionTask<StonecuttingTaskRecord> {
    private enum Phase { APPROACH, OPEN, WAIT_MENU, CRAFT }
    private Phase phase = Phase.APPROACH;
    private final VisibleMenuSession travelMenu = new VisibleMenuSession();
    private StonecuttingStock stock;
    private StonecutterMenuFlow flow;
    private Task activeChild;
    private TaskRecord activeRecord;
    private boolean openRequested;
    private long waitMenuUntil;
    private String issue;

    public StonecuttingCompanionTask(LocalPlayer player, StonecuttingTaskRecord record) { super(player, record); }

    @Override protected void onStart() {
        // 已有人正在用菜单或拿着光标物品时拒绝接管；不会为了准备切石清空对方的工作槽。
        if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) {
            failIssue("stonecutter_existing_menu_or_cursor_in_use", FailureType.INTERRUPTED); return;
        }
        if (!stationPresent()) { failIssue("stonecutter_station_missing_or_unloaded", FailureType.TARGET_LOST); return; }
        try { stock = StonecuttingStock.prepare(player, r.input, r.output, r.count); }
        catch (IllegalArgumentException unavailable) {
            failIssue(unavailable.getMessage(), unavailable.getMessage().contains("space") ? FailureType.NO_SPACE : FailureType.NO_MATERIAL);
        }
    }

    @Override protected TaskState onTick() {
        var context = ClientRuntime.requireContext(player);
        if (flow != null) {
            // 切石机消失只通知一次；已经进入安全归还后仍需让同一搬运子任务确认，不能每刻重新打断它。
            if (!stationPresent() && !flow.hasFailure()) flow.abort("stonecutter_station_changed", FailureType.TARGET_LOST);
            TaskState state = flow.tick(context);
            if (state == TaskState.FAILED) failIssue(flow.failure(), flow.failureType());
            return state;
        }
        if (!context.permitsNativeActions()) return failIssue("stonecutter_control_handed_over", FailureType.INTERRUPTED);
        if (!stationPresent()) return failIssue("stonecutter_station_missing_or_unloaded", FailureType.TARGET_LOST);
        if (activeChild != null) return tickChild();
        return switch (phase) {
            case APPROACH -> {
                if (!travelMenu.worldReady(context)) yield TaskState.RUNNING;
                if (player.distanceToSqr(Vec3.atCenterOf(r.station)) <= 3.75 * 3.75) {
                    phase = Phase.OPEN; yield TaskState.RUNNING;
                }
                // 到切石机附近只允许地面通行；不挖、搭路或自动建造另一台切石机。
                yield startChild(new MoveToTaskRecord(r.getToolCallId() + "-stonecutter-approach", deadline(1200),
                        (double) r.station.getX(), (double) r.station.getY(), (double) r.station.getZ(), null,
                        false, false, TransportMode.GROUND, false, false, 2, 1));
            }
            case OPEN -> {
                if (!travelMenu.worldReady(context)) yield TaskState.RUNNING;
                openRequested = true;
                yield startChild(new InteractAtTaskRecord(r.getToolCallId() + "-stonecutter-open", deadline(200),
                        MouseButton.RIGHT, r.station, 0, null, null, Blocks.STONECUTTER));
            }
            case WAIT_MENU -> {
                if (player.containerMenu instanceof StonecutterMenu menu) {
                    if (!menu.getCarried().isEmpty() || !menu.getSlot(0).getItem().isEmpty())
                        yield failIssue("stonecutter_opened_menu_already_occupied", FailureType.INTERRUPTED);
                    if (!context.menus().ensureVisible(context)) yield TaskState.RUNNING;
                    // 绑定这一张已可见、输入格为空的原生切石菜单；后续换菜单都不能沿用旧操作。
                    MachineMenu.rememberNativeOpened(player, menu, r.station);
                    flow = new StonecutterMenuFlow(player, r, menu, stock, r::prepareSubmission);
                    phase = Phase.CRAFT; yield TaskState.RUNNING;
                }
                if (player.containerMenu != player.inventoryMenu)
                    yield failIssue("stonecutter_unexpected_menu_opened", FailureType.TARGET_LOST);
                if (player.level().getGameTime() >= waitMenuUntil)
                    yield failIssue("stonecutter_native_menu_did_not_open", FailureType.TIMED_OUT);
                yield TaskState.RUNNING;
            }
            case CRAFT -> throw new IllegalStateException("stonecutter_menu_flow_missing");
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
        if (state != TaskState.SUCCESS) return failIssue("stonecutter_" + phase.name().toLowerCase(Locale.ROOT)
                + "_failed: " + result.message(), state == TaskState.TIMEOUT ? FailureType.TIMED_OUT : FailureType.UNKNOWN);
        if (phase == Phase.APPROACH) phase = Phase.OPEN;
        else { phase = Phase.WAIT_MENU; waitMenuUntil = player.level().getGameTime() + 60; }
        return TaskState.RUNNING;
    }

    private boolean stationPresent() {
        return player.level().isLoaded(r.station) && player.level().getBlockState(r.station).is(Blocks.STONECUTTER);
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
        else if (openRequested && player.containerMenu instanceof StonecutterMenu menu
                && menu.getCarried().isEmpty() && menu.getSlot(0).getItem().isEmpty()) {
            // 尚未认领输入格就结束时，只能关闭自己打开且仍为空的切石界面；有人放入物品后原样保留。
            try {
                var context = ClientRuntime.requireContext(player);
                if (context.permitsNativeActions()) context.menus().closeForTaskBoundary(context, 40, "stonecutting opening ended");
            } catch (RuntimeException ignored) { }
        }
        super.cleanup();
    }

    @Override protected Map<String, Object> resultData() {
        var data = new LinkedHashMap<String, Object>();
        data.put("item_id", r.input.toString()); data.put("output_item_id", r.output.toString());
        data.put("requested_count", r.count);
        data.put("mechanical_retry_allowed", !r.submissionReserved()); data.put("outcome_uncertain", false);
        if (issue != null) data.put("issue_code", issue);
        if (flow != null) data.putAll(flow.data());
        return data;
    }

    @Override public Map<String, Object> progress() {
        // 状态查询只组装已观察数据，不推进子任务或菜单；已切制数量与归还状态在进行中即可由总任务转发。
        var data = new LinkedHashMap<String, Object>(super.progress());
        data.put("phase", phase.name().toLowerCase(Locale.ROOT));
        if (flow != null) data.putAll(flow.data());
        return data;
    }
    @Override protected String successMessage() {
        return "cut " + r.count + " " + r.input + " into " + r.output + ", verified returns and closed the native menu";
    }
    @Override protected String timeoutMessage() {
        return "stonecutting timed out; inspect the recorded crafts, consumption boundary and cleanup status before continuing";
    }
    @Override protected String cancelledMessage() {
        return "stonecutting interrupted; any submitted consumption is not repeated, and cleanup is reported only as observed";
    }
}
