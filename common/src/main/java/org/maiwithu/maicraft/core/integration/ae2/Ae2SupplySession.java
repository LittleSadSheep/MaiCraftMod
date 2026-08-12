// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ae2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;

/** Complete first-person, cross-tick AE2 supply transaction. */
final class Ae2SupplySession implements Ae2ResourceSupply.Session {
    private enum Phase {
        START,
        WAIT_STAGE,
        WAIT_SELECT,
        NAVIGATE_FIXED,
        FACE_FIXED,
        OPEN_FIXED,
        OPEN_WIRELESS,
        WAIT_OPEN,
        WAIT_REPOSITORY,
        PROCESS_ITEM,
        COLLECT_EXACT,
        WAIT_EXACT_UNIT,
        PLACE_EXACT,
        WAIT_EXACT_PLACE,
        WAIT_CRAFT_AMOUNT,
        SUBMIT_CRAFT_AMOUNT,
        WAIT_CRAFT_CONFIRM,
        WAIT_CRAFT_PLAN,
        WAIT_CRAFT_RETURN,
        WAIT_CRAFT_STOCK,
        CLEAN_RETURN_CURSOR,
        CLEAN_WAIT_CURSOR_RETURN,
        CLEAN_CLOSE,
        CLEAN_WAIT_CLOSE,
        CLEAN_RESTORE,
        CLEAN_WAIT_RESTORE,
        CLEAN_SELECT,
        RETURN_ORIGIN,
        FINISHED
    }

    private record InventorySwap(
            int sourceSlot,
            int hotbarSlot,
            ItemStack sourceBefore,
            ItemStack hotbarBefore) {
        InventorySwap {
            sourceBefore = sourceBefore.copy();
            hotbarBefore = hotbarBefore.copy();
        }
    }

    private record PendingTerminal(
            Ae2ResourceSupply.Status status, String code, String message) {}

    private static final class ExactExtraction {
        final ResourceLocation itemId;
        final long serial;
        final ItemStack sample;
        final int planGroupIndex;
        final int planAllocationIndex;
        final int inventorySlot;
        final int menuSlot;
        final ItemStack destinationBefore;
        final int batch;
        final int inventoryCountBefore;
        int collected;
        boolean planValidated;

        ExactExtraction(
                ResourceLocation itemId, long serial, ItemStack sample,
                int planGroupIndex, int planAllocationIndex,
                int inventorySlot, int menuSlot, ItemStack destinationBefore,
                int batch, int inventoryCountBefore) {
            this.itemId = itemId;
            this.serial = serial;
            this.sample = sample.copyWithCount(1);
            this.planGroupIndex = planGroupIndex;
            this.planAllocationIndex = planAllocationIndex;
            this.inventorySlot = inventorySlot;
            this.menuSlot = menuSlot;
            this.destinationBefore = destinationBefore.copy();
            this.batch = batch;
            this.inventoryCountBefore = inventoryCountBefore;
        }
    }

    private record ExtractionDestination(
            int inventorySlot, int menuSlot, ItemStack before, int capacity) {
        ExtractionDestination { before = before.copy(); }
    }

    private final LocalPlayer player;
    private final Ae2ResourceSupply.Request request;
    private final Ae2ReflectionBridge bridge;
    private final int originalSelected;
    private final BlockPos callerOrigin;
    private final ResourceLocation callerDimension;

    private Phase phase = Phase.START;
    private Phase afterSelect = Phase.START;
    private int phaseTicks;
    private Map<ResourceLocation, Integer> baseline;
    private final Map<ResourceLocation, ResourceLocation> lockedVariantByGroup = new LinkedHashMap<>();
    private Ae2SupplyPlanner.Plan plan;
    private int groupIndex;
    private boolean effectsStarted;
    private String terminalAccess = "unavailable";
    private Ae2TerminalAccess.Wireless wireless;
    private List<Ae2TerminalAccess.FixedTarget> fixedCandidates = List.of();
    private Ae2TerminalAccess.FixedTarget fixedTarget;
    private Ae2TerminalAccess.Known remembered;
    private boolean movedForFixedTerminal;
    private PlayerNav navigation;
    private InventorySwap inventorySwap;
    private int selectedTerminalSlot = -1;
    private NativeActionReceipt nativeReceipt;
    private MenuReceipt menuReceipt;
    private ExactExtraction exactExtraction;
    private int craftingMissing;
    private ResourceLocation craftingItemId;
    private ItemStack craftingSample = ItemStack.EMPTY;
    private int craftingPlanGroupIndex = -1;
    private int craftingPlanAllocationIndex = -1;
    private int craftingRequests;
    private int craftingJobsSubmitted;
    private boolean craftingJobEffectPending;
    private String craftingSubmitFailure;
    private ItemStack cleanupCursorBefore = ItemStack.EMPTY;
    private PendingTerminal pendingTerminal;
    private Ae2ResourceSupply.Outcome terminal;

