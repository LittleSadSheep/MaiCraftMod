// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.stonecutter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.base.BlockMenuFlow;
import org.maiwithu.maicraft.core.task.container.ContainerTransferCompanionTask;
import org.maiwithu.maicraft.core.task.container.ContainerTransferTaskRecord;
import org.maiwithu.maicraft.core.task.menu.VisibleMenuSession;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** 在已由任务打开且输入格为空的切石机界面中完成装料、选配方、整批切制与取回对账，最后关闭；不接管后来换出的菜单。 */
final class StonecutterMenuFlow implements BlockMenuFlow {
    private enum Phase { LOAD_INPUT, WAIT_RECIPES, SELECT, WAIT_BUTTON, CRAFT, WAIT_TAKE, RETURN_INPUT, VERIFY, CLOSE, COMPLETE }
    private static final int INPUT_SLOT = 0, RESULT_SLOT = 1;
    private final LocalPlayer player;
    private final StonecuttingTaskRecord record;
    private final StonecutterMenu menu;
    private final StonecuttingStock stock;
    private final BooleanSupplier beforeFirstCraft;
    private final VisibleMenuSession session = new VisibleMenuSession();
    private Phase phase = Phase.LOAD_INPUT;
    private ContainerTransferCompanionTask child;
    private ContainerTransferTaskRecord childRecord;
    private MenuReceipt selectionReceipt, takeReceipt;
    private int recipeIndex = -1, craftsDone, craftedCount = -1;
    private String selectedOutput;
    private long waitRecipesUntil;
    private boolean childStarted, preserveMenu, returned, closeStarted, closed, successful, takeAttempted;
    private int serial;
    private String failure, cleanupStatus = "not_started";
    private FailureType failureType = FailureType.UNKNOWN;

    StonecutterMenuFlow(LocalPlayer player, StonecuttingTaskRecord record, StonecutterMenu menu,
                        StonecuttingStock stock, BooleanSupplier beforeFirstCraft) {
        this.player = player;
        this.record = record;
        this.menu = menu;
        this.stock = stock;
        this.beforeFirstCraft = beforeFirstCraft;
    }

    public TaskState tick(LocalPlayerContext context) {
        try { return advance(context); }
        catch (RuntimeException error) {
            abort(error.getMessage() == null ? "stonecutter_internal_error" : error.getMessage(), FailureType.UNKNOWN);
            return phase == Phase.COMPLETE ? TaskState.FAILED : TaskState.RUNNING;
        }
    }

