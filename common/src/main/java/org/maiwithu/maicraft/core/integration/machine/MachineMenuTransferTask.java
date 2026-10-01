// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;
import java.util.Locale;
import com.google.gson.JsonObject;

/**
 * 按一次菜单观察完成精确存入或取出：拿起、放入、退回余量、核对背包差额。每次点击都等确认后再继续。
 * 同组件的多叠来源和多个背包空位由执行器分批处理，整项搬运仍只消费一个语义请求。
 */
public final class MachineMenuTransferTask extends AbstractCompanionTask<MachineMenuTransferTaskRecord> {
    private enum Phase { START, PICKUP, PLACE, RETURN, NEXT_BATCH, VERIFY }
    private Phase phase = Phase.START;
    private MachineMenu.Inspection inspection;
    private AbstractContainerMenu menu;
    private MenuReceipt receipt;
    private Phase pendingPhase;
    private int pendingAmount;
    private List<ItemStack> expectedInventory;
    private List<ItemStack> pendingInventory;
    private ItemStack kind = ItemStack.EMPTY;
    private int sourceEntry;
    private int destinationEntry;
    private int pickupAmount;
    private int pickupButton;
    private int placed;
    private int batchEnd;
    private int confirmedClicks;
    private int initialPlayerCount;
    private int actualPlayerDelta;
    private boolean effectsStarted;
    private boolean uncertain;
    private boolean verified;
    private boolean boundaryCloseRequested;
    private String failureCode;
    private String nativeStatus;
    private JsonObject menuReport;
    private String observationFailure;

    public MachineMenuTransferTask(LocalPlayer player, MachineMenuTransferTaskRecord record) { super(player, record); }

    @Override protected TaskState onTick() {
        if (phase == Phase.START) return begin();
        if (!sameSession()) return failure("machine_menu_session_changed",
                "The inspected machine menu session changed; no additional item action was sent.", FailureType.TARGET_LOST);
        if (receipt != null) {
            var context = ClientRuntime.requireContext(player);
            receipt = context.menus().poll(context, receipt);
            if (!receipt.terminal()) return TaskState.RUNNING;
            nativeStatus = receipt.status().name().toLowerCase(Locale.ROOT);
            if (receipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
                uncertain = receipt.status() != MenuReceipt.Status.CONFIRMED_NOT_APPLIED;
                return failure("machine_transfer_unconfirmed", "The exact native inventory transaction was not confirmed; no click was replayed.", FailureType.UNKNOWN);
            }
            confirmedClicks++; uncertain = false;
            expectedInventory = pendingInventory;
            receipt = null;
            if (pendingPhase == Phase.PICKUP) phase = Phase.PLACE;
            else if (pendingPhase == Phase.PLACE) {
                placed += pendingAmount;
                phase = placed < batchEnd ? Phase.PLACE : menu.getCarried().isEmpty() ? afterBatch() : Phase.RETURN;
            } else if (pendingPhase == Phase.RETURN) phase = afterBatch();
            r.extendDeadlineTo(player.level().getGameTime() + 60L * 20L);
        }
        // 第一次点击之前先按实时库存重新选来源、数量与容量；尚未拿起物品时不让普通同步打断整项搬运。
        if (!effectsStarted && prepareTransfer() == TaskState.FAILED) return TaskState.FAILED;
        if (!inventoryMatches(expectedInventory)) {
            uncertain |= effectsStarted;
            return failure("machine_inventory_changed", "The player's inventory changed outside the confirmed transfer.", FailureType.UNKNOWN);
        }
        // 上一叠已结清后，下一次拿取仍可按原剩余数量刷新；目标已满时停在空光标边界，保留已确认前缀。
        if (effectsStarted && phase == Phase.PICKUP && prepareTransfer() == TaskState.FAILED) return TaskState.FAILED;
        if (phase == Phase.VERIFY) return verify();
        if (NavigationSafetyContext.protectsUse(inspection.origin.position())) {
            return failure("machine_transfer_protected", "The selected machine is explicitly protected from changes.", FailureType.UNSUPPORTED);
        }
        var context = ClientRuntime.requireContext(player);
        if (!context.mutationAvailable()) return TaskState.RUNNING;
        if (!context.menus().ensureVisible(context)) return TaskState.RUNNING;
        return switch (phase) {
            case PICKUP -> pickup();
            case PLACE -> place();
            case RETURN -> returnRemainder();
            case VERIFY -> verify();
            case NEXT_BATCH -> prepareTransfer();
            case START -> begin();
        };
    }