    Ae2SupplySession(
            LocalPlayer player, Ae2ResourceSupply.Request request, Ae2ReflectionBridge bridge) {
        this.player = player;
        this.request = request;
        this.bridge = bridge;
        this.originalSelected = Mth.clamp(player.getInventory().selected, 0, 8);
        this.callerOrigin = player.blockPosition().immutable();
        this.callerDimension = player.level().dimension().location();
        this.baseline = inventoryCounts();
    }

    @Override
    public Optional<Ae2ResourceSupply.Outcome> tick(LocalPlayerContext context) {
        if (terminal != null) return Optional.of(terminal);
        validateContext(context);
        phaseTicks++;
        if (!context.permitsNativeActions()) {
            if (effectsStarted || nativeReceipt != null || menuReceipt != null
                    || craftingJobEffectPending) {
                finishUncertain("control_revoked",
                        "automation lost native-action authority during an AE2 transaction");
            } else {
                finishNow(Ae2ResourceSupply.Status.RETRYABLE_FAILURE,
                        "control_required", "automation does not own the local-player controls", false);
            }
            return Optional.of(terminal);
        }
        try {
            switch (phase) {
                case START -> start(context);
                case WAIT_STAGE -> waitStage(context);
                case WAIT_SELECT -> waitSelect(context);
                case NAVIGATE_FIXED -> navigateFixed();
                case FACE_FIXED -> faceFixed(context);
                case OPEN_FIXED -> openFixed(context);
                case OPEN_WIRELESS -> openWireless(context);
                case WAIT_OPEN -> waitOpen(context);
                case WAIT_REPOSITORY -> waitRepository();
                case PROCESS_ITEM -> processItem(context);
                case COLLECT_EXACT -> collectExact(context);
                case WAIT_EXACT_UNIT -> waitExactUnit(context);
                case PLACE_EXACT -> placeExact(context);
                case WAIT_EXACT_PLACE -> waitExactPlace(context);
                case WAIT_CRAFT_AMOUNT -> waitCraftAmount(context);
                case SUBMIT_CRAFT_AMOUNT -> submitCraftAmount(context);
                case WAIT_CRAFT_CONFIRM -> waitCraftConfirm(context);
                case WAIT_CRAFT_PLAN -> waitCraftPlan(context);
                case WAIT_CRAFT_RETURN -> waitCraftReturn(context);
                case WAIT_CRAFT_STOCK -> waitCraftStock();
                case CLEAN_RETURN_CURSOR -> cleanReturnCursor(context);
                case CLEAN_WAIT_CURSOR_RETURN -> cleanWaitCursorReturn(context);
                case CLEAN_CLOSE -> cleanClose(context);
                case CLEAN_WAIT_CLOSE -> cleanWaitClose(context);
                case CLEAN_RESTORE -> cleanRestore(context);
                case CLEAN_WAIT_RESTORE -> cleanWaitRestore(context);
                case CLEAN_SELECT -> cleanSelect(context);
                case RETURN_ORIGIN -> returnOrigin();
                case FINISHED -> { }
            }
        } catch (Ae2ProtocolException failure) {
            if (nativeReceipt != null && !nativeReceipt.terminal() || craftingJobEffectPending) {
                finishUncertain("ae2_protocol_outcome_uncertain",
                        "AE2 client protocol failed after a transaction began: " + failure.getMessage());
            } else {
                beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE,
                        "ae2_protocol_failed", "AE2 client menu access failed: " + failure.getMessage());
            }
        }
        return Optional.ofNullable(terminal);
    }

    @Override
    public Optional<Ae2ResourceSupply.Outcome> outcome() {
        return Optional.ofNullable(terminal);
    }

    @Override
    public String phase() {
        return phase.name().toLowerCase();
    }

    @Override
    public void pause(LocalPlayerContext context) {
        validateContext(context);
        context.body().releaseAll();
    }

    @Override
    public Ae2ResourceSupply.Outcome cancel(LocalPlayerContext context, String reason) {
        if (terminal != null) return terminal;
        validateContext(context);
        stopNavigation();
        context.body().releaseAll();
        boolean uncertain = effectsStarted
                || nativeReceipt != null && !nativeReceipt.terminal()
                || menuReceipt != null && !menuReceipt.terminal()
                || craftingJobEffectPending
                || !player.containerMenu.getCarried().isEmpty();
        if (player.containerMenu != player.inventoryMenu) {
            try {
                // Vanilla close returns a carried cursor stack through the authoritative menu path.
                MenuReceipt closeReceipt = context.menus().close(context, 20);
                uncertain |= !closeReceipt.terminal()
                        || closeReceipt.status() != MenuReceipt.Status.CONFIRMED_APPLIED;
            } catch (RuntimeException ignored) {
                uncertain = true;
            }
        }
        finishNow(uncertain ? Ae2ResourceSupply.Status.UNCERTAIN
                        : Ae2ResourceSupply.Status.CANCELLED,
                uncertain ? "cancelled_outcome_uncertain" : "cancelled",
                reason == null ? "AE2 resource supply was cancelled" : reason,
                uncertain);
        return terminal;
    }

    private void start(LocalPlayerContext context) {
        if (!player.containerMenu.getCarried().isEmpty()) {
            finishNow(Ae2ResourceSupply.Status.FAILED, "inventory_cursor_busy",
                    "clear the inventory cursor before exact AE2 extraction", false);
            return;
        }
        if (bridge.isStorageMenu(player.containerMenu)) {
            terminalAccess = "preopened_storage_menu";
            setPhase(Phase.WAIT_REPOSITORY);
            return;
        }
        if (context.minecraft().screen != null || player.containerMenu != player.inventoryMenu) {
            finishNow(Ae2ResourceSupply.Status.RETRYABLE_FAILURE, "screen_open",
                    "close the current screen before opening an AE2 terminal", false);
            return;
        }

        wireless = Ae2TerminalAccess.findWireless(player);
        if (wireless != null) {
            terminalAccess = wireless.itemId().toString();
            if (wireless.inventorySlot() <= 8) {
                selectedTerminalSlot = wireless.inventorySlot();
                requestSelect(context, selectedTerminalSlot, Phase.OPEN_WIRELESS);
            } else {
                int hotbar = firstEmpty(0, 8);
                if (hotbar < 0) hotbar = originalSelected;
                beginStage(context, wireless.inventorySlot(), hotbar, Phase.OPEN_WIRELESS);
            }
            return;
        }

        remembered = Ae2TerminalAccess.remembered(player);
        if (remembered != null && Ae2TerminalAccess.stillPresent(
                player, bridge, remembered.position(), remembered.side())) {
            fixedCandidates = Ae2TerminalAccess.targetsFor(
                    player, remembered.position(), remembered.side());
        } else {
            if (remembered != null) Ae2TerminalAccess.discard(remembered);
            remembered = null;
            Ae2TerminalAccess.Discovery discovery = Ae2TerminalAccess.discover(player, bridge);
            fixedCandidates = discovery.targets();
            if (fixedCandidates.isEmpty()) {
                String code = discovery.terminalsObserved() > 0
                        ? "fixed_terminal_unreachable" : "fixed_terminal_not_found";
                finishNow(Ae2ResourceSupply.Status.FAILED, code,
                        discovery.terminalsObserved() > 0
                                ? "loaded fixed AE2 terminals have no bounded interaction approach"
                                : "no wireless or loaded fixed AE2 terminal is available", false);
                return;
            }
        }
        if (fixedCandidates.isEmpty()) {
            finishNow(Ae2ResourceSupply.Status.FAILED, "fixed_terminal_unreachable",
                    "the remembered AE2 terminal has no bounded interaction approach", false);
            return;
        }
        terminalAccess = "fixed_terminal";
        int emptyHotbar = firstEmpty(0, 8);
        if (emptyHotbar >= 0) {
            selectedTerminalSlot = emptyHotbar;
            requestSelect(context, emptyHotbar, Phase.NAVIGATE_FIXED);
            return;
        }
        int emptyStorage = firstEmpty(9, 35);
        if (emptyStorage < 0) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "safe_hand_unavailable",
                    "opening a fixed AE2 terminal requires an empty hand and no slot can stage one");
            return;
        }
        beginStage(context, emptyStorage, originalSelected, Phase.NAVIGATE_FIXED);
    }

    private void beginStage(
            LocalPlayerContext context, int sourceSlot, int hotbarSlot, Phase next) {
        inventorySwap = new InventorySwap(
                sourceSlot, hotbarSlot,
                player.getInventory().getItem(sourceSlot),
                player.getInventory().getItem(hotbarSlot));
        selectedTerminalSlot = hotbarSlot;
        afterSelect = next;
        menuReceipt = context.menus().swapInventoryToHotbar(
                context, sourceSlot, hotbarSlot, INVENTORY_CONFIRM_TICKS);
        setPhase(Phase.WAIT_STAGE);
    }

    private void waitStage(LocalPlayerContext context) {
        if (!settleMenuReceipt(context, "inventory_stage_unconfirmed")) return;
        requestSelect(context, selectedTerminalSlot, afterSelect);
    }

    private void requestSelect(LocalPlayerContext context, int slot, Phase next) {
        afterSelect = next;
        if (player.getInventory().selected == slot) {
            setPhase(next);
            return;
        }
        nativeReceipt = context.actions().selectHotbar(context, slot, 20);
        setPhase(Phase.WAIT_SELECT);
    }

    private void waitSelect(LocalPlayerContext context) {
        if (!settleNativeReceipt(context, "hotbar_selection_unconfirmed")) return;
        setPhase(afterSelect);
    }

    private void navigateFixed() {
        if (fixedCandidates.isEmpty()) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "fixed_terminal_unreachable",
                    "no fixed-terminal approach remains");
            return;
        }
        if (navigation == null) {
            List<NavGoal> goals = fixedCandidates.stream()
                    .map(target -> NavGoal.exact(target.approach())).toList();
            navigation = PlayerNav.toGoal(
                    player,
                    () -> NavGoal.composite(goals),
                    1.0,
                    () -> fixedCandidates.stream()
                            .anyMatch(target -> target.approach().equals(player.blockPosition())));
        }
        PlayerNav.Status status = navigation.tick();
        if (status == PlayerNav.Status.RUNNING) return;
        if (status == PlayerNav.Status.FAILED) {
            String reason = navigation.failReason();
            stopNavigation();
            beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE,
                    remembered != null ? "last_known_terminal_path_blocked" : "fixed_terminal_unreachable",
                    "the player could not reach a fixed AE2 terminal: " + reason);
            return;
        }
        fixedTarget = fixedCandidates.stream()
                .filter(target -> target.approach().equals(player.blockPosition()))
                .findFirst().orElse(null);
        stopNavigation();
        if (fixedTarget == null || !Ae2TerminalAccess.stillPresent(
                player, bridge, fixedTarget.position(), fixedTarget.side())) {
            if (remembered != null) Ae2TerminalAccess.discard(remembered);
            beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE,
                    "fixed_terminal_changed", "the selected fixed AE2 terminal changed before use");
            return;
        }
        movedForFixedTerminal = !callerOrigin.equals(player.blockPosition());
        setPhase(Phase.FACE_FIXED);
    }

    private void faceFixed(LocalPlayerContext context) {
        Ae2TerminalAccess.FixedTarget target = fixedTarget;
        if (target == null || !Ae2TerminalAccess.stillPresent(
                player, bridge, target.position(), target.side())) {
            beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE,
                    "fixed_terminal_changed", "the selected fixed AE2 terminal changed before interaction");
            return;
        }
        double reach = player.blockInteractionRange();
        if (player.getEyePosition().distanceToSqr(target.hit()) > reach * reach) {
            beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE,
                    "fixed_terminal_unreachable", "the fixed AE2 terminal is outside native reach");
            return;
        }
        Vec3 delta = target.hit().subtract(player.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0f);
        float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(delta.y, horizontal)), -90.0f, 90.0f);
        context.body().applyMovement(BodyControlPort.Movement.STOPPED, context.tickRevision());
        context.body().requestLook(yaw, pitch, context.tickRevision());
        if (Math.abs(Mth.wrapDegrees(yaw - player.getYRot())) <= LOOK_EPSILON
                && Math.abs(pitch - player.getXRot()) <= LOOK_EPSILON) {
            setPhase(Phase.OPEN_FIXED);
        }
    }

    private void openFixed(LocalPlayerContext context) {
        Ae2TerminalAccess.FixedTarget target = fixedTarget;
        if (target == null || !Ae2TerminalAccess.stillPresent(
                player, bridge, target.position(), target.side())) {
            beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE,
                    "fixed_terminal_changed", "the fixed AE2 terminal changed before opening");
            return;
        }
        if (!player.getMainHandItem().isEmpty()) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "safe_hand_unavailable",
                    "the selected hand is no longer empty for fixed-terminal interaction");
            return;
        }
        int beforeContainer = player.containerMenu.containerId;
        nativeReceipt = context.actions().useBlock(
                context,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(target.hit(), target.side(), target.position(), false),
                NativeConfirmation.menuChanged(beforeContainer),
                TERMINAL_OPEN_TICKS);
        setPhase(Phase.WAIT_OPEN);
    }

    private void openWireless(LocalPlayerContext context) {
        if (selectedTerminalSlot < 0 || wireless == null) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "wireless_terminal_missing",
                    "the selected wireless AE2 terminal is unavailable");
            return;
        }
        ItemStack stack = player.getInventory().getItem(selectedTerminalSlot);
        if (stack.isEmpty()
                || !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(wireless.itemId())
                || player.getInventory().selected != selectedTerminalSlot) {
            beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE,
                    "wireless_terminal_changed", "the staged wireless AE2 terminal changed before use");
            return;
        }
        int beforeContainer = player.containerMenu.containerId;
        nativeReceipt = context.actions().useItem(
                context,
                InteractionHand.MAIN_HAND,
                NativeConfirmation.menuChanged(beforeContainer),
                TERMINAL_OPEN_TICKS);
        setPhase(Phase.WAIT_OPEN);
    }

    private void waitOpen(LocalPlayerContext context) {
        if (!settleNativeReceipt(context, "terminal_open_unconfirmed")) return;
        setPhase(Phase.WAIT_REPOSITORY);
    }

    private void waitRepository() {
        Object menu = storageMenuOrFail();
        if (menu == null) return;
        if (!bridge.connected(menu)) {
            beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE,
                    "ae2_network_disconnected", "the AE2 terminal is not connected to a storage network");
            return;
        }
        List<Ae2ReflectionBridge.Entry> entries = bridge.entries(menu);
        if (entries == null) {
            if (phaseTicks > REPOSITORY_READY_TICKS) {
                beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE,
                        "ae2_repository_pending", "the AE2 client repository did not become ready");
            }
            return;
        }
        if (!installPlan(entries)) return;
        if (fixedTarget != null) Ae2TerminalAccess.remember(player, fixedTarget);
        setPhase(Phase.PROCESS_ITEM);
    }

    private boolean installPlan(List<Ae2ReflectionBridge.Entry> entries) {
        if (effectsStarted) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "resource_plan_changed",
                    "AE2 refused to replace a resource plan after an effect began");
            return false;
        }
        baseline = inventoryCounts();
        Ae2SupplyPlanner.Result built = request.operation() == Ae2ResourceSupply.Operation.PREPARE
                ? Ae2SupplyPlanner.prepare(request, entries)
                : Ae2SupplyPlanner.build(player, request, entries, reservedInventorySlots());
        if (built.failure() != null) {
            Ae2SupplyPlanner.Failure failure = built.failure();
            beginFinish(Ae2ResourceSupply.Status.FAILED, failure.code(), failure.message());
            return false;
        }
        plan = built.plan();
        lockedVariantByGroup.clear();
        for (Ae2SupplyPlanner.PlannedGroup group : plan.groups()) {
            if (group.group().selectionMode() != Ae2ResourceSupply.SelectionMode.SINGLE_VARIANT) {
                continue;
            }
            List<ResourceLocation> selected = group.allocations().stream()
                    .map(Ae2SupplyPlanner.Allocation::itemId).distinct().toList();
            if (selected.size() != 1) {
                beginFinish(Ae2ResourceSupply.Status.FAILED, "internal_state_invalid",
                        "a single-variant AE2 plan selected more than one concrete item ID");
                return false;
            }
            lockedVariantByGroup.put(group.group().itemId(), selected.getFirst());
        }
        groupIndex = 0;
        exactExtraction = null;
        clearCraftingTarget();
        return true;
    }

    private void processItem(LocalPlayerContext context) {
        if (groupIndex >= request.groups().size()) {
            beginFinish(Ae2ResourceSupply.Status.SUCCEEDED,
                    request.operation() == Ae2ResourceSupply.Operation.PREPARE
                            ? "resources_prepared" : "resources_supplied",
                    request.operation() == Ae2ResourceSupply.Operation.PREPARE
                            ? "AE2 prepared the complete approved network stock"
                            : "AE2 supplied the approved resource delta");
            return;
        }
        Object menu = storageMenuOrFail();
        if (menu == null) return;
        if (!bridge.connected(menu)) {
            beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE,
                    "ae2_network_disconnected", "the AE2 network disconnected during supply");
            return;
        }
        List<Ae2ReflectionBridge.Entry> entries = bridge.entries(menu);
        if (entries == null) {
            setPhase(Phase.WAIT_REPOSITORY);
            return;
        }
        if (plan == null) {
            if (installPlan(entries)) setPhase(Phase.PROCESS_ITEM);
            return;
        }
        if (request.operation() == Ae2ResourceSupply.Operation.PREPARE) {
            processPreparedItem(context, entries);
            return;
        }
        String issue = Ae2SupplyPlanner.issue(
                plan, player, entries, reservedInventorySlots(), this::groupProgress);
        if (issue != null) {
            if (!effectsStarted) {
                if (installPlan(entries)) setPhase(Phase.PROCESS_ITEM);
            } else {
                beginFinish(Ae2ResourceSupply.Status.FAILED, "resource_plan_changed",
                        "the bound AE2 resource plan changed after an effect began: " + issue);
            }
            return;
        }
        Ae2SupplyPlanner.PlannedGroup plannedGroup = plan.groups().get(groupIndex);
        Ae2ResourceSupply.Group group = request.groups().get(groupIndex);
        int confirmed = plannedGroup.confirmedCount();
        int acquired = groupProgress(group);
        if (acquired != confirmed || confirmed > group.count()) {
            beginFinish(Ae2ResourceSupply.Status.FAILED,
                    acquired > group.count() || confirmed > group.count()
                            ? "approved_transfer_exceeded" : "inventory_changed",
                    "the live inventory no longer matches the bound AE2 transfer plan");
            return;
        }
        if (confirmed == group.count()) {
            clearCraftingTarget();
            groupIndex++;
            return;
        }
        int allocationIndex = -1;
        for (int i = 0; i < plannedGroup.allocations().size(); i++) {
            if (plannedGroup.allocations().get(i).remaining() > 0) {
                allocationIndex = i;
                break;
            }
        }
        if (allocationIndex < 0) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "internal_state_invalid",
                    "the executable AE2 allocation is missing before the group is complete");
            return;
        }
        Ae2SupplyPlanner.Allocation allocation = plannedGroup.allocations().get(allocationIndex);
        int remaining = allocation.remaining();
        List<Ae2ReflectionBridge.Entry> matching =
                Ae2SupplyPlanner.matchingEntries(entries, allocation);
        Ae2ReflectionBridge.Entry stocked = matching.stream()
                .filter(entry -> entry.storedAmount() > 0)
                .sorted(Comparator.comparingLong(Ae2ReflectionBridge.Entry::storedAmount).reversed()
                        .thenComparingLong(Ae2ReflectionBridge.Entry::serial))
                .findFirst().orElse(null);
        if (stocked != null) {
            if (!player.containerMenu.getCarried().isEmpty()) {
                beginFinish(Ae2ResourceSupply.Status.FAILED, "inventory_cursor_changed",
                        "the inventory cursor changed before exact AE2 extraction");
                return;
            }
            ExtractionDestination destination = exactDestination(player.containerMenu, stocked.sample());
            if (destination == null) {
                beginFinish(Ae2ResourceSupply.Status.FAILED, "inventory_full",
                        "no compatible inventory slot can receive the exact AE2 extraction");
                return;
            }
            int batch = Math.min(
                    Math.min(remaining, (int) Math.min(Integer.MAX_VALUE, stocked.storedAmount())),
                    Math.min(destination.capacity(), stocked.sample().getMaxStackSize()));
            if (batch <= 0) {
                beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE, "network_stock_changed",
                        "the selected AE2 entry no longer has an extractable exact batch");
                return;
            }
            exactExtraction = new ExactExtraction(
                    stocked.itemId(), stocked.serial(), stocked.sample(), groupIndex, allocationIndex,
                    destination.inventorySlot(), destination.menuSlot(), destination.before(),
                    batch, inventoryCount(stocked.itemId()));
            setPhase(Phase.COLLECT_EXACT);
            return;
        }
        Ae2ReflectionBridge.Entry craftable = matching.stream()
                .filter(Ae2ReflectionBridge.Entry::craftable)
                .min(Comparator.comparingLong(Ae2ReflectionBridge.Entry::serial)).orElse(null);
        if (allocation.craftingAllowed() && craftable != null
                && capacityFor(allocation.sample()) >= remaining) {
            craftingMissing = remaining;
            craftingItemId = craftable.itemId();
            craftingSample = allocation.sample().copyWithCount(1);
            craftingPlanGroupIndex = groupIndex;
            craftingPlanAllocationIndex = allocationIndex;
            boolean submitted = submitProtocol(
                    context,
                    "ae2_open_craft_amount",
                    () -> bridge.startAutoCraft(menu, craftable.serial()),
                    fresh -> {
                        Object active = fresh.player().containerMenu;
                        if (bridge.isCraftAmountMenu(active)) {
                            return NativeConfirmation.Verdict.APPLIED;
                        }
                        return active == menu
                                ? NativeConfirmation.Verdict.PENDING
                                : NativeConfirmation.Verdict.DIVERGED;
                    });
            if (submitted) {
                effectsStarted = true;
                setPhase(Phase.WAIT_CRAFT_AMOUNT);
            }
            return;
        }
        beginFinish(Ae2ResourceSupply.Status.FAILED, "resource_plan_changed",
                "the bound AE2 candidate can no longer complete its planned allocation");
    }

    /** Prepare complete network stock, including confirmed auto-crafting, without extraction. */
    private void processPreparedItem(
            LocalPlayerContext context, List<Ae2ReflectionBridge.Entry> entries) {
        Ae2SupplyPlanner.PlannedGroup plannedGroup = plan.groups().get(groupIndex);
        if (plannedGroup.confirmedCount() == plannedGroup.group().count()) {
            clearCraftingTarget();
            groupIndex++;
            return;
        }
        int allocationIndex = -1;
        for (int index = 0; index < plannedGroup.allocations().size(); index++) {
            if (plannedGroup.allocations().get(index).remaining() > 0) {
                allocationIndex = index;
                break;
            }
        }
        if (allocationIndex < 0) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "internal_state_invalid",
                    "the prepared AE2 allocation is missing before the group is complete");
            return;
        }
        Ae2SupplyPlanner.Allocation allocation = plannedGroup.allocations().get(allocationIndex);
        List<Ae2ReflectionBridge.Entry> matching =
                Ae2SupplyPlanner.matchingEntries(entries, allocation);
        long stocked = matching.stream().mapToLong(Ae2ReflectionBridge.Entry::storedAmount).sum();
        int remaining = allocation.remaining();
        if (stocked >= remaining) {
            allocation.confirm(remaining);
            return;
        }
        Ae2ReflectionBridge.Entry craftable = matching.stream()
                .filter(Ae2ReflectionBridge.Entry::craftable)
                .min(Comparator.comparingLong(Ae2ReflectionBridge.Entry::serial)).orElse(null);
        if (!allocation.craftingAllowed() || craftable == null) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "network_stock_changed",
                    "the AE2 network can no longer prepare the complete material ledger");
            return;
        }
        craftingMissing = (int) Math.min(Integer.MAX_VALUE, remaining - stocked);
        craftingItemId = craftable.itemId();
        craftingSample = allocation.sample().copyWithCount(1);
        craftingPlanGroupIndex = groupIndex;
        craftingPlanAllocationIndex = allocationIndex;
        Object menu = storageMenuOrFail();
        if (menu == null) return;
        boolean submitted = submitProtocol(
                context,
                "ae2_open_craft_amount",
                () -> bridge.startAutoCraft(menu, craftable.serial()),
                fresh -> {
                    Object active = fresh.player().containerMenu;
                    if (bridge.isCraftAmountMenu(active)) return NativeConfirmation.Verdict.APPLIED;
                    return active == menu
                            ? NativeConfirmation.Verdict.PENDING
                            : NativeConfirmation.Verdict.DIVERGED;
                });
        if (submitted) {
            effectsStarted = true;
            setPhase(Phase.WAIT_CRAFT_AMOUNT);
        }
    }

    private void collectExact(LocalPlayerContext context) {
        ExactExtraction extraction = requireExtraction();
        if (extraction == null) return;
        Object menu = storageMenuOrFail();
        if (menu == null) return;
        if (!extraction.planValidated) {
            List<Ae2ReflectionBridge.Entry> entries = bridge.entries(menu);
            if (entries == null) {
                exactExtraction = null;
                setPhase(Phase.WAIT_REPOSITORY);
                return;
            }
            String issue = Ae2SupplyPlanner.issue(
                    plan, player, entries, reservedInventorySlots(), this::groupProgress);
            if (issue != null) {
                if (!effectsStarted && installPlan(entries)) setPhase(Phase.PROCESS_ITEM);
                else beginFinish(Ae2ResourceSupply.Status.FAILED, "resource_plan_changed",
                        "the bound extraction changed before submission: " + issue);
                return;
            }
            boolean entryBound = entries.stream().anyMatch(entry ->
                    entry.serial() == extraction.serial
                            && entry.itemId().equals(extraction.itemId)
                            && entry.storedAmount() > 0
                            && same(entry.sample(), extraction.sample));
            if (!entryBound) {
                exactExtraction = null;
                setPhase(Phase.PROCESS_ITEM);
                return;
            }
            extraction.planValidated = true;
        }
        ItemStack carried = player.containerMenu.getCarried();
        if (!exactCarriedMatches(carried, extraction.sample, extraction.collected)) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "inventory_cursor_changed",
                    "the cursor no longer matches the exact AE2 extraction batch");
            return;
        }
        if (extraction.collected >= extraction.batch) {
            setPhase(Phase.PLACE_EXACT);
            return;
        }
        int beforeCount = extraction.collected;
        boolean submitted = submitProtocol(
                context,
                "ae2_extract_single",
                () -> bridge.pickupSingle(menu, extraction.serial),
                fresh -> {
                    Object active = fresh.player().containerMenu;
                    if (!bridge.isStorageMenu(active)) {
                        return NativeConfirmation.Verdict.DIVERGED;
                    }
                    ItemStack freshCarried = fresh.player().containerMenu.getCarried();
                    if (exactCarriedMatches(freshCarried, extraction.sample, beforeCount + 1)) {
                        return NativeConfirmation.Verdict.APPLIED;
                    }
                    return exactCarriedMatches(freshCarried, extraction.sample, beforeCount)
                            ? NativeConfirmation.Verdict.PENDING
                            : NativeConfirmation.Verdict.DIVERGED;
                });
        if (submitted) {
            effectsStarted = true;
            setPhase(Phase.WAIT_EXACT_UNIT);
        }
    }

    private void waitExactUnit(LocalPlayerContext context) {
        ExactExtraction extraction = requireExtraction();
        if (extraction == null) return;
        if (!settleNativeReceipt(context, "ae2_extract_unconfirmed")) return;
        ItemStack carried = player.containerMenu.getCarried();
        int expected = extraction.collected + 1;
        if (exactCarriedMatches(carried, extraction.sample, expected)) {
            extraction.collected = expected;
            setPhase(expected == extraction.batch ? Phase.PLACE_EXACT : Phase.COLLECT_EXACT);
            return;
        }
        finishUncertain("ae2_extract_diverged",
                "AE2 confirmed extraction without the expected exact cursor amount");
    }

    private void placeExact(LocalPlayerContext context) {
        ExactExtraction extraction = requireExtraction();
        if (extraction == null) return;
        AbstractContainerMenu menu = player.containerMenu;
        if (!bridge.isStorageMenu(menu)
                || !exactCarriedMatches(menu.getCarried(), extraction.sample, extraction.batch)) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "inventory_cursor_changed",
                    "the exact AE2 cursor batch changed before inventory placement");
            return;
        }
        if (!same(player.getInventory().getItem(extraction.inventorySlot), extraction.destinationBefore)
                || menuSlotForInventory(menu, extraction.inventorySlot) != extraction.menuSlot) {
            beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE, "inventory_changed",
                    "the reserved inventory destination changed during exact extraction");
            return;
        }
        menuReceipt = context.menus().click(
                context, extraction.menuSlot, 0, ClickType.PICKUP,
                (fresh, ignored) -> {
                    int delta = inventoryCount(extraction.itemId) - extraction.inventoryCountBefore;
                    ItemStack cursor = fresh.player().containerMenu.getCarried();
                    if (cursor.isEmpty() && delta == extraction.batch) {
                        return MenuConfirmation.Verdict.APPLIED;
                    }
                    if (delta > extraction.batch || delta < 0
                            || !exactCarriedMatches(cursor, extraction.sample, extraction.batch)) {
                        return MenuConfirmation.Verdict.DIVERGED;
                    }
                    return MenuConfirmation.Verdict.PENDING;
                }, INVENTORY_CONFIRM_TICKS);
        setPhase(Phase.WAIT_EXACT_PLACE);
    }

    private void waitExactPlace(LocalPlayerContext context) {
        ExactExtraction extraction = requireExtraction();
        if (extraction == null) return;
        if (!settleMenuReceipt(context, "inventory_delta_unconfirmed")) return;
        int delta = inventoryCount(extraction.itemId) - extraction.inventoryCountBefore;
        if (!player.containerMenu.getCarried().isEmpty() || delta != extraction.batch) {
            finishUncertain("inventory_delta_unconfirmed",
                    "the exact AE2 placement did not become the approved inventory delta");
            return;
        }
        Ae2SupplyPlanner.Allocation allocation = plan.groups().get(extraction.planGroupIndex)
                .allocations().get(extraction.planAllocationIndex);
        if (extraction.planGroupIndex != groupIndex
                || !allocation.itemId().equals(extraction.itemId)
                || !same(allocation.sample(), extraction.sample)) {
            finishUncertain("resource_plan_changed",
                    "the confirmed AE2 extraction no longer matches its bound allocation");
            return;
        }
        allocation.confirm(extraction.batch);
        Ae2ResourceSupply.Group group = plan.groups().get(extraction.planGroupIndex).group();
        if (group.selectionMode() == Ae2ResourceSupply.SelectionMode.SINGLE_VARIANT
                && !extraction.itemId.equals(lockedVariantByGroup.get(group.itemId()))) {
            finishUncertain("single_variant_conflict",
                    "the confirmed AE2 extraction violated its locked concrete variant");
            return;
        }
        if (allocation.remaining() == 0
                && craftingPlanGroupIndex == extraction.planGroupIndex
                && craftingPlanAllocationIndex == extraction.planAllocationIndex) {
            clearCraftingTarget();
        }
        exactExtraction = null;
        setPhase(Phase.PROCESS_ITEM);
    }