    private TaskState advance(LocalPlayerContext context) {
        if (phase == Phase.COMPLETE) return successful ? TaskState.SUCCESS : TaskState.FAILED;
        if (!context.permitsNativeActions()) {
            preserveMenu = true;
            return abandon("stonecutter_control_handed_over", FailureType.INTERRUPTED);
        }
        if (child != null) return tickChild();
        if (phase == Phase.CLOSE) {
            // 只有切制与归位完成后的原生关闭回执确认，才把整个切石闭环算为成功。
            if (!closeStarted && (player.containerMenu != menu || !stock.workEmpty(menu)))
                return abandon("stonecutter_menu_changed_before_close", FailureType.TARGET_LOST);
            closeStarted = true;
            if (!session.close(context)) return TaskState.RUNNING;
            closed = true;
            cleanupStatus = returned ? "confirmed_returned_and_closed" : "closed_return_not_verified";
            successful = failure == null && returned;
            phase = Phase.COMPLETE;
            return successful ? TaskState.SUCCESS : TaskState.FAILED;
        }
        if (player.containerMenu != menu) return abandon("stonecutter_menu_changed", FailureType.TARGET_LOST);
        if (phase == Phase.WAIT_BUTTON) {
            selectionReceipt = context.menus().poll(context, selectionReceipt);
            if (!selectionReceipt.terminal()) return TaskState.RUNNING;
            if (selectionReceipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED)
                return abandon("stonecutting_recipe_selection_uncertain: " + selectionReceipt.detail(), FailureType.UNKNOWN);
            craftsDone = 0;
            phase = Phase.CRAFT;
            return TaskState.RUNNING;
        }
        if (phase == Phase.WAIT_TAKE) {
            takeReceipt = context.menus().poll(context, takeReceipt);
            if (!takeReceipt.terminal()) return TaskState.RUNNING;
            // 原版预测与服务器同步的时序不可依赖，回执状态不作为裁决；真实产出由 VERIFY 的背包全量对账裁决。
            craftsDone = record.count;
            phase = Phase.RETURN_INPUT;
            return TaskState.RUNNING;
        }
        if (!session.ready(context)) return TaskState.RUNNING;
        return switch (phase) {
            case LOAD_INPUT -> {
                // 打开界面与首次搬运之间也可能有外部输入；还没放入自己的东西时，任何已有内容都不能认领。
                if (!menu.getSlot(INPUT_SLOT).getItem().isEmpty() || !menu.getCarried().isEmpty())
                    yield abandon("stonecutter_input_slot_occupied_before_loading", FailureType.TARGET_LOST);
                yield startTransfer(stock.loadMoves(menu));
            }
            case WAIT_RECIPES -> {
                var recipes = menu.getRecipes();
                if (recipes.isEmpty()) {
                    if (waitRecipesUntil == 0) waitRecipesUntil = player.level().getGameTime() + 40;
                    if (player.level().getGameTime() >= waitRecipesUntil)
                        yield abandon("stonecutting_input_has_no_recipes", FailureType.TARGET_LOST);
                    yield TaskState.RUNNING;
                }
                recipeIndex = recipeIndexOf(recipes);
                if (recipeIndex < 0) yield abandon("stonecutting_output_item_has_no_matching_recipe", FailureType.TARGET_LOST);
                selectedOutput = BuiltInRegistries.ITEM.getKey(
                        recipes.get(recipeIndex).value().getResultItem(player.level().registryAccess()).getItem()).toString();
                phase = Phase.SELECT;
                yield TaskState.RUNNING;
            }
            case SELECT -> {
                // 第一次取件就是真实消耗；持久屏障确认落盘前不按配方按钮，之后也不能隐式再来一次。
                if (!record.submissionReserved() && !beforeFirstCraft.getAsBoolean()) yield TaskState.RUNNING;
                if (!stock.inputLoaded(menu)) throw new IllegalStateException("stonecutter_input_changed_before_selection");
                selectionReceipt = context.menus().pressButton(context, recipeIndex, MenuConfirmation.stateChanged(), 100);
                phase = Phase.WAIT_BUTTON;
                yield TaskState.RUNNING;
            }
            case CRAFT -> {
                // 原版一次快速移动会把输入格整叠依次切完；一次点击即整批切制。
                // 确认取整批完成这一稳定后置条件，并留足服务端往返与稳定窗口；真实产出仍由 VERIFY 全量对账裁决。
                takeAttempted = true;
                int expected = stock.outputCount() + record.count;
                takeReceipt = context.menus().click(context, RESULT_SLOT, 0, ClickType.QUICK_MOVE,
                        (ctx, receipt) -> stock.outputCount() >= expected
                                ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING,
                        200);
                phase = Phase.WAIT_TAKE;
                yield TaskState.RUNNING;
            }
            case RETURN_INPUT -> {
                if (menu.getSlot(INPUT_SLOT).getItem().isEmpty()) { phase = Phase.VERIFY; yield TaskState.RUNNING; }
                yield startTransfer(List.of(stock.returnInputMove()));
            }
            case VERIFY -> verifyReturn();
            default -> TaskState.RUNNING;
        };
    }

    private int recipeIndexOf(List<RecipeHolder<StonecutterRecipe>> recipes) {
        for (int i = 0; i < recipes.size(); i++) {
            var result = recipes.get(i).value().getResultItem(player.level().registryAccess());
            if (!result.isEmpty() && BuiltInRegistries.ITEM.getKey(result.getItem()).equals(record.output)) return i;
        }
        return -1;
    }

    private TaskState startTransfer(List<ContainerTransferTaskRecord.Move> moves) {
        // 搬运子任务保留此菜单继续切制或收尾；父任务逐刻检查它的截止时间并最终调用 result 释放所有操作。
        childRecord = new ContainerTransferTaskRecord(record.getToolCallId() + "-stonecutter-transfer-" + (++serial),
                player.level().getGameTime() + 400, menu.containerId, moves, false);
        child = new ContainerTransferCompanionTask(player, childRecord);
        childStarted = false;
        cleanupStatus = "owned_items_in_transfer";
        record.extendDeadlineTo(childRecord.getDeadlineGameTime());
        return TaskState.RUNNING;
    }

    private TaskState tickChild() {
        if (!childStarted) { child.start(player); childStarted = true; }
        TaskState state = player.level().getGameTime() >= childRecord.getDeadlineGameTime()
                ? TaskState.TIMEOUT : childRecord.getState().isTerminal() ? childRecord.getState() : child.tick(player);
        record.extendDeadlineTo(childRecord.getDeadlineGameTime());
        if (!state.isTerminal()) return TaskState.RUNNING;
        if (state != TaskState.SUCCESS) child.stop(player, Task.StopReason.REPLACED);
        child.result(state);
        child = null; childRecord = null; childStarted = false;
        if (state != TaskState.SUCCESS) {
            preserveMenu = true;
            cleanupStatus = "transfer_boundary_cleanup_not_confirmed";
            return abandon("stonecutter_transfer_failed", FailureType.UNKNOWN);
        }
        phase = switch (phase) {
            case LOAD_INPUT -> Phase.WAIT_RECIPES;
            case RETURN_INPUT -> Phase.VERIFY;
            default -> throw new IllegalStateException("stonecutter_transfer_phase_changed");
        };
        return TaskState.RUNNING;
    }

