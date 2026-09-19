// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.enchant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.container.ContainerTransferCompanionTask;
import org.maiwithu.maicraft.core.task.container.ContainerTransferTaskRecord;
import org.maiwithu.maicraft.core.task.container.ContainerTransferTaskRecord.Move;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 在已由任务打开且为空的附魔台界面中完成装料、一次附魔、核验取回和关闭；不接管后来换出的菜单。 */
final class EnchantMenuFlow {
    private enum Phase { LOAD_INPUT, LOAD_LAPIS, QUOTE, SUBMIT, WAIT_BUTTON, RETURN_ITEM, RETURN_LAPIS, VERIFY, CLOSE, COMPLETE }
    private final LocalPlayer player;
    private final EnchantTaskRecord record;
    private final EnchantmentMenu menu;
    private final EnchantInventory inventory;
    private final EnchantTransaction transaction;
    private final VisibleMenuSession session = new VisibleMenuSession();
    private Phase phase = Phase.LOAD_INPUT;
    private ContainerTransferCompanionTask child;
    private ContainerTransferTaskRecord childRecord;
    private boolean childStarted, preserveMenu, returned, closeStarted, closed, successful;
    private int serial;
    private String failure, cleanupStatus = "not_started";
    private FailureType failureType = FailureType.UNKNOWN;

    EnchantMenuFlow(LocalPlayer player, EnchantTaskRecord record, EnchantmentMenu menu,
                    EnchantInventory inventory, BooleanSupplier beforeSubmit) {
        this.player = player; this.record = record; this.menu = menu; this.inventory = inventory;
        transaction = new EnchantTransaction(player, record, beforeSubmit);
    }

    TaskState tick(LocalPlayerContext context) {
        try { return advance(context); }
        catch (RuntimeException error) {
            abort(error.getMessage() == null ? "enchantment_internal_error" : error.getMessage(), FailureType.UNKNOWN);
            return phase == Phase.COMPLETE ? TaskState.FAILED : TaskState.RUNNING;
        }
    }

    private TaskState advance(LocalPlayerContext context) {
        if (phase == Phase.COMPLETE) return successful ? TaskState.SUCCESS : TaskState.FAILED;
        if (!context.permitsNativeActions()) {
            preserveMenu = true; return abandon("enchantment_control_handed_over", FailureType.INTERRUPTED);
        }
        if (child != null) return tickChild();
        if (phase == Phase.CLOSE) {
            // 只有返还物品后的原生关闭回执确认，才将整个附魔闭环算为成功。
            if (!closeStarted && (player.containerMenu != menu || !workEmpty()))
                return abandon("enchantment_menu_changed_before_close", FailureType.TARGET_LOST);
            closeStarted = true;
            if (!session.close(context)) return TaskState.RUNNING;
            closed = true; cleanupStatus = returned ? "confirmed_returned_and_closed" : "closed_return_not_verified";
            successful = failure == null && returned; phase = Phase.COMPLETE;
            return successful ? TaskState.SUCCESS : TaskState.FAILED;
        }
        if (player.containerMenu != menu) return abandon("enchantment_menu_changed", FailureType.TARGET_LOST);
        if (phase == Phase.WAIT_BUTTON) {
            if (!transaction.poll(context)) return TaskState.RUNNING;
            inventory.freezeResult(transaction.confirmedResult()); phase = Phase.RETURN_ITEM; return TaskState.RUNNING;
        }
        if (!session.ready(context)) return TaskState.RUNNING;
        return switch (phase) {
            case LOAD_INPUT -> {
                // 打开界面与首次搬运之间也可能有外部输入；还没放入自己的东西时，任何已有内容都不能认领。
                if (!workEmpty()) yield abandon("enchantment_work_slots_changed_before_loading", FailureType.TARGET_LOST);
                yield startTransfer(inventory.loadInput(menu));
            }
            case LOAD_LAPIS -> {
                if (!net.minecraft.world.item.ItemStack.matches(inventory.input, menu.getSlot(0).getItem())
                        || !menu.getSlot(1).getItem().isEmpty() || !menu.getCarried().isEmpty())
                    yield abandon("enchantment_work_slots_changed_before_lapis", FailureType.TARGET_LOST);
                var moves = inventory.loadLapis(menu);
                if (moves.isEmpty()) { phase = Phase.QUOTE; yield TaskState.RUNNING; }
                yield startTransfer(moves);
            }
            case QUOTE -> {
                if (!inventory.loaded(menu)) throw new IllegalStateException("enchantment_input_changed_before_quote");
                if (transaction.prepare(context, menu)) phase = Phase.SUBMIT;
                yield TaskState.RUNNING;
            }
            case SUBMIT -> {
                if (!inventory.loaded(menu)) throw new IllegalStateException("enchantment_input_changed_before_submission");
                inventory.requireOutputSpace();
                if (transaction.submit(context, menu)) phase = Phase.WAIT_BUTTON;
                yield TaskState.RUNNING;
            }
            case RETURN_ITEM, RETURN_LAPIS -> returnContents();
            case VERIFY -> verifyReturn();
            default -> TaskState.RUNNING;
        };
    }

