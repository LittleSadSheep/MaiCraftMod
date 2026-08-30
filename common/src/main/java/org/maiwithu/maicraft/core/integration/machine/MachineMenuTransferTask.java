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
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

/**
 * One exact selected-item transfer through observed real inventory entries. All click indexes are
 * bound to a one-use inspection of the same menu instance. No QUICK_MOVE, swaps or ghost setters.
 */
public final class MachineMenuTransferTask extends AbstractCompanionTask<MachineMenuTransferTaskRecord> {
    private enum Phase { START, PICKUP, PLACE, RETURN, VERIFY }
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
    private int initialPlayerCount;
    private int actualPlayerDelta;
    private boolean effectsStarted;
    private boolean uncertain;
    private boolean verified;
    private boolean boundaryCloseRequested;
    private String failureCode;
    private String nativeStatus;

    public MachineMenuTransferTask(LocalPlayer player, MachineMenuTransferTaskRecord record) { super(player, record); }

    @Override protected TaskState onTick() {
        if (phase == Phase.START) return begin();
        if (!sameSession()) return failure("machine_menu_session_changed",
                "The inspected machine menu session changed; no additional item action was sent.", FailureType.TARGET_LOST);
        if (receipt != null) {
            var context = ClientRuntime.requireContext(player);
            receipt = context.menus().poll(context, receipt);
            if (!receipt.terminal()) return TaskState.RUNNING;
            nativeStatus = receipt.status().name().toLowerCase(java.util.Locale.ROOT);
            if (receipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED) {
                uncertain = true;
                return failure("machine_transfer_unconfirmed", "The exact native inventory transaction was not confirmed; no click was replayed.", FailureType.UNKNOWN);
            }
            expectedInventory = pendingInventory;
            receipt = null;
            if (pendingPhase == Phase.PICKUP) phase = Phase.PLACE;
            else if (pendingPhase == Phase.PLACE) {
                placed += pendingAmount;
                phase = placed < r.count ? Phase.PLACE : menu.getCarried().isEmpty() ? Phase.VERIFY : Phase.RETURN;
            } else if (pendingPhase == Phase.RETURN) phase = Phase.VERIFY;
            r.extendDeadlineTo(player.level().getGameTime() + 60L * 20L);
        }
        if (!inventoryMatches(expectedInventory)) {
            uncertain |= effectsStarted;
            return failure("machine_inventory_changed", "The player's inventory changed outside the confirmed transfer.", FailureType.UNKNOWN);
        }
        if (phase == Phase.VERIFY) return verify();
        if (NavigationSafetyContext.protectsMutation(inspection.origin.position())) {
            return failure("machine_transfer_protected", "The selected machine is explicitly protected from changes.", FailureType.UNSUPPORTED);
        }
        var context = ClientRuntime.requireContext(player);
        if (!context.mutationAvailable()) return TaskState.RUNNING;
        return switch (phase) {
            case PICKUP -> pickup();
            case PLACE -> place();
            case RETURN -> returnRemainder();
            case VERIFY -> verify();
            case START -> begin();
        };
    }

