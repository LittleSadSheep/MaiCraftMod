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