    private TaskState startTransfer(List<Move> moves) {
        // 搬运子任务保留此菜单继续报价或收尾；父任务逐刻检查它的截止时间并最终调用 result 释放所有操作。
        childRecord = new ContainerTransferTaskRecord(record.getToolCallId() + "-enchant-transfer-" + (++serial),
                player.level().getGameTime() + 400, menu.containerId, moves, false);
        child = new ContainerTransferCompanionTask(player, childRecord); childStarted = false;
        cleanupStatus = "owned_items_in_transfer";
        record.extendDeadlineTo(childRecord.getDeadlineGameTime()); return TaskState.RUNNING;
    }

    private TaskState tickChild() {
        if (!childStarted) { child.start(player); childStarted = true; }
        TaskState state = player.level().getGameTime() >= childRecord.getDeadlineGameTime()
                ? TaskState.TIMEOUT : childRecord.getState().isTerminal() ? childRecord.getState() : child.tick(player);
        record.extendDeadlineTo(childRecord.getDeadlineGameTime());
        if (!state.isTerminal()) return TaskState.RUNNING;
        if (state != TaskState.SUCCESS) child.stop(player, Task.StopReason.REPLACED);
        child.result(state); child = null; childRecord = null; childStarted = false;
        if (state != TaskState.SUCCESS) {
            // 子搬运已经负责自己的原生关闭或陌生光标保护；父任务不得再关闭一次覆盖该保护。
            preserveMenu = true; cleanupStatus = "transfer_boundary_cleanup_not_confirmed";
            // 子任务日志保留底层诊断，对外只给语义原因，避免把原生菜单槽号混进状态与附魔结果。
            return abandon("enchantment_transfer_failed", FailureType.UNKNOWN);
        }
        phase = switch (phase) {
            case LOAD_INPUT -> Phase.LOAD_LAPIS;
            case LOAD_LAPIS -> Phase.QUOTE;
            case RETURN_ITEM -> Phase.RETURN_LAPIS;
            case RETURN_LAPIS -> Phase.VERIFY;
            default -> throw new IllegalStateException("enchantment_transfer_phase_changed");
        };
        return TaskState.RUNNING;
    }

    private TaskState returnContents() {
        if (!inventory.ownedContents(menu, transaction.confirmed(), remainingLapis())) {
            preserveMenu = true; return abandon("enchantment_foreign_cursor_or_work_item", FailureType.TARGET_LOST);
        }
        int slot = phase == Phase.RETURN_ITEM ? 0 : 1;
        if (menu.getSlot(slot).getItem().isEmpty()) {
            phase = slot == 0 ? Phase.RETURN_LAPIS : Phase.VERIFY; return TaskState.RUNNING;
        }
        return startTransfer(List.of(inventory.returnMove(menu, slot, transaction.confirmed())));
    }

    private TaskState verifyReturn() {
        // 已消费时核对完整成品和实际成本；未消费的失败则只核对原物品及青金石已全数归还。
        if (!workEmpty()) return abandon("enchantment_work_slots_changed_after_return", FailureType.TARGET_LOST);
        // 消耗已在按钮回执完成时精确核验；之后合法获得经验不会推翻那次扣费证据，收尾只再核对自有物品。
        returned = transaction.confirmed() ? inventory.verifiedReturn(menu, transaction.lapisSpent())
                : inventory.restoredBeforeConsumption(menu);
        if (!returned && failure == null) { failure = "enchantment_inventory_return_not_verified"; failureType = FailureType.UNKNOWN; }
        cleanupStatus = returned ? "items_return_verified" : "items_return_not_verified";
        phase = Phase.CLOSE; return TaskState.RUNNING;
    }