    // 消费观察编号并重新核对现场；普通背包的三十六个槽必须全部且各出现一次，才开始选来源与去向。
    private TaskState begin() {
        try { inspection = MachineMenu.consume(r.receiptId, player); }
        catch (IllegalArgumentException stale) { return failure("machine_menu_receipt_invalid", stale.getMessage(), FailureType.TARGET_LOST); }
        menu = player.containerMenu;
        return prepareTransfer();
    }

    private TaskState prepareTransfer() {
        // 身体、机器及目标槽仍须相同；只刷新本次授权搬运的原生选槽，不重新消费编号或扩大数量。
        if (!sameSession() || MachineMenu.virtualMenu(menu)) {
            return failure("machine_menu_session_invalid", "This menu has no valid observed machine origin.", FailureType.UNSUPPORTED);
        }
        if (r.entryIndex >= menu.slots.size()) return failure("machine_entry_unobserved", "The requested entry was not in the menu inspection.", FailureType.TARGET_LOST);
        Slot machine = menu.getSlot(r.entryIndex);
        if (machine.container == player.getInventory() || !machine.isActive() || !MachineMenu.transferable(machine)) {
            return failure("machine_entry_not_real_inventory", "The selected machine entry does not expose verified real inventory backing.", FailureType.UNSUPPORTED);
        }
        if (NavigationSafetyContext.protectsUse(inspection.origin.position())) {
            return failure("machine_transfer_protected", "The selected machine is explicitly protected from changes.", FailureType.UNSUPPORTED);
        }
        if (!BuiltInRegistries.ITEM.containsKey(r.itemId)) return failure("machine_item_unknown", "The selected item is not registered.", FailureType.NO_MATERIAL);
        List<Integer> playerEntries = playerEntries();
        if (playerEntries.size() != 36) return failure("machine_player_inventory_unproven", "The menu does not expose each ordinary player inventory entry exactly once.", FailureType.UNSUPPORTED);
        int remaining = r.count - placed;
        if (r.deposit) {
            destinationEntry = r.entryIndex;
            sourceEntry = -1;
            // 多叠同组件材料共同满足本次数量即可；逐叠原生拿取，已确认放入的部分不重做。
            for (int index : playerEntries) {
                Slot source = menu.getSlot(index);
                ItemStack stack = source.getItem();
                if (matchesId(stack) && source.isActive() && (!effectsStarted || compatible(stack, kind)) && source.mayPickup(player)
                        && compatible(machine.getItem(), stack) && machine.mayPlace(stack)
                        && capacity(machine, stack) >= remaining && availableSources(playerEntries, stack) >= remaining) {
                    sourceEntry = index; break;
                }
            }
            if (sourceEntry < 0) return failure("machine_deposit_source_unavailable",
                    "The current compatible carried stacks or selected entry capacity cannot satisfy the remaining deposit.", FailureType.NO_MATERIAL);
        } else {
            sourceEntry = r.entryIndex;
            ItemStack stack = machine.getItem();
            if (!matchesId(stack) || stack.getCount() < remaining || !machine.mayPickup(player)) {
                return failure("machine_withdraw_source_unavailable", "The selected real machine entry does not allow taking the requested item and count.", FailureType.NO_MATERIAL);
            }
            destinationEntry = -1;
            // 取出的同一叠可以落入多个背包槽；先核对总容量，再逐槽确认，不要求 LLM 拆成小任务。
            int totalCapacity = 0;
            for (int index : playerEntries) {
                Slot destination = menu.getSlot(index);
                if (destination.isActive() && compatible(destination.getItem(), stack) && destination.mayPlace(stack)) {
                    int free = Math.max(0, capacity(destination, stack)); totalCapacity += free;
                    if (destinationEntry < 0 && free > 0) destinationEntry = index;
                }
            }
            if (destinationEntry < 0 || totalCapacity < remaining) return failure("machine_withdraw_capacity_unavailable",
                    "The current player inventory has insufficient compatible capacity for the remaining withdrawal.", FailureType.NO_SPACE);
        }
        Slot source = menu.getSlot(sourceEntry);
        kind = source.getItem().copyWithCount(1);
        expectedInventory = inventorySnapshot();
        if (!effectsStarted) initialPlayerCount = matchingPlayerCount();
        batchEnd = placed + Math.min(remaining, source.getItem().getCount());
        var pickup = MachineMenuPolicy.pickup(source.getItem().getCount(), batchEnd - placed, source.mayPlace(kind));
        if (!pickup.supported()) {
            int sourceCount = source.getItem().getCount();
            int half = sourceCount / 2 + sourceCount % 2;
            return failure("machine_output_exact_count_unavailable",
                    "This output-only entry cannot accept a remainder; request its whole stack or native half-stack amount " + half + ".", FailureType.UNSUPPORTED);
        }
        pickupButton = pickup.button();
        pickupAmount = pickup.amount();
        phase = Phase.PICKUP;
        return TaskState.RUNNING;
    }

