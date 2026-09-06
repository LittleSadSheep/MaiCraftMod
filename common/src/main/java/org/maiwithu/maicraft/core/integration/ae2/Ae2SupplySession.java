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
import org.maiwithu.maicraft.client.actor.DefaultBodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.MenuConfirmation;
import org.maiwithu.maicraft.client.actor.MenuReceipt;
import org.maiwithu.maicraft.client.actor.MenuVisibility;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;

/** Complete first-person, cross-tick AE2 supply transaction. */
final class Ae2SupplySession implements Ae2ResourceSupply.Session {
    private enum Phase {
        START,
        STAGE,
        WAIT_STAGE,
        CLOSE_STAGE,
        WAIT_STAGE_CLOSE,
        WAIT_SELECT,
        DISCOVER_FIXED,
        NAVIGATE_FIXED,
        FACE_FIXED,
        OPEN_FIXED,
        OPEN_WIRELESS,
        WAIT_OPEN,
        WAIT_REPOSITORY,
        PROCESS_ITEM,
        FILL_WATER_BUCKET,
        WAIT_WATER_BUCKET,
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
    private Ae2TerminalAccess.Discovery discovery;
    private boolean discoveryAttempted;
    private String fixedDiscoverySummary = "";
    private boolean movedForFixedTerminal;
    private PlayerNav navigation;
    private InventorySwap inventorySwap;
    private boolean inventoryGuiOwned;
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
    private boolean waterBucketRoute;
    private Ae2WaterBucketFill pendingWaterFill;
    private final List<Map<String, Object>> waterFillReceipts = new ArrayList<>();

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
                case STAGE -> stage(context);
                case WAIT_STAGE -> waitStage(context);
                case CLOSE_STAGE -> closeStage(context);
                case WAIT_STAGE_CLOSE -> waitStageClose(context);
                case WAIT_SELECT -> waitSelect(context);
                case DISCOVER_FIXED -> discoverFixed(context);
                case NAVIGATE_FIXED -> navigateFixed();
                case FACE_FIXED -> faceFixed(context);
                case OPEN_FIXED -> openFixed(context);
                case OPEN_WIRELESS -> openWireless(context);
                case WAIT_OPEN -> waitOpen(context);
                case WAIT_REPOSITORY -> waitRepository();
                case PROCESS_ITEM -> processItem(context);
                case FILL_WATER_BUCKET -> fillWaterBucket(context);
                case WAIT_WATER_BUCKET -> waitWaterBucket(context);
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
    public boolean livenessActive() {
        if (craftingJobEffectPending
                || nativeReceipt != null && !nativeReceipt.terminal()
                || menuReceipt != null && !menuReceipt.terminal()) {
            return true;
        }
        return navigation != null
                && (navigation.hasRecentPhysicalProgress(100) || navigation.planningInFlight());
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
        if (ownsOpenMenu(context)) {
            try {
                // Vanilla close returns a carried cursor stack through the authoritative menu path.
                MenuReceipt closeReceipt = context.menus().closeForTaskBoundary(context, 20, "AE2 supply cancelled");
                menuReceipt = closeReceipt;
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
        if (!worldAccessAvailable(context)) {
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
        if (remembered != null && !Ae2TerminalAccess.isLoaded(player, remembered.position())) {
            // Unloaded is not evidence of removal. Travel to the last verified access point; the
            // terminal is authoritatively revalidated only after its chunk becomes loaded.
            fixedCandidates = Ae2TerminalAccess.targetsFor(
                    player, remembered.position(), remembered.side());
        } else if (remembered != null && Ae2TerminalAccess.stillPresent(
                player, bridge, remembered.position(), remembered.side())) {
            fixedCandidates = Ae2TerminalAccess.targetsFor(
                    player, remembered.position(), remembered.side());
        } else {
            if (remembered != null) Ae2TerminalAccess.discard(remembered);
            remembered = null;
            beginDiscovery();
            return;
        }
        prepareFixedAccess(context);
    }

    private void beginDiscovery() {
        discoveryAttempted = true;
        terminalAccess = "fixed_terminal_discovery";
        discovery = new Ae2TerminalAccess.Discovery(player, bridge);
        setPhase(Phase.DISCOVER_FIXED);
    }

    private void discoverFixed(LocalPlayerContext context) {
        if (!discovery.tick()) return;
        fixedCandidates = discovery.result().stream()
                .flatMap(found -> Ae2TerminalAccess.targetsFor(player, found.position(), found.side()).stream())
                .distinct().toList();
        fixedDiscoverySummary = discovery.summary();
        discovery = null;
        prepareFixedAccess(context);
    }

    private void prepareFixedAccess(LocalPlayerContext context) {
        if (fixedCandidates.isEmpty()) {
            finishNow(Ae2ResourceSupply.Status.FAILED, "fixed_terminal_not_found",
                    "no loaded fixed AE2 terminal with an interaction approach was observed nearby; "
                            + fixedDiscoverySummary, false);
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
        setPhase(Phase.STAGE);
    }

    private void stage(LocalPlayerContext context) {
        inventoryGuiOwned = true;
        if (!context.menus().ensureVisible(context)) return;
        if (!same(player.getInventory().getItem(inventorySwap.sourceSlot()), inventorySwap.sourceBefore())
                || !same(player.getInventory().getItem(inventorySwap.hotbarSlot()), inventorySwap.hotbarBefore())) {
            inventorySwap = null;
            beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE, "inventory_changed_before_stage",
                    "the terminal staging slots changed while the inventory GUI was opening");
            return;
        }
        menuReceipt = context.menus().swapInventoryToHotbar(
                context, inventorySwap.sourceSlot(), inventorySwap.hotbarSlot(), INVENTORY_CONFIRM_TICKS);
        setPhase(Phase.WAIT_STAGE);
    }

    private void waitStage(LocalPlayerContext context) {
        if (!settleMenuReceipt(context, "inventory_stage_unconfirmed")) return;
        setPhase(Phase.CLOSE_STAGE);
    }

    private void closeStage(LocalPlayerContext context) {
        if (!ownsOpenMenu(context)) {
            finishCleanupFailure("staging_screen_changed");
            return;
        }
        menuReceipt = context.menus().close(context, INVENTORY_CONFIRM_TICKS);
        setPhase(Phase.WAIT_STAGE_CLOSE);
    }

    private void waitStageClose(LocalPlayerContext context) {
        if (!settleMenuReceipt(context, "inventory_stage_close_unconfirmed")) return;
        inventoryGuiOwned = false;
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
            NavGoal goal = fixedGoal(fixedCandidates);
            navigation = PlayerNav.toGoal(
                    player,
                    () -> goal,
                    1.0,
                    () -> fixedCandidates.stream()
                            .anyMatch(target -> target.approach().equals(player.blockPosition())));
        }
        PlayerNav.Status status = navigation.tick();
        // Cleanup responsibility begins as soon as navigation has physically displaced the body,
        // not only after a terminal is successfully revalidated. A stale remembered terminal or
        // a path failure must still return the player to the semantic caller's worksite.
        movedForFixedTerminal |= !callerOrigin.equals(player.blockPosition());
        if (status == PlayerNav.Status.RUNNING) return;
        if (status == PlayerNav.Status.FAILED) {
            String reason = navigation.failReason();
            stopNavigation();
            if (!discoveryAttempted) {
                beginDiscovery();
                return;
            }
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
        setPhase(Phase.FACE_FIXED);
    }

    static NavGoal fixedGoal(List<Ae2TerminalAccess.FixedTarget> candidates) {
        return NavGoal.composite(candidates.stream().map(target -> NavGoal.exact(target.approach())).toList());
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
        if (!worldAccessAvailable(context)) {
            beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE, "screen_open", "another GUI interrupted terminal opening");
            return;
        }
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
        if (!worldAccessAvailable(context)) {
            beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE, "screen_open", "another GUI interrupted terminal opening");
            return;
        }
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
        if (Ae2WaterBucketFill.supports(request)
                && Ae2WaterBucketFill.count(entries, Ae2WaterBucketFill.WATER_BUCKET) < request.groups().getFirst().count()) {
            var water = bridge.waterEntry(menu);
            if (water != null && water.storedAmount() >= water.bucketUnits()
                    && Ae2WaterBucketFill.count(entries, Ae2WaterBucketFill.EMPTY_BUCKET) > 0) {
                waterBucketRoute = true;
                baseline = inventoryCounts();
                lockedVariantByGroup.put(Ae2WaterBucketFill.WATER_BUCKET, Ae2WaterBucketFill.WATER_BUCKET);
                if (fixedTarget != null) Ae2TerminalAccess.remember(player, fixedTarget);
                setPhase(Phase.FILL_WATER_BUCKET);
                return;
            }
        }
        if (!installPlan(entries)) return;
        if (fixedTarget != null) Ae2TerminalAccess.remember(player, fixedTarget);
        setPhase(Phase.PROCESS_ITEM);
    }

    private void fillWaterBucket(LocalPlayerContext context) {
        var group = request.groups().getFirst();
        if (waterFillReceipts.size() == group.count()) {
            beginFinish(Ae2ResourceSupply.Status.SUCCEEDED, "water_buckets_filled",
                    "AE2 filled the approved water buckets from stored water and empty buckets");
            return;
        }
        Object menu = storageMenuOrFail();
        if (menu == null) return;
        var entries = bridge.entries(menu);
        var water = bridge.waterEntry(menu);
        if (entries == null) return;
        long emptyBuckets = Ae2WaterBucketFill.count(entries, Ae2WaterBucketFill.EMPTY_BUCKET);
        if (!bridge.connected(menu) || water == null || water.storedAmount() < water.bucketUnits() || emptyBuckets < 1) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "water_container_stock_missing",
                    "AE2 needs stored water and a stored empty bucket for native filling");
            return;
        }
        int destination = player.getInventory().getFreeSlot();
        if (destination < 0 || reservedInventorySlots().contains(destination)) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "water_bucket_destination_unavailable",
                    "the native bucket destination must be a free, unreserved main-inventory slot");
            return;
        }
        if (!player.containerMenu.getCarried().isEmpty() || groupProgress(group) != waterFillReceipts.size()) {
            finishUncertain("water_bucket_inventory_changed", "inventory changed before the next exact water fill");
            return;
        }
        var before = new Ae2WaterBucketFill(inventoryCount(Ae2WaterBucketFill.WATER_BUCKET),
                inventoryCount(Ae2WaterBucketFill.EMPTY_BUCKET), emptyBuckets, water.storedAmount(), water.bucketUnits());
        boolean submitted = submitProtocol(context, "ae2_fill_water_bucket",
                () -> bridge.fillContainerToPlayer(menu, water.serial()), fresh -> {
                    if (fresh.player().containerMenu != menu) return NativeConfirmation.Verdict.DIVERGED;
                    var afterEntries = bridge.entries(menu);
                    Long afterWater = bridge.storedAmount(menu, water.serial());
                    if (afterEntries == null || afterWater == null) return NativeConfirmation.Verdict.PENDING;
                    return before.observe(inventoryCount(Ae2WaterBucketFill.WATER_BUCKET),
                            inventoryCount(Ae2WaterBucketFill.EMPTY_BUCKET),
                            Ae2WaterBucketFill.count(afterEntries, Ae2WaterBucketFill.EMPTY_BUCKET),
                            afterWater, fresh.player().containerMenu.getCarried().isEmpty());
                });
        if (submitted) {
            effectsStarted = true;
            pendingWaterFill = before;
            setPhase(Phase.WAIT_WATER_BUCKET);
        }
    }

    private void waitWaterBucket(LocalPlayerContext context) {
        if (!settleNativeReceipt(context, "water_bucket_fill_unconfirmed")) return;
        waterFillReceipts.add(pendingWaterFill.confirmedData());
        pendingWaterFill = null;
        setPhase(Phase.FILL_WATER_BUCKET);
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
        if (!context.menus().ensureVisible(context)) return;
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
        if (!context.menus().ensureVisible(context)) return;
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

    private void waitCraftAmount(LocalPlayerContext context) {
        if (!settleNativeReceipt(context, "craft_amount_menu_unconfirmed")) return;
        if (!bridge.isCraftAmountMenu(player.containerMenu)) {
            finishUncertain("craft_amount_menu_diverged",
                    "AE2 confirmed craft-amount opening without the expected menu");
            return;
        }
        setPhase(Phase.SUBMIT_CRAFT_AMOUNT);
    }

    private void submitCraftAmount(LocalPlayerContext context) {
        Object amountMenu = player.containerMenu;
        if (!bridge.isCraftAmountMenu(amountMenu)) {
            beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE, "craft_menu_changed",
                    "the AE2 craft-amount menu changed before confirmation");
            return;
        }
        boolean submitted = submitProtocol(
                context,
                "ae2_confirm_craft_amount",
                () -> bridge.confirmCraftAmount(amountMenu, craftingMissing),
                fresh -> {
                    Object active = fresh.player().containerMenu;
                    if (bridge.isCraftConfirmMenu(active)) {
                        return NativeConfirmation.Verdict.APPLIED;
                    }
                    return active == amountMenu
                            ? NativeConfirmation.Verdict.PENDING
                            : NativeConfirmation.Verdict.DIVERGED;
                });
        if (submitted) {
            craftingRequests++;
            setPhase(Phase.WAIT_CRAFT_CONFIRM);
        }
    }

    private void waitCraftConfirm(LocalPlayerContext context) {
        if (!settleNativeReceipt(context, "craft_confirm_menu_unconfirmed")) return;
        if (!bridge.isCraftConfirmMenu(player.containerMenu)) {
            finishUncertain("craft_confirm_menu_diverged",
                    "AE2 confirmed the craft amount without the expected confirmation menu");
            return;
        }
        setPhase(Phase.WAIT_CRAFT_PLAN);
    }

    private void waitCraftPlan(LocalPlayerContext context) {
        Object menu = player.containerMenu;
        if (!bridge.isCraftConfirmMenu(menu)) {
            beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE, "craft_menu_changed",
                    "the AE2 crafting confirmation menu changed before job submission");
            return;
        }
        String submitFailure = bridge.craftConfirmSubmitFailure(menu);
        if (submitFailure != null) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "crafting_submit_failed",
                    "AE2 rejected the crafting plan: " + submitFailure);
            return;
        }
        if (!bridge.craftConfirmPlanReady(menu)) {
            if (phaseTicks > CRAFT_JOB_CONFIRM_TICKS) {
                beginFinish(Ae2ResourceSupply.Status.FAILED, "crafting_job_unconfirmed",
                        "AE2 did not synchronize a craftable plan before the deadline");
            }
            return;
        }
        if (bridge.craftConfirmHasNoCpu(menu)) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "crafting_cpu_unavailable",
                    "AE2 could not assign a crafting CPU to the requested job");
            return;
        }
        craftingSubmitFailure = null;
        boolean submitted = submitProtocol(
                context,
                "ae2_submit_crafting_job",
                () -> bridge.startCraftingJob(menu),
                fresh -> {
                    Object active = fresh.player().containerMenu;
                    if (bridge.isStorageMenu(active)) {
                        return NativeConfirmation.Verdict.APPLIED;
                    }
                    if (bridge.isCraftConfirmMenu(active)) {
                        String failure = bridge.craftConfirmSubmitFailure(active);
                        if (failure != null) {
                            craftingSubmitFailure = failure;
                            return NativeConfirmation.Verdict.NOT_APPLIED;
                        }
                        return NativeConfirmation.Verdict.PENDING;
                    }
                    return NativeConfirmation.Verdict.DIVERGED;
                });
        if (submitted) {
            effectsStarted = true;
            craftingJobsSubmitted++;
            craftingJobEffectPending = true;
            setPhase(Phase.WAIT_CRAFT_RETURN);
        }
    }

    private void waitCraftReturn(LocalPlayerContext context) {
        NativeActionReceipt receipt = nativeReceipt;
        if (receipt == null) {
            finishUncertain("transaction_state_missing",
                    "the AE2 crafting-job actor receipt is missing");
            return;
        }
        if (!receipt.terminal()) {
            receipt = context.actions().poll(context, receipt);
            nativeReceipt = receipt;
        }
        if (!receipt.terminal()) return;
        nativeReceipt = null;
        if (receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            if (!bridge.isStorageMenu(player.containerMenu)) {
                finishUncertain("crafting_submit_diverged",
                        "AE2 confirmed job submission without returning to storage");
                return;
            }
            setPhase(Phase.WAIT_CRAFT_STOCK);
            return;
        }
        if (receipt.status() == NativeActionReceipt.Status.CONFIRMED_NOT_APPLIED) {
            craftingJobEffectPending = false;
            beginFinish(Ae2ResourceSupply.Status.FAILED, "crafting_submit_failed",
                    "AE2 rejected the submitted crafting job: "
                            + (craftingSubmitFailure == null
                            ? receipt.detail() : craftingSubmitFailure));
            return;
        }
        finishUncertain("crafting_submit_unconfirmed", receipt.detail());
    }

    private void waitCraftStock() {
        if (craftingItemId == null || craftingSample.isEmpty()) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "internal_state_invalid",
                    "the concrete AE2 crafting target is missing");
            return;
        }
        if (plan == null
                || craftingPlanGroupIndex < 0
                || craftingPlanGroupIndex >= plan.groups().size()
                || craftingPlanAllocationIndex < 0
                || craftingPlanAllocationIndex
                >= plan.groups().get(craftingPlanGroupIndex).allocations().size()) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "resource_plan_changed",
                    "the submitted AE2 crafting allocation is no longer bound to the request");
            return;
        }
        Ae2SupplyPlanner.Allocation allocation = plan.groups().get(craftingPlanGroupIndex)
                .allocations().get(craftingPlanAllocationIndex);
        if (groupIndex != craftingPlanGroupIndex
                || !allocation.itemId().equals(craftingItemId)
                || !same(allocation.sample(), craftingSample)) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "resource_plan_changed",
                    "the submitted AE2 crafting target no longer matches its bound allocation");
            return;
        }
        Object menu = storageMenuOrFail();
        if (menu == null) return;
        if (!bridge.connected(menu)) {
            beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE,
                    "ae2_network_disconnected", "the AE2 network disconnected while crafting");
            return;
        }
        List<Ae2ReflectionBridge.Entry> entries = bridge.entries(menu);
        long observed = entries == null ? 0L : entries.stream()
                .filter(entry -> entry.itemId().equals(craftingItemId)
                        && same(entry.sample(), craftingSample))
                .mapToLong(Ae2ReflectionBridge.Entry::storedAmount).sum();
        boolean ready = request.operation() == Ae2ResourceSupply.Operation.PREPARE
                ? observed >= allocation.remaining() : observed > 0;
        if (ready) {
            craftingJobEffectPending = false;
            setPhase(Phase.PROCESS_ITEM);
            return;
        }
        // A confirmed AE crafting job may legitimately run for an arbitrarily long time. Its
        // submission is an acknowledged in-flight effect, so the scheduler renews a liveness
        // lease while this phase keeps observing network stock. Cancellation remains explicit;
        // never resubmit merely because no output has appeared yet.
    }

    private void beginFinish(
            Ae2ResourceSupply.Status status, String code, String message) {
        if (terminal != null || pendingTerminal != null) return;
        if (nativeReceipt != null && !nativeReceipt.terminal() || craftingJobEffectPending) {
            finishUncertain(code + "_outcome_uncertain",
                    message + "; an AE2 server transaction is still unconfirmed");
            return;
        }
        pendingTerminal = new PendingTerminal(status, code, message);
        stopNavigation();
        if (!player.containerMenu.getCarried().isEmpty()
                && bridge.isStorageMenu(player.containerMenu)) {
            setPhase(Phase.CLEAN_RETURN_CURSOR);
        } else {
            setPhase(Phase.CLEAN_CLOSE);
        }
    }

    private void cleanReturnCursor(LocalPlayerContext context) {
        if (!bridge.isStorageMenu(player.containerMenu)) {
            finishCleanupFailure("cursor_return_menu_missing");
            return;
        }
        cleanupCursorBefore = player.containerMenu.getCarried().copy();
        if (cleanupCursorBefore.isEmpty()) {
            setPhase(Phase.CLEAN_CLOSE);
            return;
        }
        Object storageMenu = player.containerMenu;
        boolean submitted = submitProtocol(
                context,
                "ae2_return_cursor",
                () -> bridge.returnCarriedToNetwork(storageMenu),
                fresh -> {
                    Object active = fresh.player().containerMenu;
                    if (active != storageMenu || !bridge.isStorageMenu(active)) {
                        return NativeConfirmation.Verdict.DIVERGED;
                    }
                    ItemStack carried = fresh.player().containerMenu.getCarried();
                    if (carried.isEmpty()) return NativeConfirmation.Verdict.APPLIED;
                    return same(carried, cleanupCursorBefore)
                            ? NativeConfirmation.Verdict.PENDING
                            : NativeConfirmation.Verdict.DIVERGED;
                });
        if (submitted) setPhase(Phase.CLEAN_WAIT_CURSOR_RETURN);
    }

    private void cleanWaitCursorReturn(LocalPlayerContext context) {
        if (!settleNativeReceipt(context, "cursor_return_unconfirmed")) return;
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) {
            exactExtraction = null;
            setPhase(Phase.CLEAN_CLOSE);
            return;
        }
        finishUncertain("cursor_return_diverged",
                "AE2 confirmed cursor return while a carried stack remains");
    }

    private void cleanClose(LocalPlayerContext context) {
        if (worldAccessAvailable(context)) {
            setPhase(Phase.CLEAN_RESTORE);
            return;
        }
        if (!ownsOpenMenu(context)) {
            finishCleanupFailure("unrelated_screen_open");
            return;
        }
        menuReceipt = context.menus().close(context, INVENTORY_CONFIRM_TICKS);
        setPhase(Phase.CLEAN_WAIT_CLOSE);
    }

    private void cleanWaitClose(LocalPlayerContext context) {
        if (!settleMenuReceipt(context, "terminal_close_unconfirmed")) return;
        inventoryGuiOwned = false;
        setPhase(Phase.CLEAN_RESTORE);
    }

    private void cleanRestore(LocalPlayerContext context) {
        InventorySwap swap = inventorySwap;
        if (swap == null) {
            setPhase(Phase.CLEAN_SELECT);
            return;
        }
        if (!DefaultBodyControlPort.permitsWorldMovement(context.minecraft().screen)
                && !MenuVisibility.inventoryVisible(context.minecraft(), player)
                || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()) {
            finishCleanupFailure("inventory_not_ready_for_restore");
            return;
        }
        ItemStack source = player.getInventory().getItem(swap.sourceSlot());
        ItemStack hotbar = player.getInventory().getItem(swap.hotbarSlot());
        if (!same(source, swap.hotbarBefore()) || !stagedItemMatches(hotbar, swap.sourceBefore())) {
            finishCleanupFailure("inventory_changed_before_restore");
            return;
        }
        inventoryGuiOwned = true;
        if (!context.menus().ensureVisible(context)) return;
        menuReceipt = context.menus().swapInventoryToHotbar(
                context, swap.sourceSlot(), swap.hotbarSlot(), INVENTORY_CONFIRM_TICKS);
        setPhase(Phase.CLEAN_WAIT_RESTORE);
    }

    private void cleanWaitRestore(LocalPlayerContext context) {
        if (!settleMenuReceipt(context, "inventory_restore_unconfirmed")) return;
        InventorySwap swap = inventorySwap;
        if (swap == null
                || !stagedItemMatches(player.getInventory().getItem(swap.sourceSlot()), swap.sourceBefore())
                || !same(player.getInventory().getItem(swap.hotbarSlot()), swap.hotbarBefore())) {
            finishUncertain("inventory_restore_diverged",
                    "temporary terminal staging did not restore to the exact authoritative state");
            return;
        }
        inventorySwap = null;
        setPhase(Phase.CLEAN_CLOSE);
    }

    private void cleanSelect(LocalPlayerContext context) {
        if (player.getInventory().selected != originalSelected) {
            requestSelect(context, originalSelected, Phase.CLEAN_SELECT);
            return;
        }
        if (movedForFixedTerminal && !callerOrigin.equals(player.blockPosition())
                && pendingTerminal != null
                && pendingTerminal.status() != Ae2ResourceSupply.Status.CANCELLED) {
            setPhase(Phase.RETURN_ORIGIN);
        } else {
            finishPending();
        }
    }

    private void returnOrigin() {
        if (!player.level().dimension().location().equals(callerDimension)) {
            finishCleanupFailure("return_world_changed");
            return;
        }
        if (navigation == null) {
            // Return to the caller's semantic worksite, not blindly to its exact old feet cell.
            // That cell may have become task-scoped forbidden after an area investigation (for
            // example an unplanted farmland cell). The path graph chooses a real safe stance;
            // the outer material coordinator uses the same radius-two return contract.
            navigation = PlayerNav.toGoal(
                    player, () -> NavGoal.near(callerOrigin, RETURN_RADIUS), 1.0,
                    () -> player.blockPosition().distSqr(callerOrigin)
                            <= RETURN_RADIUS * RETURN_RADIUS);
        }
        PlayerNav.Status status = navigation.tick();
        if (status == PlayerNav.Status.RUNNING) return;
        if (status == PlayerNav.Status.FAILED) {
            String detail = navigation.failReason();
            stopNavigation();
            finishCleanupFailure("return_path_blocked: " + detail);
            return;
        }
        stopNavigation();
        finishPending();
    }

    private void finishPending() {
        PendingTerminal pending = pendingTerminal;
        if (pending == null) {
            finishNow(Ae2ResourceSupply.Status.FAILED, "internal_state_invalid",
                    "AE2 cleanup reached no terminal result", effectsStarted);
            return;
        }
        if (pending.status() == Ae2ResourceSupply.Status.SUCCEEDED && !finalAuditPasses()) {
            finishNow(Ae2ResourceSupply.Status.UNCERTAIN, "final_exact_audit_failed",
                    "the final inventory audit did not exactly match the approved AE2 request", true);
            return;
        }
        finishNow(pending.status(), pending.code(), pending.message(), false);
    }

    private void finishCleanupFailure(String detail) {
        PendingTerminal pending = pendingTerminal;
        Ae2ResourceSupply.Status status = effectsStarted
                ? Ae2ResourceSupply.Status.UNCERTAIN : Ae2ResourceSupply.Status.FAILED;
        String message = "AE2 cleanup could not be confirmed: " + detail;
        if (pending != null && !pending.message().isBlank()) message = pending.message() + "; " + message;
        finishNow(status, "cleanup_unconfirmed", message, effectsStarted);
    }

    private void finishUncertain(String code, String message) {
        stopNavigation();
        try {
            org.maiwithu.maicraft.client.runtime.ClientRuntime.requireContext(player)
                    .body().releaseAll();
        } catch (RuntimeException ignored) {
            // Body/control epoch may already have ended; no compensating packet is submitted.
        }
        finishNow(Ae2ResourceSupply.Status.UNCERTAIN, code, message, true);
    }

    private void finishNow(
            Ae2ResourceSupply.Status status, String code, String message, boolean uncertain) {
        if (terminal != null) return;
        try {
            var context = org.maiwithu.maicraft.client.runtime.ClientRuntime.requireContext(player);
            if (ownsOpenMenu(context) && (menuReceipt == null || menuReceipt.terminal()
                    || menuReceipt.kind() != MenuReceipt.Kind.CLOSE)) {
                context.menus().closeForTaskBoundary(context, INVENTORY_CONFIRM_TICKS,
                        "AE2 supply session ended: " + code);
            }
        } catch (RuntimeException unavailable) { /* Body/control handoff owns the old menu. */ }
        terminal = new Ae2ResourceSupply.Outcome(
                status, code, message, groupDeltas(), request.operation(), request.allowCrafting(),
                craftingRequests, craftingJobsSubmitted, effectsStarted,
                uncertain, terminalAccess, List.copyOf(waterFillReceipts));
        phase = Phase.FINISHED;
    }

    private boolean settleNativeReceipt(LocalPlayerContext context, String code) {
        NativeActionReceipt receipt = nativeReceipt;
        if (receipt == null) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "internal_state_invalid",
                    "the pending native AE2 action receipt is missing");
            return false;
        }
        if (!receipt.terminal()) {
            receipt = context.actions().poll(context, receipt);
            nativeReceipt = receipt;
        }
        if (!receipt.terminal()) return false;
        nativeReceipt = null;
        if (receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED) return true;
        if (receipt.status() == NativeActionReceipt.Status.CONFIRMED_NOT_APPLIED
                || receipt.status() == NativeActionReceipt.Status.CANCELLED) {
            beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE, code, receipt.detail());
        } else {
            finishUncertain(code, receipt.detail());
        }
        return false;
    }

    private boolean settleMenuReceipt(LocalPlayerContext context, String code) {
        MenuReceipt receipt = menuReceipt;
        if (receipt == null) {
            beginFinish(Ae2ResourceSupply.Status.FAILED, "internal_state_invalid",
                    "the pending AE2 menu receipt is missing");
            return false;
        }
        if (!receipt.terminal()) {
            receipt = context.menus().poll(context, receipt);
            menuReceipt = receipt;
        }
        if (!receipt.terminal()) return false;
        menuReceipt = null;
        if (receipt.status() == MenuReceipt.Status.CONFIRMED_APPLIED) return true;
        if (receipt.status() == MenuReceipt.Status.CONFIRMED_NOT_APPLIED) {
            beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE, code, receipt.detail());
        } else {
            finishUncertain(code, receipt.detail());
        }
        return false;
    }

    private boolean submitProtocol(
            LocalPlayerContext context,
            String operation,
            Runnable submission,
            NativeConfirmation confirmation) {
        if (!context.menus().ensureVisible(context)) return false;
        if (nativeReceipt != null) {
            if (pendingTerminal != null) {
                finishCleanupFailure("overlapping_actor_protocol_receipt");
            } else {
                beginFinish(Ae2ResourceSupply.Status.FAILED, "internal_state_invalid",
                        "AE2 protocol actions cannot overlap one actor receipt");
            }
            return false;
        }
        try {
            nativeReceipt = context.actions().submitProtocol(
                    context, operation, submission, confirmation, PROTOCOL_CONFIRM_TICKS);
            return true;
        } catch (RuntimeException rejectedBeforeSubmission) {
            String detail = "actor rejected AE2 protocol submission before a receipt was created: "
                    + rejectedBeforeSubmission.getMessage();
            if (pendingTerminal != null) {
                finishCleanupFailure(detail);
            } else {
                beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE,
                        "actor_protocol_unavailable", detail);
            }
            return false;
        }
    }

    private Object storageMenuOrFail() {
        Object menu = player.containerMenu;
        var context = org.maiwithu.maicraft.client.runtime.ClientRuntime.requireContext(player);
        if (bridge.isStorageMenu(menu) && MenuVisibility.matches(context.minecraft(), player.containerMenu)) return menu;
        beginFinish(Ae2ResourceSupply.Status.RETRYABLE_FAILURE,
                "ae2_menu_changed", "the AE2 storage GUI is no longer visibly open");
        return null;
    }

    private boolean worldAccessAvailable(LocalPlayerContext context) {
        return DefaultBodyControlPort.permitsWorldMovement(context.minecraft().screen)
                && player.containerMenu == player.inventoryMenu;
    }

    private boolean ownsOpenMenu(LocalPlayerContext context) {
        Object menu = player.containerMenu;
        return MenuVisibility.matches(context.minecraft(), player.containerMenu)
                && (bridge.isStorageMenu(menu) || bridge.isCraftAmountMenu(menu) || bridge.isCraftConfirmMenu(menu)
                || inventoryGuiOwned && menu == player.inventoryMenu);
    }

    private ExactExtraction requireExtraction() {
        if (exactExtraction != null) return exactExtraction;
        beginFinish(Ae2ResourceSupply.Status.FAILED, "internal_state_invalid",
                "the exact AE2 extraction state is missing");
        return null;
    }

    private Set<Integer> reservedInventorySlots() {
        return inventorySwap == null
                ? Set.of() : Set.of(inventorySwap.sourceSlot(), inventorySwap.hotbarSlot());
    }

    private ExtractionDestination exactDestination(AbstractContainerMenu menu, ItemStack sample) {
        Set<Integer> reserved = reservedInventorySlots();
        List<Integer> ordered = new ArrayList<>();
        for (int slot = 0; slot <= 35; slot++) if (!reserved.contains(slot)) ordered.add(slot);
        ordered.sort(Comparator.comparingInt(slot -> {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && sameKind(stack, sample)) return 0;
            return stack.isEmpty() ? 1 : 2;
        }));
        for (int inventorySlot : ordered) {
            ItemStack before = player.getInventory().getItem(inventorySlot);
            int capacity = before.isEmpty() ? sample.getMaxStackSize()
                    : sameKind(before, sample)
                    ? Math.max(0, before.getMaxStackSize() - before.getCount()) : 0;
            if (capacity <= 0) continue;
            int menuSlot = menuSlotForInventory(menu, inventorySlot);
            if (menuSlot >= 0) {
                return new ExtractionDestination(inventorySlot, menuSlot, before, capacity);
            }
        }
        return null;
    }

    private int menuSlotForInventory(AbstractContainerMenu menu, int inventorySlot) {
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            var slot = menu.getSlot(menuSlot);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == inventorySlot) {
                return menuSlot;
            }
        }
        return -1;
    }

    private int capacityFor(ItemStack sample) {
        int capacity = 0;
        Set<Integer> reserved = reservedInventorySlots();
        for (int slot = 0; slot <= 35; slot++) {
            if (reserved.contains(slot)) continue;
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) capacity += sample.getMaxStackSize();
            else if (sameKind(stack, sample)) {
                capacity += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
        }
        return capacity;
    }

    private Map<ResourceLocation, Integer> inventoryCounts() {
        Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
        Set<ResourceLocation> accepted = request.acceptedItemIds();
        for (int slot = 0; slot <= 35; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (accepted.contains(id)) counts.merge(id, stack.getCount(), Integer::sum);
        }
        return Map.copyOf(counts);
    }

    private int inventoryCount(ResourceLocation itemId) {
        int count = 0;
        for (int slot = 0; slot <= 35; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemId)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int groupProgress(Ae2ResourceSupply.Group group) {
        if (group.selectionMode() == Ae2ResourceSupply.SelectionMode.SINGLE_VARIANT) {
            ResourceLocation selected = lockedVariantByGroup.get(group.itemId());
            return selected == null ? 0
                    : Math.max(0, inventoryCount(selected) - baseline.getOrDefault(selected, 0));
        }
        int before = group.acceptableItemIds().stream()
                .mapToInt(id -> baseline.getOrDefault(id, 0)).sum();
        int after = group.acceptableItemIds().stream().mapToInt(this::inventoryCount).sum();
        return Math.max(0, after - before);
    }

    private List<Ae2ResourceSupply.GroupDelta> groupDeltas() {
        List<Ae2ResourceSupply.GroupDelta> result = new ArrayList<>();
        for (Ae2ResourceSupply.Group group : request.groups()) {
            int before = group.acceptableItemIds().stream()
                    .mapToInt(id -> baseline.getOrDefault(id, 0)).sum();
            int after = group.acceptableItemIds().stream().mapToInt(this::inventoryCount).sum();
            int acquired = groupProgress(group);
            ResourceLocation selected = lockedVariantByGroup.get(group.itemId());
            List<Ae2ResourceSupply.ItemDelta> itemEvidence = group.acceptableItemIds().stream()
                    .map(itemId -> {
                        int itemBefore = baseline.getOrDefault(itemId, 0);
                        int itemAfter = inventoryCount(itemId);
                        return new Ae2ResourceSupply.ItemDelta(
                                itemId,
                                itemBefore,
                                itemAfter,
                                Math.max(0, itemAfter - itemBefore),
                                itemId.equals(selected));
                    })
                    .toList();
            result.add(new Ae2ResourceSupply.GroupDelta(
                    group.itemId(), group.acceptableItemIds(), group.selectionMode(),
                    selected, group.count(), before, after,
                    acquired, Math.max(0, group.count() - acquired), itemEvidence));
        }
        return List.copyOf(result);
    }

    private boolean finalAuditPasses() {
        if (waterBucketRoute) return player.containerMenu.getCarried().isEmpty()
                && waterFillReceipts.size() == request.groups().getFirst().count()
                && groupProgress(request.groups().getFirst()) == waterFillReceipts.size();
        if (request.operation() == Ae2ResourceSupply.Operation.PREPARE) {
            return player.containerMenu.getCarried().isEmpty() && plan != null
                    && plan.groups().size() == request.groups().size()
                    && plan.groups().stream().allMatch(group ->
                            group.confirmedCount() == group.group().count());
        }
        if (!player.containerMenu.getCarried().isEmpty() || plan == null
                || plan.groups().size() != request.groups().size()) return false;
        for (Ae2SupplyPlanner.PlannedGroup group : plan.groups()) {
            if (group.confirmedCount() != group.group().count()
                    || groupProgress(group.group()) != group.group().count()) return false;
        }
        return true;
    }

    private void clearCraftingTarget() {
        craftingMissing = 0;
        craftingItemId = null;
        craftingSample = ItemStack.EMPTY;
        craftingPlanGroupIndex = -1;
        craftingPlanAllocationIndex = -1;
    }

    private int firstEmpty(int from, int to) {
        int upper = Math.min(to, player.getInventory().getContainerSize() - 1);
        for (int slot = Math.max(0, from); slot <= upper; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    private void stopNavigation() {
        if (navigation != null) {
            navigation.stop();
            navigation = null;
        }
    }

    private void setPhase(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private void validateContext(LocalPlayerContext context) {
        context.requireCurrent();
        if (context.player() != player) {
            throw new IllegalStateException("AE2 session belongs to another local-player body");
        }
    }

    private static boolean exactCarriedMatches(ItemStack carried, ItemStack sample, int count) {
        return count == 0 ? carried.isEmpty()
                : !carried.isEmpty() && carried.getCount() == count && sameKind(carried, sample);
    }

    private static boolean same(ItemStack left, ItemStack right) {
        return left.getCount() == right.getCount() && sameKind(left, right);
    }

    private static boolean sameKind(ItemStack left, ItemStack right) {
        return ItemStack.isSameItemSameComponents(left, right);
    }

    /** Wireless charge components may change while its item identity remains the staged object. */
    private static boolean stagedItemMatches(ItemStack actual, ItemStack stagedBefore) {
        if (stagedBefore.isEmpty()) return actual.isEmpty();
        return !actual.isEmpty()
                && BuiltInRegistries.ITEM.getKey(actual.getItem())
                .equals(BuiltInRegistries.ITEM.getKey(stagedBefore.getItem()));
    }

    private static final int INVENTORY_CONFIRM_TICKS = 60;
    private static final int PROTOCOL_CONFIRM_TICKS = 200;
    private static final int TERMINAL_OPEN_TICKS = 100;
    private static final int REPOSITORY_READY_TICKS = 100;
    private static final int CRAFT_JOB_CONFIRM_TICKS = 2_400;
    private static final float LOOK_EPSILON = 1.5f;
    private static final double RETURN_RADIUS = 2.0D;
}