    void abort(String code, FailureType type) {
        if (failure == null) { failure = code; failureType = type; }
        if (preserveMenu || phase == Phase.CLOSE || phase == Phase.COMPLETE || child != null
                || player.containerMenu != menu || transaction.attempted() && !transaction.confirmed()) {
            // 按钮发出后结果不明时不再碰可能仍在同步的输入，保留现场给人工核验，普通重试已被禁止。
            preserveMenu = true; phase = Phase.COMPLETE;
            if (transaction.attempted() && !transaction.confirmed()) cleanupStatus = "outcome_uncertain_menu_preserved";
        } else if (!inventory.ownedContents(menu, transaction.confirmed(), remainingLapis())) {
            preserveMenu = true; cleanupStatus = "foreign_contents_preserved"; phase = Phase.COMPLETE;
        } else if (phase == Phase.RETURN_ITEM || phase == Phase.RETURN_LAPIS || phase == Phase.VERIFY) {
            preserveMenu = true; cleanupStatus = "return_failed_menu_preserved"; phase = Phase.COMPLETE;
        } else phase = Phase.RETURN_ITEM;
    }

    private TaskState abandon(String code, FailureType type) {
        if (failure == null) { failure = code; failureType = type; }
        preserveMenu = true; phase = Phase.COMPLETE; return TaskState.FAILED;
    }

    void stop(Task.StopReason reason) {
        // 生存抢占只暂停当前搬运；取消最终还需 cleanup 调子任务 result，不能遗漏未完成的 GUI 回执。
        if (child != null) child.stop(player, reason);
    }

    void cleanup() {
        if (child != null) {
            child.stop(player, Task.StopReason.REPLACED); child.result(TaskState.CANCELLED);
            child = null; childRecord = null; preserveMenu = true;
            cleanupStatus = "transfer_boundary_cleanup_not_confirmed";
        }
        if (closed || preserveMenu || player.containerMenu != menu) return;
        if (transaction.attempted() && !transaction.confirmed()) {
            cleanupStatus = "outcome_uncertain_menu_preserved"; return;
        }
        // 取消时不再推进新搬运；仅在仍持有控制权且内容属于本任务时，请原版归还并关闭，回执未核验就如实报待确认。
        if (!inventory.ownedContents(menu, transaction.confirmed(), remainingLapis())) {
            cleanupStatus = "foreign_contents_preserved"; return;
        }
        try {
            var context = ClientRuntime.requireContext(player);
            if (!context.permitsNativeActions()) { cleanupStatus = "manual_handoff"; return; }
            context.menus().closeForTaskBoundary(context, 40, "single-item enchantment task ended");
            cleanupStatus = "native_return_and_close_requested";
        } catch (RuntimeException ignored) { cleanupStatus = "cleanup_not_confirmed"; }
    }

    String failure() { return failure == null ? "enchantment_failed" : failure; }
    boolean hasFailure() { return failure != null; }
    FailureType failureType() { return failureType; }
    private int remainingLapis() { return inventory.lapisNeeded - transaction.lapisSpent(); }
    private boolean workEmpty() {
        return menu.getCarried().isEmpty() && menu.getSlot(0).getItem().isEmpty() && menu.getSlot(1).getItem().isEmpty();
    }

    Map<String, Object> data() {
        var data = new LinkedHashMap<String, Object>(transaction.data());
        data.put("phase", phase.name().toLowerCase(java.util.Locale.ROOT));
        data.put("outcome_uncertain", transaction.attempted() && !successful);
        data.put("item_return_verified", returned); data.put("gui_closed", closed); data.put("cleanup_status", cleanupStatus);
        // 只有真实按钮回执已确认、成品也已冻结时才展示全部附魔；报价线索不能冒充最终随机结果。
        if (transaction.confirmed() && !inventory.resultItemId().isEmpty()) {
            data.put("result_item_id", inventory.resultItemId());
            data.put("result_enchantments", inventory.resultEnchantments());
        }
        if (failure != null) data.put("issue_code", failure);
        return data;
    }
}