    private TaskState pickup() {
        Slot source = menu.getSlot(sourceEntry);
        ItemStack stack = source.getItem();
        if (!menu.getCarried().isEmpty() || !compatible(stack, kind) || stack.isEmpty()
                || stack.getCount() < pickupAmount || !source.mayPickup(player)
                || !MachineMenu.transferable(source)) {
            return failure("machine_source_changed", "The observed source changed before pickup.", FailureType.TARGET_LOST);
        }
        // 原生整堆或半堆拾取数量仍必须与审核通过的数量完全一致。
        int livePickup = pickupButton == 0 ? stack.getCount() : (stack.getCount() + 1) / 2;
        if (livePickup != pickupAmount) return failure("machine_source_count_changed",
                "The source count changed before native pickup; inspect it again.", FailureType.TARGET_LOST);
        return click(sourceEntry, pickupButton, Phase.PICKUP, pickupAmount,
                kind.copyWithCount(pickupAmount), -pickupAmount);
    }

    // 鼠标上正好是剩余数量就一次全放；鼠标拿得更多时逐件右键放，达到要求后再退回余量。
    private TaskState place() {
        ItemStack carried = menu.getCarried();
        // 只在自有背包内续选落点；机器的授权入口保持不变，换槽不增加提取数量。
        if (!r.deposit && capacity(menu.getSlot(destinationEntry), kind) <= 0) {
            for (int index : playerEntries()) {
                Slot candidate = menu.getSlot(index);
                if (candidate.isActive() && compatible(candidate.getItem(), kind) && candidate.mayPlace(kind)
                        && capacity(candidate, kind) > 0) { destinationEntry = index; break; }
            }
        }
        Slot destination = menu.getSlot(destinationEntry);
        if (carried.isEmpty() || !ItemStack.isSameItemSameComponents(carried, kind)
                || !destination.isActive() || !MachineMenu.transferable(destination)
                || !compatible(destination.getItem(), kind) || !destination.mayPlace(kind)) {
            return failure("machine_destination_changed", "The selected destination no longer accepts the confirmed carried item.", FailureType.UNKNOWN);
        }
        int remaining = batchEnd - placed;
        int amount = carried.getCount() == remaining && capacity(destination, kind) >= remaining ? remaining : 1;
        if (capacity(destination, kind) < amount) return failure("machine_destination_full",
                "The observed destination lost capacity before placement.", FailureType.NO_SPACE);
        return click(destinationEntry, amount == carried.getCount() ? 0 : 1,
                Phase.PLACE, amount, kind.copyWithCount(carried.getCount() - amount), amount);
    }

