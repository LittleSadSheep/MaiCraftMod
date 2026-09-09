// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.integration.machine.MachineMenu;
import org.maiwithu.maicraft.core.integration.machine.MachineSurvey;
import org.maiwithu.maicraft.core.inventory.StockEvidence;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.UnequipCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.UnequipTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 补足机器里指定物品的数量，例如给 AE2 驱动器装存储元件。先打开并观察真实菜单，分次放入缺少的数量，再关好菜单。
 * 已经够数就不再添加；当前只向空槽放入，不补齐已有但未满的一叠，也不取走原有物品。
 */
public final class MachineContentsTask extends AbstractCompanionTask<MachineContentsTaskRecord> {
    private enum Phase { HAND, OPEN, OBSERVE, DEPOSIT, CLOSE, FAILURE_CLEANUP, DONE }
    private Phase phase;
    private Level world;
    private Task child;
    private TaskRecord childRecord;
    private AbstractContainerMenu ownedMenu;
    private List<Slot> entries;
    private MenuReceipt cleanupReceipt;
    private int installed, inserted, beforeCount, transferCount, selectedEntry, missingCount, cleanupTicks, syncTicks;
    private boolean closed, verified, uncertain, childClosing;
    private String failureCode = "", failureMessage = "";

    public MachineContentsTask(LocalPlayer player, MachineContentsTaskRecord record) { super(player, record); }
    @Override protected void onStart() {
        world = player.level();
        if (!world.isLoaded(r.target) || !BuiltInRegistries.ITEM.containsKey(r.itemId)) {
            fail("Machine contents target or requested item is unavailable.", FailureType.TARGET_LOST); return;
        }
        var context = ClientRuntime.requireContext(player);
        if (player.containerMenu != player.inventoryMenu || context.minecraft().screen != null) {
            fail("An unrelated menu is open before machine initialization.", FailureType.UNSUPPORTED); return;
        }
        if (player.getMainHandItem().isEmpty()) open();
        else {
            phase = Phase.HAND;
            childRecord = new UnequipTaskRecord(id(), deadline(200), List.of(EquipmentSlot.MAINHAND), "mainhand");
            child = new UnequipCompanionTask(player, (UnequipTaskRecord) childRecord);
        }
    }
    @Override protected TaskState onTick() {
        if (player.level() != world) return failure("machine_contents_world_changed", "The machine's client world session changed.");
        if (phase == Phase.FAILURE_CLEANUP) return cleanupFailure();
        if (child != null) return tickChild();
        if (world.getGameTime() > r.getDeadlineGameTime()) return failure("machine_contents_timeout", "Machine initialization timed out.");
        if (phase == Phase.DONE) return TaskState.SUCCESS;
        if (!sameMenu()) return failure("machine_contents_menu_changed", "The observed menu or its slot identities changed.");
        // 先等服务器同步完整库存；打开菜单时客户端的空槽外观不能作为开始装入的依据。
        if (!StockEvidence.isContainerSynchronized(player, ownedMenu)) {
            if (++syncTicks > 100) return failure("machine_contents_unsynchronized", "The machine never supplied a complete server inventory snapshot.");
            return TaskState.RUNNING;
        }
        JsonObject observation = MachineMenu.inspect(player);
        if (!observation.get("transfer_receipt_available").getAsBoolean()) {
            return failure("machine_contents_unobservable", "The open machine has no physical-inventory transfer receipt.");
        }
        installed = installedCount(observation, r.itemId.toString());
        if (installed >= r.count) { close(); return TaskState.RUNNING; }
        ItemStack source = ItemStack.EMPTY;
        for (ItemStack stack : player.getInventory().items) if (matches(stack)) { source = stack; break; }
        // 先正常关菜单，再报告还缺多少。父级机器装配任务收到这个缺料结果后才去补货。
        if (source.isEmpty()) {
            missingCount = r.count - installed;
            failureCode = "machine_contents_material_shortage";
            failureMessage = "The observed machine needs " + missingCount + " more " + r.itemId + ".";
            close(); return TaskState.RUNNING;
        }
        selectedEntry = -1;
        for (var element : observation.getAsJsonArray("menu_entries")) {
            JsonObject row = element.getAsJsonObject();
            int index = row.get("entry_index").getAsInt();
            Slot slot = entries.get(index);
            if (eligible(row) && slot.mayPlace(source) && slot.getMaxStackSize(source) > 0) { selectedEntry = index; break; }
        }
        if (selectedEntry < 0) return failure("machine_contents_no_empty_slot", "No observed empty physical slot accepts the supplied item; existing contents were preserved.");
        beforeCount = installed;
        // 一次不超过缺额、来源这一叠、目标槽容量和六十四件；放完重新观察，再决定是否继续下一槽。
        transferCount = Math.min(Math.min(r.count - installed, 64), Math.min(source.getCount(), entries.get(selectedEntry).getMaxStackSize(source)));
        phase = Phase.DEPOSIT;
        start(MachineMenu.transferTask(id(), deadline(1200), observation.get("menu_receipt_id").getAsString(),
                "deposit", selectedEntry, r.itemId, transferCount));
        return TaskState.RUNNING;
    }