    private TaskState begin() {
        try { inspection = MachineMenu.consume(r.receiptId, player); }
        catch (IllegalArgumentException stale) { return failure("machine_menu_receipt_invalid", stale.getMessage(), FailureType.TARGET_LOST); }
        menu = player.containerMenu;
        if (!sameSession() || MachineMenu.virtualMenu(menu)) {
            return failure("machine_menu_session_invalid", "This menu has no valid observed machine origin.", FailureType.UNSUPPORTED);
        }
        if (r.entryIndex >= menu.slots.size()) return failure("machine_entry_unobserved", "The requested entry was not in the menu inspection.", FailureType.TARGET_LOST);
        Slot machine = menu.getSlot(r.entryIndex);
        if (machine.container == player.getInventory() || !machine.isActive() || !MachineMenu.transferable(machine)) {
            return failure("machine_entry_not_real_inventory", "The selected machine entry does not expose verified real inventory backing.", FailureType.UNSUPPORTED);
        }
        if (NavigationSafetyContext.protectsMutation(inspection.origin.position())) {
            return failure("machine_transfer_protected", "The selected machine is explicitly protected from changes.", FailureType.UNSUPPORTED);
        }
        if (!BuiltInRegistries.ITEM.containsKey(r.itemId)) return failure("machine_item_unknown", "The selected item is not registered.", FailureType.NO_MATERIAL);
        List<Integer> playerEntries = playerEntries();
        if (playerEntries.size() != 36) return failure("machine_player_inventory_unproven", "The menu does not expose each ordinary player inventory entry exactly once.", FailureType.UNSUPPORTED);
        if (r.deposit) {
            destinationEntry = r.entryIndex;
            sourceEntry = -1;
            for (int index : playerEntries) {
                Slot source = menu.getSlot(index);
                ItemStack stack = source.getItem();
                if (matchesId(stack) && stack.getCount() >= r.count && source.mayPickup(player)
                        && compatible(machine.getItem(), stack) && machine.mayPlace(stack)
                        && capacity(machine, stack) >= r.count) { sourceEntry = index; break; }
            }
            if (sourceEntry < 0) return failure("machine_deposit_source_unavailable",
                    "No single carried stack has the exact requested amount accepted by the selected entry; choose a smaller count or compatible entry.", FailureType.NO_MATERIAL);
        } else {
            sourceEntry = r.entryIndex;
            ItemStack stack = machine.getItem();
            if (!matchesId(stack) || stack.getCount() < r.count || !machine.mayPickup(player)) {
                return failure("machine_withdraw_source_unavailable", "The selected real machine entry does not allow taking the requested item and count.", FailureType.NO_MATERIAL);
            }
            destinationEntry = -1;
            for (int index : playerEntries) {
                Slot destination = menu.getSlot(index);
                if (compatible(destination.getItem(), stack) && destination.mayPlace(stack)
                        && capacity(destination, stack) >= r.count) { destinationEntry = index; break; }
            }
            if (destinationEntry < 0) return failure("machine_withdraw_capacity_unavailable",
                    "No player inventory entry can hold the exact requested stack; free space or choose a smaller count.", FailureType.NO_SPACE);
        }
        Slot source = menu.getSlot(sourceEntry);
        kind = source.getItem().copyWithCount(1);
        expectedInventory = inventorySnapshot();
        initialPlayerCount = matchingPlayerCount();
        var pickup = MachineMenuPolicy.pickup(source.getItem().getCount(), r.count, source.mayPlace(kind));
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
        // The native whole/half pickup amount must still be exactly the reviewed amount.
        int livePickup = pickupButton == 0 ? stack.getCount() : (stack.getCount() + 1) / 2;
        if (livePickup != pickupAmount) return failure("machine_source_count_changed",
                "The source count changed before native pickup; inspect it again.", FailureType.TARGET_LOST);
        return click(sourceEntry, pickupButton, Phase.PICKUP, pickupAmount,
                kind.copyWithCount(pickupAmount), -pickupAmount);
    }