    private TaskState verifyReturn() {
        if (!stock.workEmpty(menu)) return abandon("stonecutter_work_slots_changed_after_return", FailureType.TARGET_LOST);
        craftedCount = stock.outputDelta();
        returned = stock.verifiedAfterCrafts(menu);
        if (!returned && failure == null) { failure = "stonecutter_inventory_change_not_verified"; failureType = FailureType.UNKNOWN; }
        cleanupStatus = returned ? "items_return_verified" : "items_return_not_verified";
        phase = Phase.CLOSE;
        return TaskState.RUNNING;
    }

    public void abort(String code, FailureType type) {
        if (failure == null) { failure = code; failureType = type; }
        if (preserveMenu || phase == Phase.CLOSE || phase == Phase.COMPLETE || child != null || takeAttempted) {
            // 取件点击已经发出时结果以真实背包为准但不自动补做；保留现场给人工核验，普通重试已被禁止。
            preserveMenu = true;
            phase = Phase.COMPLETE;
            if (takeAttempted) cleanupStatus = "outcome_uncertain_menu_preserved";
        } else if (!menu.getCarried().isEmpty()
                || menu.getSlot(INPUT_SLOT).getItem().getCount() > remainingInputToCraft()) {
            preserveMenu = true;
            cleanupStatus = "foreign_contents_preserved";
            phase = Phase.COMPLETE;
        } else if (phase == Phase.LOAD_INPUT || phase == Phase.WAIT_RECIPES || phase == Phase.SELECT
                || phase == Phase.WAIT_BUTTON) {
            phase = Phase.RETURN_INPUT;
        } else phase = Phase.COMPLETE;
    }

    private int remainingInputToCraft() { return record.count - craftsDone; }

    private TaskState abandon(String code, FailureType type) {
        if (failure == null) { failure = code; failureType = type; }
        preserveMenu = true;
        phase = Phase.COMPLETE;
        return TaskState.FAILED;
    }

    public void stop(Task.StopReason reason) {
        if (child != null) child.stop(player, reason);
    }

    public void cleanup() {
        if (child != null) {
            child.stop(player, Task.StopReason.REPLACED);
            child.result(TaskState.CANCELLED);
            child = null; childRecord = null; preserveMenu = true;
            cleanupStatus = "transfer_boundary_cleanup_not_confirmed";
        }
        if (closed || preserveMenu || player.containerMenu != menu) return;
        if (takeAttempted) { cleanupStatus = "outcome_uncertain_menu_preserved"; return; }
        // 取消时不再推进新搬运；仅在仍持有控制权且工作格属于本任务时请原版归还并关闭。
        if (!menu.getCarried().isEmpty() || menu.getSlot(INPUT_SLOT).getItem().getCount() > remainingInputToCraft()) {
            cleanupStatus = "foreign_contents_preserved";
            return;
        }
        try {
            var context = ClientRuntime.requireContext(player);
            if (!context.permitsNativeActions()) { cleanupStatus = "manual_handoff"; return; }
            context.menus().closeForTaskBoundary(context, 40, "stonecutting task ended");
            cleanupStatus = "native_return_and_close_requested";
        } catch (RuntimeException ignored) { cleanupStatus = "cleanup_not_confirmed"; }
    }

    public String failure() { return failure == null ? "stonecutting_failed" : failure; }
    public boolean hasFailure() { return failure != null; }
    public FailureType failureType() { return failureType; }

    public Map<String, Object> data() {
        var data = new LinkedHashMap<String, Object>();
        data.put("phase", phase.name().toLowerCase(Locale.ROOT));
        data.put("requested_count", record.count);
        if (craftedCount >= 0) data.put("crafted_count", craftedCount);
        data.put("native_consumption_reserved", record.submissionReserved());
        data.put("mechanical_retry_allowed", craftsDone == 0 && !record.submissionReserved());
        data.put("outcome_uncertain", takeAttempted && !successful);
        data.put("item_return_verified", returned);
        data.put("gui_closed", closed);
        data.put("cleanup_status", cleanupStatus);
        if (selectedOutput != null) data.put("selected_recipe_output", selectedOutput);
        if (failure != null) data.put("issue_code", failure);
        return data;
    }
}