    private TaskState tickChild() {
        TaskState state = world.getGameTime() >= childRecord.getDeadlineGameTime() ? TaskState.TIMEOUT : runChild(child);
        r.extendDeadlineTo(childRecord.getDeadlineGameTime());
        if (state == null) return TaskState.RUNNING;
        if (state == TaskState.TIMEOUT) child.stop(player, StopReason.REPLACED);
        TaskResult result = child.result(state); child = null; childRecord = null;
        if (result == null || !result.success()) {
            var data = result == null || result.data() == null ? Map.<String, Object>of() : result.data();
            uncertain |= Boolean.TRUE.equals(data.get("outcome_uncertain"));
            childClosing = Boolean.TRUE.equals(data.get("boundary_close_requested"));
            return failure("machine_contents_native_stage_failed", result == null ? "Native contents stage returned no receipt." : result.message());
        }
        if (phase == Phase.HAND) {
            if (!player.getMainHandItem().isEmpty()) return failure("machine_contents_empty_hand_unavailable", "No inventory capacity is available for ordinary machine opening.");
            open();
        } else if (phase == Phase.OPEN) {
            ownedMenu = player.containerMenu; entries = List.copyOf(ownedMenu.slots);
            if (!targetMenuMatches()) return failure("machine_contents_target_menu_mismatch", "The opened menu is not the requested AE2 drive.");
            phase = Phase.OBSERVE;
        } else if (phase == Phase.DEPOSIT) {
            if (!sameMenu()) return failure("machine_contents_menu_changed", "The physical menu changed after transfer.");
            JsonObject observation = MachineMenu.inspect(player);
            installed = installedCount(observation, r.itemId.toString());
            ItemStack destination = entries.get(selectedEntry).getItem();
            // 不仅看背包少了多少，还要求机器总数和本次目标空槽都增加了准确数量。
            if (installed != beforeCount + transferCount || !matches(destination) || destination.getCount() != transferCount
                    || !Boolean.TRUE.equals(result.data().get("transfer_verified"))
                    || !(result.data().get("actual_player_delta") instanceof Number delta) || delta.intValue() != -transferCount) {
                uncertain = true;
                return failure("machine_contents_delta_unconfirmed", "The machine credit and exact player inventory debit did not agree.");
            }
            inserted += transferCount; phase = Phase.OBSERVE;
        } else if (phase == Phase.CLOSE) {
            closed = true; ownedMenu = null;
            if (!failureCode.isEmpty()) { fail(failureMessage, FailureType.NO_MATERIAL); return TaskState.FAILED; }
            verified = true; phase = Phase.DONE; return TaskState.SUCCESS;
        }
        return TaskState.RUNNING;
    }