    private TaskState place() {
        ItemStack carried = menu.getCarried();
        Slot destination = menu.getSlot(destinationEntry);
        if (carried.isEmpty() || !ItemStack.isSameItemSameComponents(carried, kind)
                || !destination.isActive() || !MachineMenu.transferable(destination)
                || !compatible(destination.getItem(), kind) || !destination.mayPlace(kind)) {
            return failure("machine_destination_changed", "The selected destination no longer accepts the confirmed carried item.", FailureType.UNKNOWN);
        }
        int remaining = r.count - placed;
        int amount = carried.getCount() == remaining ? remaining : 1;
        if (capacity(destination, kind) < amount) return failure("machine_destination_full",
                "The observed destination lost capacity before placement.", FailureType.NO_SPACE);
        return click(destinationEntry, amount == carried.getCount() ? 0 : 1,
                Phase.PLACE, amount, kind.copyWithCount(carried.getCount() - amount), amount);
    }

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
        ItemStack expectedMachineSource = submittedPhase == Phase.PICKUP
                && entry.container != player.getInventory()
                ? kind.copyWithCount(entry.getItem().getCount() - amount) : null;
        pendingInventory = afterInventory;
        pendingPhase = submittedPhase;
        pendingAmount = amount;
        effectsStarted = true;
        uncertain = true;
        var context = ClientRuntime.requireContext(player);
        receipt = context.menus().click(context, entryIndex, button, ClickType.PICKUP,
                (fresh, nativeReceipt) -> {
                    if (!sameSession()) return MenuConfirmation.Verdict.DIVERGED;
                    // Withdrawal also requires the physical machine entry's exact debit. A
                    // virtual output that merely copies an item to the cursor cannot pass.
                    boolean sourceDebited = expectedMachineSource == null
                            || MachineMenu.same(entry.getItem(), expectedMachineSource);
                    if (sourceDebited && MachineMenu.same(menu.getCarried(), expected)
                            && inventoryMatches(afterInventory)) {
                        return MenuConfirmation.Verdict.APPLIED;
                    }
                    return MenuConfirmation.Verdict.PENDING;
                }, 40);
        return TaskState.RUNNING;
    }

    private TaskState verify() {
        actualPlayerDelta = matchingPlayerCount() - initialPlayerCount;
        if (placed != r.count || !menu.getCarried().isEmpty() || !inventoryMatches(expectedInventory)
                || actualPlayerDelta != (r.deposit ? -r.count : r.count)) {
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

    private boolean matchesId(ItemStack stack) { return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(r.itemId); }
    private static boolean compatible(ItemStack stack, ItemStack item) { return stack.isEmpty() || ItemStack.isSameItemSameComponents(stack, item); }
    private static int capacity(Slot slot, ItemStack item) { return Math.min(item.getMaxStackSize(), slot.getMaxStackSize(item)) - slot.getItem().getCount(); }

    private TaskState failure(String code, String message, FailureType type) {
        failureCode = code; uncertain |= effectsStarted; fail(message, type); return TaskState.FAILED;
    }

    @Override protected void cleanup() {
        super.cleanup();
        if (effectsStarted && !verified) uncertain = true;
        if (menu != null && player.containerMenu == menu && !menu.getCarried().isEmpty()) {
            try {
                var context = ClientRuntime.requireContext(player);
                context.menus().closeForTaskBoundary(context, 40,
                        "machine transfer ended with a cursor stack; native close owns its return");
                boundaryCloseRequested = true;
            } catch (RuntimeException unavailable) { /* Actor body/human handoff also owns cursor return. */ }
            uncertain = true;
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
        data.put("machine_production_verified", false);
        data.put("effects_started", effectsStarted);
        data.put("outcome_uncertain", uncertain);
        data.put("mechanical_retry_allowed", !effectsStarted);
        data.put("boundary_close_requested", boundaryCloseRequested);
        data.put("cursor_empty", menu != null && menu.getCarried().isEmpty());
        if (nativeStatus != null) data.put("native_receipt_status", nativeStatus);
        if (failureCode != null) data.put("failure_code", failureCode);
        if (verified) data.put("menu_report", MachineMenu.inspect(player));
        return data;
    }

    @Override protected String successMessage() {
        return "Verified an exact " + r.count + " item " + (r.deposit ? "deposit into" : "withdrawal from")
                + " the observed real machine entry. Machine processing and output still require separate verification.";
    }
    @Override protected String cancelledMessage() { return "Machine item transfer interrupted; reconcile its actual inventory delta and cursor before another transfer."; }
}