    // 只退回原来源槽；它现在不能接收时停止并报告未结清，不另找一个未经计划的槽位塞进去。
    private TaskState returnRemainder() {
        Slot source = menu.getSlot(sourceEntry);
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) { phase = Phase.VERIFY; return TaskState.RUNNING; }
        if (!ItemStack.isSameItemSameComponents(carried, kind) || !source.isActive()
                || !MachineMenu.transferable(source) || !compatible(source.getItem(), kind)
                || !source.mayPlace(carried) || capacity(source, kind) < carried.getCount()) {
            return failure("machine_cursor_return_unavailable", "The original entry cannot accept the remaining cursor stack; stop and reconcile the menu.", FailureType.UNKNOWN);
        }
        return click(sourceEntry, 0, Phase.RETURN, carried.getCount(), ItemStack.EMPTY, carried.getCount());
    }

    // 提交前计算本次点击后预期的背包、鼠标和机器来源数量；确认这些变化才推进记账。
    private TaskState click(int entryIndex, int button, Phase submittedPhase, int amount,
            ItemStack expectedCursor, int entryDelta) {
        Slot entry = menu.getSlot(entryIndex);
        List<ItemStack> afterInventory = copyInventory(expectedInventory);
        if (entry.container == player.getInventory()) {
            int inventoryIndex = entry.getContainerSlot();
            ItemStack prior = afterInventory.get(inventoryIndex);
            int afterCount = prior.getCount() + entryDelta;
            if (afterCount < 0) return failure("machine_inventory_delta_invalid", "The exact transfer debit is no longer possible.", FailureType.UNKNOWN);
            afterInventory.set(inventoryIndex, kind.copyWithCount(afterCount));
        }
        ItemStack expected = expectedCursor.isEmpty() ? ItemStack.EMPTY : expectedCursor.copy();
        ItemStack cursorBefore = menu.getCarried().copy(), entryBefore = entry.getItem().copy();
        List<ItemStack> beforeInventory = copyInventory(expectedInventory);
        ItemStack expectedMachineSource = submittedPhase == Phase.PICKUP
                && entry.container != player.getInventory()
                ? kind.copyWithCount(entry.getItem().getCount() - amount) : null;
        pendingInventory = afterInventory;
        pendingPhase = submittedPhase;
        pendingAmount = amount;
        var context = ClientRuntime.requireContext(player);
        receipt = context.menus().click(context, entryIndex, button, ClickType.PICKUP,
                (fresh, nativeReceipt) -> {
                    if (!sameSession()) return MenuConfirmation.Verdict.DIVERGED;
                    // 提取还必须确认机器物理入口中的数量精确扣减；仅把物品复制到游标的虚拟输出无法通过。
                    boolean sourceDebited = expectedMachineSource == null
                            || MachineMenu.same(entry.getItem(), expectedMachineSource);
                    if (sourceDebited && MachineMenu.same(menu.getCarried(), expected)
                            && inventoryMatches(afterInventory)) {
                        return MenuConfirmation.Verdict.APPLIED;
                    }
                    // 同步后仍精确等于点击前状态，表示这一笔未执行；之前已确认的批次不因此丢失或被重放。
                    if (MachineMenu.same(entry.getItem(), entryBefore) && MachineMenu.same(menu.getCarried(), cursorBefore)
                            && inventoryMatches(beforeInventory)) return MenuConfirmation.Verdict.NOT_APPLIED;
                    return MenuConfirmation.Verdict.PENDING;
                }, 40);
        // 菜单端口的前置拒绝会直接抛错；只有它实际接收了本次点击，才记录已提交且等待原生回执。
        effectsStarted = true; uncertain = true;
        return TaskState.RUNNING;
    }

    // 最后要求放入数量、背包净变化和空鼠标同时正确，避免只数点击次数就声称搬运完成。
    private TaskState verify() {
        actualPlayerDelta = matchingPlayerCount() - initialPlayerCount;
        if (placed != r.count || !menu.getCarried().isEmpty() || !inventoryMatches(expectedInventory)
                || actualPlayerDelta != (r.deposit ? -r.count : r.count)) {
            uncertain = true;
            return failure("machine_transfer_delta_unconfirmed", "The exact final inventory delta and empty cursor were not both confirmed.", FailureType.UNKNOWN);
        }
        verified = true;
        uncertain = false;
        return TaskState.SUCCESS;
    }

    private boolean sameSession() {
        if (inspection == null || player.containerMenu != menu || menu == player.inventoryMenu
                || !MachineMenu.sameEntries(inspection, menu)) return false;
        var origin = inspection.origin;
        var context = ClientRuntime.requireContext(player);
        return origin.player().get() == player && context.bodyEpoch() == inspection.bodyEpoch
                && MenuVisibility.matches(context.minecraft(), menu)
                && origin.dimension().equals(player.level().dimension().location().toString())
                && player.level().isLoaded(origin.position())
                && BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(origin.position()).getBlock()).equals(origin.blockId());
    }

    private List<Integer> playerEntries() {
        boolean[] seen = new boolean[36];
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.getSlot(index);
            if (slot.container != player.getInventory()) continue;
            int inventory = slot.getContainerSlot();
            if (inventory < 0 || inventory >= 36) continue;
            if (seen[inventory] || !MachineMenu.transferable(slot)) return List.of();
            seen[inventory] = true; result.add(index);
        }
        return result;
    }

    private List<ItemStack> inventorySnapshot() {
        List<ItemStack> result = new ArrayList<>();
        for (int index = 0; index < 36; index++) result.add(player.getInventory().getItem(index).copy());
        return result;
    }

    private static List<ItemStack> copyInventory(List<ItemStack> input) { return new ArrayList<>(input.stream().map(ItemStack::copy).toList()); }

    private boolean inventoryMatches(List<ItemStack> expected) {
        if (expected == null || expected.size() != 36) return false;
        for (int index = 0; index < 36; index++) if (!MachineMenu.same(player.getInventory().getItem(index), expected.get(index))) return false;
        return true;
    }

    private int matchingPlayerCount() {
        int result = 0;
        for (int index = 0; index < 36; index++) {
            ItemStack stack = player.getInventory().getItem(index);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, kind)) result += stack.getCount();
        }
        return result;
    }

    private int availableSources(List<Integer> entries, ItemStack sample) {
        int total = 0;
        for (int index : entries) {
            Slot source = menu.getSlot(index);
            if (source.isActive() && source.mayPickup(player) && compatible(source.getItem(), sample)) total += source.getItem().getCount();
        }
        return total;
    }

    private Phase afterBatch() { return placed < r.count ? Phase.NEXT_BATCH : Phase.VERIFY; }

    private boolean matchesId(ItemStack stack) { return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(r.itemId); }
    private static boolean compatible(ItemStack stack, ItemStack item) { return stack.isEmpty() || ItemStack.isSameItemSameComponents(stack, item); }
    private static int capacity(Slot slot, ItemStack item) { return Math.min(item.getMaxStackSize(), slot.getMaxStackSize(item)) - slot.getItem().getCount(); }

    private TaskState failure(String code, String message, FailureType type) {
        failureCode = code; fail(message, type); return TaskState.FAILED;
    }

    // 未完成时让原生关闭逻辑处理鼠标物品，并继续标记结果不确定；不能据此认为已回到点击前状态。
    @Override protected void cleanup() {
        super.cleanup();
        // 收尾可能关闭失败菜单；先保存这一刻的全部可见槽位和报价，调用者无需再开界面补观察。
        try { menuReport = MachineMenu.inspect(player); }
        catch (RuntimeException unavailable) { observationFailure = unavailable.getMessage(); }
        // 已确认的部分搬运是已知效果；只有未结清点击、库存分歧或待返还游标才属于真正的未知项。
        if (effectsStarted && !verified && (!inventoryMatches(expectedInventory)
                || receipt != null && !receipt.terminal())) uncertain = true;
        if (effectsStarted && menu != null && !menu.getCarried().isEmpty()) uncertain = true;
        if (effectsStarted && menu != null && player.containerMenu == menu && !menu.getCarried().isEmpty()) {
            try {
                var context = ClientRuntime.requireContext(player);
                context.menus().closeForTaskBoundary(context, 40,
                        "machine transfer ended before verification; native close owns cursor return");
                boundaryCloseRequested = true;
            } catch (RuntimeException unavailable) { /* Actor body/human handoff also owns cursor return. */ }
        }
        if (!kind.isEmpty()) actualPlayerDelta = matchingPlayerCount() - initialPlayerCount;
    }

    @Override public boolean mustSettleBeforeSatisfiedCancellation() { return effectsStarted && !verified; }

    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", r.deposit ? "deposit" : "withdraw");
        data.put("item_id", r.itemId.toString());
        data.put("requested_count", r.count);
        data.put("observed_entry_index", r.entryIndex);
        data.put("transfer_verified", verified);
        data.put("actual_player_delta", actualPlayerDelta);
        data.put("confirmed_destination_count", placed);
        data.put("confirmed_clicks", confirmedClicks);
        data.put("machine_production_verified", false);
        data.put("effects_started", effectsStarted);
        data.put("outcome_uncertain", uncertain);
        data.put("mechanical_retry_allowed", !uncertain && confirmedClicks == 0);
        data.put("boundary_close_requested", boundaryCloseRequested);
        data.put("cursor_empty", menu != null && menu.getCarried().isEmpty());
        if (nativeStatus != null) data.put("native_receipt_status", nativeStatus);
        if (failureCode != null) data.put("failure_code", failureCode);
        if (menuReport != null) data.put("menu_report", menuReport);
        if (observationFailure != null) data.put("menu_observation_failure", observationFailure);
        return data;
    }

    @Override protected String successMessage() {
        return "Verified an exact " + r.count + " item " + (r.deposit ? "deposit into" : "withdrawal from")
                + " the observed real machine entry. Machine processing and output still require separate verification.";
    }
    @Override protected String cancelledMessage() { return "Machine item transfer interrupted; reconcile its actual inventory delta and cursor before another transfer."; }
}