    private void open() {
        phase = Phase.OPEN;
        start(MachineMenu.openTask(id(), deadline(3600), new MachineMenu.OpenRequest(world.dimension().location().toString(),
                r.target, 0, MachineSurvey.fingerprint(player, r.target, 0), r.target)));
    }
    private void close() { phase = Phase.CLOSE; start(MachineMenu.closeTask(id(), deadline(100))); }
    private void start(TaskRecord record) { childRecord = record; child = TaskFactory.create(player, record); }
    private long deadline(long duration) { return Math.max(r.getDeadlineGameTime(), world.getGameTime() + duration); }
    private String id() { return r.getToolCallId() + "-contents-" + phase + "-" + inserted; }
    private boolean matches(ItemStack stack) { return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(r.itemId); }
    private boolean sameMenu() {
        if (ownedMenu == null || player.containerMenu != ownedMenu || entries.size() != ownedMenu.slots.size()) return false;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i) != ownedMenu.getSlot(i)) return false;
        return true;
    }
    // AE2 驱动器另检查菜单确属同一台驱动器；其他机器依靠打开任务和后续菜单身份检查。
    private boolean targetMenuMatches() {
        if (!BuiltInRegistries.BLOCK.getKey(world.getBlockState(r.target).getBlock()).toString().equals("ae2:drive")) return true;
        try {
            return ownedMenu.getClass().getName().equals("appeng.menu.implementations.DriveMenu")
                    && ownedMenu.getClass().getMethod("getBlockEntity").invoke(ownedMenu) == world.getBlockEntity(r.target);
        } catch (ReflectiveOperationException unavailable) { return false; }
    }
    static boolean eligible(JsonObject entry) {
        return entry.get("side").getAsString().equals("machine") && entry.get("transfer_supported").getAsBoolean()
                && entry.get("active").getAsBoolean() && entry.getAsJsonObject("stack").get("empty").getAsBoolean();
    }
    static int installedCount(JsonObject observation, String itemId) {
        int count = 0;
        for (var element : observation.getAsJsonArray("menu_entries")) {
            JsonObject entry = element.getAsJsonObject(), stack = entry.getAsJsonObject("stack");
            if (entry.get("side").getAsString().equals("machine") && entry.get("transfer_supported").getAsBoolean()
                    && !stack.get("empty").getAsBoolean() && stack.get("item_id").getAsString().equals(itemId)) {
                count += stack.get("count").getAsInt();
            }
        }
        return count;
    }
    private TaskState failure(String code, String message) {
        failureCode = code; failureMessage = message;
        if (ownedMenu != null && player.containerMenu == ownedMenu) { phase = Phase.FAILURE_CLEANUP; return TaskState.RUNNING; }
        fail(message, FailureType.UNKNOWN); return TaskState.FAILED;
    }
    // 失败后由拥有菜单的一层安排关闭；子存取任务已经在收尾时等待它，避免两个关闭流程争着处理鼠标上的物品。
    private TaskState cleanupFailure() {
        var context = ClientRuntime.requireContext(player);
        if (cleanupReceipt != null) {
            cleanupReceipt = context.menus().poll(context, cleanupReceipt);
            if (cleanupReceipt.terminal()) {
                closed = cleanupReceipt.status() == MenuReceipt.Status.CONFIRMED_APPLIED
                        && player.containerMenu == player.inventoryMenu && context.minecraft().screen == null;
                uncertain |= !closed;
                fail(failureMessage, FailureType.UNKNOWN); return TaskState.FAILED;
            }
        }
        if (cleanupReceipt == null && player.containerMenu != ownedMenu) {
            closed = player.containerMenu == player.inventoryMenu && context.minecraft().screen == null;
            fail(failureMessage, FailureType.UNKNOWN); return TaskState.FAILED;
        }
        if (!childClosing && cleanupReceipt == null) {
            cleanupReceipt = context.menus().closeForTaskBoundary(context, 80, "machine contents failure owns cursor return");
        }
        if (++cleanupTicks > 100) {
            uncertain = true; fail(failureMessage, FailureType.UNKNOWN); return TaskState.FAILED;
        }
        return TaskState.RUNNING;
    }
    @Override protected void cleanup() {
        if (child != null) { child.stop(player, StopReason.REPLACED); child.result(TaskState.CANCELLED); child = null; }
        if (!closed && ownedMenu != null && player.containerMenu == ownedMenu && cleanupReceipt == null && !childClosing) {
            try { var context = ClientRuntime.requireContext(player); context.menus().closeForTaskBoundary(context, 80, "machine contents task ended"); }
            catch (RuntimeException revoked) { /* 世界或角色已更换时，旧菜单由运行层收尾。 */ }
        }
        super.cleanup();
    }
    @Override public void stop(LocalPlayer companion, StopReason why) { if (child != null) child.stop(companion, why); super.stop(companion, why); }
    @Override public boolean mustSettleBeforeSatisfiedCancellation() { return child != null || ownedMenu != null && !closed; }
    @Override protected String successMessage() { return "Machine contains at least " + r.count + " " + r.itemId + "; its menu is closed."; }
    @Override protected Map<String, Object> resultData() {
        return Map.of("contents_verified", verified, "installed_count", installed, "inserted_count", inserted,
                "missing_count", missingCount, "failure_code", failureCode, "owned_menu_closed", closed,
                "effects_settled", closed && !uncertain, "outcome_uncertain", uncertain, "item_id", r.itemId.toString());
    }
}
