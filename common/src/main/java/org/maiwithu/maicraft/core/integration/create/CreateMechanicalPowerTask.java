// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.supply.SemanticMaterialSupplyCoordinator;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** One scheduler-owned, receipt-driven first-person construction attempt. */
final class CreateMechanicalPowerTask
        extends AbstractCompanionTask<CreateMechanicalPowerTaskRecord> {
    private static final int PLACEMENT_CONFIRM_TICKS = 40;
    private static final int NETWORK_CONFIRM_TICKS = 80;
    private static final int READY_TICKS = 2;
    private static final float LOOK_EPSILON = 1.5f;
    private static final float MIN_VERTICAL_PITCH = 48.0f;
    /** Renewable liveness window; this is deliberately not a maximum task duration. */
    private static final long PROGRESS_LEASE_TICKS = 2L * 60L * 20L;
    private static final int PROGRESS_GRACE_TICKS = 100;

    private enum Phase {
        PROGRESSIVE,
        RESUME_AUDIT,
        SUPPLY,
        RESTOCK_RESTORE,
        PREPARE,
        NAVIGATE,
        ALIGN,
        WAIT_PLACEMENT,
        VERIFY,
        RESTORE
    }

    private final Map<String, Object> data = new LinkedHashMap<>();
    private final SemanticMaterialSupplyCoordinator supply =
            new SemanticMaterialSupplyCoordinator();
    private final List<Map<String, Object>> supplyReceipts = new ArrayList<>();
    private Map<String, Object> supplyFailure = Map.of();
    private int supplyRounds;
    private int materialBatches;
    private CreateMechanicalPlan plan;
    private CreateMechanicalStager stager;
    private CreateProgressiveSurvey progressiveSurvey;
    private final CreateProgressiveSurvey.Travel constructionTravel =
            new CreateProgressiveSurvey.Travel();
    private Item chainItem;
    private Block chainBlock;
    private Phase phase = Phase.PREPARE;
    private int cursor;
    private int resumeAuditIndex = -1;
    private int invocationStartCursor;
    private int startCursor;
    private int initialInventoryCount;
    private boolean continuingAfterRestock;
    private boolean restoreRetryBlocked;
    private long progressiveProgressRevision;
    private long constructionProgressRevision;
    private int readyTicks;
    private int networkWaitTicks;
    private NativeActionReceipt placementReceipt;
    private BlockPos activeStand;
    private long bodyEpoch;
    private String dimension;
    private String failureCode;
    private String failureDetail;
    private FailureType failureType = FailureType.UNKNOWN;
    private List<String> recoveryOptions = List.of("inspect_facts", "cancel");
    private boolean desiredSuccess;
    private boolean nativeOutcomeUncertain;
    private boolean continuationIssued;
    private Task.StopReason forcedStop;

    CreateMechanicalPowerTask(LocalPlayer player, CreateMechanicalPowerTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        bodyEpoch = context.bodyEpoch();
        dimension = context.level().dimension().location().toString();
        ResourceLocation id;
        id = ResourceLocation.tryParse(CreateMechanicalPower.CHAIN_DRIVE_ID);
        if (id == null) {
            failNow("optional_dependency_unavailable", "the chain-drive registry id is invalid",
                    FailureType.UNSUPPORTED, List.of("cancel"));
            return;
        }
        chainItem = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        chainBlock = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (!(chainItem instanceof BlockItem blockItem) || chainBlock == null
                || chainBlock == Blocks.AIR || blockItem.getBlock() != chainBlock) {
            failNow("optional_dependency_unavailable",
                    "create:encased_chain_drive is not available as a native block item",
                    FailureType.UNSUPPORTED, List.of("install_or_enable_create", "cancel"));
            return;
        }

        if (r.continuationToken == null) {
            CreateMechanicalPlan.Result planned = CreateMechanicalPlanner.plan(player, r.request);
            data.putAll(planned.facts());
            if (planned.plan() == null) {
                if (progressiveEligible(planned.failureCode())) {
                    progressiveSurvey = new CreateProgressiveSurvey(r.request);
                    progressiveProgressRevision = progressiveSurvey.progressRevision();
                    phase = Phase.PROGRESSIVE;
                    renewProgressLease();
                    data.put("progressive_survey", true);
                    return;
                }
                failNow(planned.failureCode(), stringFact(planned.facts(), "detail",
                                "mechanical preflight did not produce a route"),
                        mapFailure(planned.failureCode()), recovery(planned.facts()));
                return;
            }
            plan = planned.plan();
            stager = new CreateMechanicalStager(player);
            cursor = 0;
        } else {
            CreateMechanicalContinuations.Entry continuation =
                    CreateMechanicalContinuations.take(r.continuationToken);
            if (continuation == null) {
                failNow("continuation_unavailable",
                        "the continuation token is unknown, expired, or already consumed",
                        FailureType.TARGET_LOST, List.of("inspect_partial_route", "cancel"));
                return;
            }
            if (!continuation.request().equals(r.request)
                    || continuation.bodyEpoch() != bodyEpoch
                    || !continuation.dimension().equals(dimension)) {
                failNow("continuation_context_changed",
                        "the continuation is bound to a different request, body, or dimension",
                        FailureType.TARGET_LOST, List.of("inspect_partial_route", "cancel"));
                return;
            }
            plan = continuation.plan();
            cursor = continuation.confirmedCells();
            if (!continuation.prefixHash().equals(plan.prefixHash(cursor))) {
                failNow("continuation_corrupt", "the confirmed route prefix identity does not match",
                        FailureType.INTERNAL, List.of("cancel"));
                return;
            }
            stager = continuation.staging() == null
                    ? new CreateMechanicalStager(player)
                    : new CreateMechanicalStager(continuation.staging());
            if (!stager.validateSnapshot(player, chainItem)) {
                failNow("continuation_inventory_changed",
                        "hotbar staging facts changed after the continuation was issued",
                        FailureType.NO_MATERIAL, List.of("inspect_inventory", "cancel"));
                return;
            }
            data.put("resumed_from", r.continuationToken.toString());
            data.put("confirmed_prefix_at_resume", cursor);
            if (plan.progressive()) {
                resumeAuditIndex = 0;
                phase = Phase.RESUME_AUDIT;
                renewProgressLease();
            } else {
                Validation validation = validateWholeRoute(context.level());
                if (validation != null) {
                    failNow(validation.code, validation.detail, validation.type, validation.recovery);
                    return;
                }
            }
        }
        invocationStartCursor = cursor;
        initializeApprovedPlan(false);
    }

    @Override
    protected TaskState onTick() {
        LocalPlayerContext context = ClientRuntime.requireContext(player);
        if (context.bodyEpoch() != bodyEpoch
                || !context.level().dimension().location().toString().equals(dimension)) {
            beginFailure("body_or_dimension_changed",
                    "the local player body or dimension changed during construction",
                    FailureType.TARGET_LOST, List.of("inspect_partial_route", "cancel"), true);
        }
        if (placementReceipt != null || stager != null && stager.hasPendingReceipt()
                || phase == Phase.RESTORE) {
            r.extendDeadlineTo(Math.max(r.getDeadlineGameTime(), player.level().getGameTime() + 2));
        }
        return switch (phase) {
            case PROGRESSIVE -> progressSurvey(context);
            case RESUME_AUDIT -> auditProgressiveContinuation(context);
            case SUPPLY -> tickSupply(context);
            case RESTOCK_RESTORE -> restoreForNextBatch(context);
            case PREPARE -> prepare(context);
            case NAVIGATE -> navigate(context);
            case ALIGN -> alignAndPlace(context);
            case WAIT_PLACEMENT -> settlePlacement(context);
            case VERIFY -> verifyNetwork(context);
            case RESTORE -> restoreAndFinish(context);
        };
    }

    private TaskState auditProgressiveContinuation(LocalPlayerContext context) {
        if (resumeAuditIndex >= cursor) {
            constructionTravel.stop();
            data.put("continuation_prefix_revalidated", cursor);
            phase = Phase.PREPARE;
            return TaskState.RUNNING;
        }
        if (resumeAuditIndex == 0) {
            CreateMechanicalPlan.KineticEndpoint source = plan.source();
            if (!context.level().isLoaded(source.position())) {
                CreateProgressiveSurvey.Travel.Status travel = constructionTravel.tick(
                        player, plan.cells().get(0).stand(), 1);
                observeConstructionTravelProgress();
                if (travel == CreateProgressiveSurvey.Travel.Status.RUNNING) return TaskState.RUNNING;
                if (travel == CreateProgressiveSurvey.Travel.Status.FAILED) {
                    beginFailure("continuation_source_unreachable",
                            "could not return to the progressive source for exact prefix audit: "
                                    + constructionTravel.failure(),
                            FailureType.NO_PATH,
                            List.of("make_path_accessible", "cancel"), false);
                    return TaskState.RUNNING;
                }
                if (!context.level().isLoaded(source.position())) return TaskState.RUNNING;
            }
            CreateKineticsBridge.Facts live = CreateKineticsBridge.inspect(
                    context.level(), source.position());
            if (live == null || !live.powered()
                    || !context.level().getBlockState(source.position()).equals(source.state())) {
                beginFailure("source_changed",
                        "the progressive continuation source changed before prefix audit",
                        FailureType.TARGET_LOST,
                        List.of("restore_source_power", "cancel"), false);
                return TaskState.RUNNING;
            }
        }
        int budget = 48;
        while (resumeAuditIndex < cursor && budget-- > 0) {
            CreateMechanicalPlan.RouteCell cell = plan.cells().get(resumeAuditIndex);
            if (!context.level().isLoaded(cell.position())) {
                CreateProgressiveSurvey.Travel.Status travel = constructionTravel.tick(
                        player, cell.stand(), 1);
                observeConstructionTravelProgress();
                if (travel == CreateProgressiveSurvey.Travel.Status.RUNNING) return TaskState.RUNNING;
                if (travel == CreateProgressiveSurvey.Travel.Status.FAILED) {
                    beginFailure("continuation_prefix_unreachable",
                            "could not reload the next confirmed progressive prefix cell: "
                                    + constructionTravel.failure(),
                            FailureType.NO_PATH,
                            List.of("make_path_accessible", "cancel"), false);
                    return TaskState.RUNNING;
                }
                if (!context.level().isLoaded(cell.position())) return TaskState.RUNNING;
            }
            BlockState state = context.level().getBlockState(cell.position());
            if (state.getBlock() != chainBlock || !"y".equals(axisName(state))) {
                beginFailure("confirmed_prefix_changed",
                        "a confirmed progressive prefix cell changed during exact audit",
                        FailureType.TARGET_LOST,
                        List.of("inspect_partial_route", "repair_manually", "cancel"), false);
                return TaskState.RUNNING;
            }
            resumeAuditIndex++;
            renewProgressLease();
        }
        return TaskState.RUNNING;
    }

    private TaskState progressSurvey(LocalPlayerContext context) {
        CreateProgressiveSurvey.Status status = progressiveSurvey.tick(context);
        observeProgressiveSurveyProgress();
        if (status == CreateProgressiveSurvey.Status.RUNNING) return TaskState.RUNNING;
        if (status == CreateProgressiveSurvey.Status.FAILED) {
            CreateProgressiveSurvey.Failure failure = progressiveSurvey.failure();
            beginFailure(failure.code(), failure.detail(), failure.type(), failure.recovery(), false);
            return TaskState.RUNNING;
        }
        plan = progressiveSurvey.plan();
        renewProgressLease();
        data.put("progressive_survey", true);
        data.put("progressive_travel_segments", progressiveSurvey.travelSegments());
        data.put("progressive_rejected_route_candidates",
                progressiveSurvey.rejectedRouteCandidates());
        data.put("source_loaded_cells", progressiveSurvey.sourceLoadedCells());
        data.put("source_unloaded_cells", progressiveSurvey.sourceUnloadedCells());
        data.put("destination_loaded_cells", progressiveSurvey.destinationLoadedCells());
        data.put("destination_unloaded_cells", progressiveSurvey.destinationUnloadedCells());
        data.put("corridor_survey_digest", progressiveSurvey.surveyDigest());
        data.put("route_hash", plan.routeHash());
        data.put("route_cells", plan.cells().size());
        data.put("numeric_stress_margin_supported", false);
        data.put("stress_evidence",
                "non-zero speed plus optional boolean overstress; numeric capacity/impact margin unavailable");
        data.put("delivery_kind", plan.destinationMachine() == null
                ? "verified_free_receiver" : "kinetic_machine");
        stager = new CreateMechanicalStager(player);
        cursor = 0;
        if (!initializeApprovedPlan(true)) return TaskState.RUNNING;
        phase = Phase.PREPARE;
        return TaskState.RUNNING;
    }

    private boolean initializeApprovedPlan(boolean duringTick) {
        startCursor = cursor;
        initialInventoryCount = inventoryCount(player, chainItem);
        int remaining = plan.cells().size() - cursor;
        int carryingCapacity = carryingCapacity(player, chainItem);
        int desiredBatch = Math.min(remaining, carryingCapacity);
        data.put("total_route_chain_drives", plan.cells().size());
        data.put("remaining_route_chain_drives", remaining);
        data.put("required_chain_drives_this_attempt", desiredBatch);
        data.put("available_chain_drives", initialInventoryCount);
        data.put("route_hash", plan.routeHash());
        if (remaining <= 0) return true;
        if (desiredBatch <= 0) {
            String detail = "the route still needs " + remaining
                    + " chain drives, but the main inventory has no capacity for one batch";
            List<String> recovery = List.of("make_inventory_space", "resume", "cancel");
            if (duringTick) {
                beginFailure("material_inventory_full", detail,
                        FailureType.NO_SPACE, recovery, false);
            } else {
                failNow("material_inventory_full", detail,
                        FailureType.NO_SPACE, recovery);
            }
            return false;
        }

        if (initialInventoryCount <= 0) {
            // A resumed/partially built route may have a staged hotbar transaction. Restore that
            // exact transaction before acquisition, then continue the same confirmed prefix.
            if (cursor > 0 || r.continuationToken != null) {
                phase = Phase.RESTOCK_RESTORE;
            } else {
                startMaterialSupply(Math.max(1, desiredBatch), false);
            }
            return false;
        }

        // Before the first mutation, fill the currently available carrying capacity and then
        // investigate again. After a confirmed prefix exists, use what is already carried and
        // restock at the exact batch boundary; requiring the entire route in one inventory would
        // be an arbitrary 36-slot gate on otherwise valid long construction.
        if (cursor == 0 && r.continuationToken == null
                && initialInventoryCount < desiredBatch) {
            startMaterialSupply(desiredBatch, false);
            return false;
        }
        data.put("required_chain_drives_this_attempt",
                Math.min(remaining, initialInventoryCount));
        if (!plan.progressive()) {
            long scaledBudget = player.level().getGameTime()
                    + Math.max(600L,
                            (long) Math.min(remaining, initialInventoryCount) * 160L + 400L);
            r.extendDeadlineTo(scaledBudget);
        }
        materialBatches++;
        data.put("material_batches", materialBatches);
        return true;
    }

    private void startMaterialSupply(int requiredFinalCount, boolean afterConfirmedPrefix) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(chainItem);
        supply.begin(player, r.getToolCallId(), r.getDeadlineGameTime(),
                new SemanticMaterialSupplyCoordinator.Demand(
                        List.of(itemId), requiredFinalCount,
                        "investigated mechanical connection material ledger"),
                r.materialPolicy, r.allowedSources, r.allowHarm, r.protectedLabels);
        r.extendDeadlineTo(supply.childDeadline());
        supplyRounds++;
        continuingAfterRestock = afterConfirmedPrefix;
        phase = Phase.SUPPLY;
    }

    private TaskState tickSupply(LocalPlayerContext context) {
        SemanticMaterialSupplyCoordinator.Tick tick = supply.tick(player, this::runChild);
        if (tick.status() == SemanticMaterialSupplyCoordinator.Status.RUNNING) {
            r.extendDeadlineTo(supply.childDeadline());
            return TaskState.RUNNING;
        }
        supplyReceipts.add(tick.receipt());
        if (tick.status() == SemanticMaterialSupplyCoordinator.Status.FAILED) {
            supplyFailure = tick.receipt();
            beginFailure(stringValue(tick.receipt().get("failure_code"),
                            "material_supply_failed"),
                    "mechanical material supply stopped before construction: " + tick.message(),
                    tick.failureType(), recoveryIds(tick.receipt().get("recovery_options")), false);
            return TaskState.RUNNING;
        }
        return continuingAfterRestock
                ? resumeAfterRestock(context)
                : replanAfterSupply(context);
    }

    private TaskState restoreForNextBatch(LocalPlayerContext context) {
        InputDriver.halt(player);
        if (stager != null) {
            CreateMechanicalStager.Status restored = stager.restore(context);
            if (restored == CreateMechanicalStager.Status.RUNNING) return TaskState.RUNNING;
            if (restored == CreateMechanicalStager.Status.FAILED
                    || restored == CreateMechanicalStager.Status.UNCERTAIN) {
                restoreRetryBlocked = true;
                beginFailure(restored == CreateMechanicalStager.Status.UNCERTAIN
                                ? "inventory_restoration_uncertain"
                                : "inventory_restoration_failed",
                        stager.detail(), restored == CreateMechanicalStager.Status.FAILED
                                ? FailureType.NO_SPACE : FailureType.UNKNOWN,
                        List.of("inspect_inventory", "do_not_repeat_blindly", "cancel"),
                        restored == CreateMechanicalStager.Status.UNCERTAIN);
                return TaskState.RUNNING;
            }
        }
        stager = null;

        int remaining = plan.cells().size() - cursor;
        if (remaining <= 0) {
            phase = Phase.VERIFY;
            return TaskState.RUNNING;
        }
        int carried = inventoryCount(player, chainItem);
        if (carried > 0) return resumeBatchFromInventory(context);

        int desiredBatch = Math.min(remaining, carryingCapacity(player, chainItem));
        if (desiredBatch <= 0) {
            beginFailure("material_inventory_full",
                    "the confirmed route can continue, but no inventory capacity is available "
                            + "for another chain-drive batch",
                    FailureType.NO_SPACE,
                    List.of("make_inventory_space", "resume", "cancel"), false);
            return TaskState.RUNNING;
        }
        startMaterialSupply(desiredBatch, true);
        return TaskState.RUNNING;
    }

    private TaskState resumeAfterRestock(LocalPlayerContext context) {
        continuingAfterRestock = false;
        return resumeBatchFromInventory(context);
    }

    private TaskState resumeBatchFromInventory(LocalPlayerContext context) {
        int carried = inventoryCount(player, chainItem);
        if (carried <= 0) {
            beginFailure("material_supply_not_observed",
                    "the next route batch was reported supplied, but no usable chain drive is "
                            + "present in the live inventory",
                    FailureType.NO_MATERIAL,
                    List.of("inspect_inventory", "retry_supply", "cancel"), false);
            return TaskState.RUNNING;
        }
        startCursor = cursor;
        initialInventoryCount = carried;
        stager = new CreateMechanicalStager(player);
        data.put("remaining_route_chain_drives", plan.cells().size() - cursor);
        data.put("required_chain_drives_this_attempt",
                Math.min(plan.cells().size() - cursor, carried));
        data.put("available_chain_drives", carried);
        materialBatches++;
        data.put("material_batches", materialBatches);
        renewProgressLease();

        if (plan.progressive()) {
            resumeAuditIndex = 0;
            phase = Phase.RESUME_AUDIT;
            return TaskState.RUNNING;
        }
        Validation validation = validateWholeRoute(context.level());
        if (validation != null) {
            beginFailure(validation.code, validation.detail,
                    validation.type, validation.recovery, false);
            return TaskState.RUNNING;
        }
        phase = Phase.PREPARE;
        return TaskState.RUNNING;
    }

    /** Supply invalidates every pre-supply route fact; investigate again before any placement. */
    private TaskState replanAfterSupply(LocalPlayerContext context) {
        continuingAfterRestock = false;
        if (progressiveSurvey != null) progressiveSurvey.stop();
        progressiveSurvey = null;
        constructionTravel.stop();
        stopNavSafely();
        plan = null;
        stager = null;
        cursor = 0;
        startCursor = 0;
        initialInventoryCount = 0;
        data.put("reinvestigated_after_supply", supplyRounds);

        CreateMechanicalPlan.Result planned = CreateMechanicalPlanner.plan(player, r.request);
        data.putAll(planned.facts());
        if (planned.plan() == null) {
            if (progressiveEligible(planned.failureCode())) {
                progressiveSurvey = new CreateProgressiveSurvey(r.request);
                progressiveProgressRevision = progressiveSurvey.progressRevision();
                renewProgressLease();
                data.put("progressive_survey", true);
                phase = Phase.PROGRESSIVE;
                return TaskState.RUNNING;
            }
            beginFailure(planned.failureCode(), stringFact(planned.facts(), "detail",
                            "fresh post-supply investigation did not produce a route"),
                    mapFailure(planned.failureCode()), recovery(planned.facts()), false);
            return TaskState.RUNNING;
        }
        plan = planned.plan();
        stager = new CreateMechanicalStager(player);
        if (initializeApprovedPlan(true)) phase = Phase.PREPARE;
        return TaskState.RUNNING;
    }

    private TaskState prepare(LocalPlayerContext context) {
        if (cursor >= plan.cells().size()) {
            phase = Phase.VERIFY;
            return TaskState.RUNNING;
        }
        int expectedInventory = initialInventoryCount - (cursor - startCursor);
        int actualInventory = inventoryCount(player, chainItem);
        if (expectedInventory == 0) {
            // The prior batch is exactly accounted for. Restore staging before accepting any
            // newly observed items or asking the internal supplier for the next finite batch.
            phase = Phase.RESTOCK_RESTORE;
            return TaskState.RUNNING;
        }
        if (actualInventory != expectedInventory) {
            beginFailure("material_changed",
                    "the exact chain-drive inventory budget changed between confirmed placements",
                    FailureType.NO_MATERIAL, List.of("inspect_inventory", "cancel"), false);
            return TaskState.RUNNING;
        }
        if (plan.progressive()) {
            CreateMechanicalPlan.RouteCell active = plan.cells().get(cursor);
            if (!context.level().isLoaded(active.position())
                    || !context.level().isLoaded(active.support())
                    || !context.level().isLoaded(active.stand())) {
                BlockPos loadTarget = !context.level().isLoaded(active.position())
                        ? active.position()
                        : !context.level().isLoaded(active.support())
                                ? active.support() : active.stand();
                CreateProgressiveSurvey.Travel.Status travel = constructionTravel.tick(
                        player, loadTarget, 2);
                observeConstructionTravelProgress();
                if (travel == CreateProgressiveSurvey.Travel.Status.RUNNING) {
                    return TaskState.RUNNING;
                }
                if (travel == CreateProgressiveSurvey.Travel.Status.FAILED) {
                    beginFailure("construction_corridor_unreachable",
                            "could not reload the next surveyed corridor segment: "
                                    + constructionTravel.failure(),
                            FailureType.NO_PATH,
                            List.of("make_path_accessible", "resume", "cancel"), false);
                    return TaskState.RUNNING;
                }
            }
        }
        Validation validation = validateWholeRoute(context.level());
        if (validation != null) {
            beginFailure(validation.code, validation.detail, validation.type, validation.recovery, false);
            return TaskState.RUNNING;
        }
        CreateMechanicalStager.Status staged = stager.ensureChainSelected(context, chainItem);
        if (staged == CreateMechanicalStager.Status.RUNNING) return TaskState.RUNNING;
        if (staged == CreateMechanicalStager.Status.FAILED
                || staged == CreateMechanicalStager.Status.UNCERTAIN) {
            beginFailure(staged == CreateMechanicalStager.Status.UNCERTAIN
                            ? "inventory_staging_uncertain" : "inventory_staging_failed",
                    stager.detail(), staged == CreateMechanicalStager.Status.FAILED
                            ? FailureType.NO_SPACE : FailureType.UNKNOWN,
                    List.of("inspect_inventory", "clear_hotbar_slot", "cancel"),
                    staged == CreateMechanicalStager.Status.UNCERTAIN);
            return TaskState.RUNNING;
        }
        CreateMechanicalPlan.RouteCell cell = plan.cells().get(cursor);
        activeStand = cell.stand();
        final BlockPos stand = activeStand;
        nav = new PlayerNav(player, stand, 0.85,
                () -> player.blockPosition().distSqr(stand) <= 1.0);
        phase = Phase.NAVIGATE;
        return TaskState.RUNNING;
    }

    private TaskState navigate(LocalPlayerContext context) {
        Validation endpoint = validateSourceAndDestination(context.level(), cursor == startCursor);
        if (endpoint != null) {
            beginFailure(endpoint.code, endpoint.detail, endpoint.type, endpoint.recovery, false);
            return TaskState.RUNNING;
        }
        PlayerNav.Status status = nav.tick();
        if (status == PlayerNav.Status.RUNNING) return TaskState.RUNNING;
        if (status == PlayerNav.Status.FAILED) {
            String reason = nav.failReason();
            FailureType type = nav.failType();
            stopNav();
            beginFailure("placement_stance_unreachable", reason, type,
                    List.of("make_corridor_accessible", "choose_other_endpoint", "cancel"), false);
            return TaskState.RUNNING;
        }
        stopNav();
        phase = Phase.ALIGN;
        readyTicks = 0;
        return TaskState.RUNNING;
    }

    private TaskState alignAndPlace(LocalPlayerContext context) {
        CreateMechanicalPlan.RouteCell cell = plan.cells().get(cursor);
        if (!context.level().isLoaded(cell.position()) || !context.level().isLoaded(cell.support())) {
            beginFailure("route_needs_exploration", "the active placement cell became unloaded",
                    FailureType.TARGET_LOST, List.of("travel_to_load_corridor", "resume", "cancel"), false);
            return TaskState.RUNNING;
        }
        if (!CreateMechanicalPlanner.isEmptyRouteCell(context.level(), cell.position())) {
            beginFailure("route_changed", "the active route cell is no longer empty and safe",
                    FailureType.TARGET_LOST, List.of("inspect_obstruction", "choose_other_route", "cancel"), false);
            return TaskState.RUNNING;
        }
        Vec3 point = faceCenter(cell.support(), cell.supportFace());
        InputDriver.halt(player);
        InputDriver.lookAt(player, point);
        float[] look = lookAngles(player.getEyePosition(), point);
        if (Math.abs(look[1]) < MIN_VERTICAL_PITCH) {
            beginFailure("endpoint_orientation_unsupported",
                    "the verified stance cannot aim steeply enough to place a vertical-axis chain drive",
                    FailureType.STANCE_DUD, List.of("choose_other_endpoint", "make_alternate_stance", "cancel"), false);
            return TaskState.RUNNING;
        }
        if (!lookReady(player, look[0], look[1])) {
            readyTicks = 0;
            return TaskState.RUNNING;
        }
        BlockHitResult hit = nativeRaycast(player);
        if (hit == null || !hit.getBlockPos().equals(cell.support())
                || hit.getDirection() != cell.supportFace()) {
            readyTicks = 0;
            if (++networkWaitTicks > 20) {
                networkWaitTicks = 0;
                beginFailure("placement_occluded",
                        "the first-person ray no longer reaches the planned support face",
                        FailureType.OCCLUDED, List.of("make_corridor_accessible", "choose_other_route", "cancel"), false);
            }
            return TaskState.RUNNING;
        }
        networkWaitTicks = 0;
        readyTicks++;
        if (cursor == 0) {
            context.body().applyMovement(new BodyControlPort.Movement(0, 0, false, true, false),
                    context.tickRevision());
        }
        if (readyTicks < READY_TICKS) return TaskState.RUNNING;
        ItemStack beforeStack = player.getMainHandItem().copy();
        if (CreateMechanicalStager.usable(beforeStack, chainItem) <= 0) {
            beginFailure("material_changed", "the selected chain-drive stack changed before placement",
                    FailureType.NO_MATERIAL, List.of("inspect_inventory", "resume", "cancel"), false);
            return TaskState.RUNNING;
        }
        BlockState expected = predictedState(player, beforeStack, hit);
        if (expected == null || expected.getBlock() != chainBlock || !"y".equals(axisName(expected))) {
            beginFailure("endpoint_orientation_unsupported",
                    "native placement would not produce a vertical-axis encased chain drive",
                    FailureType.STANCE_DUD, List.of("choose_other_endpoint", "make_alternate_stance", "cancel"), false);
            return TaskState.RUNNING;
        }
        BlockState targetBefore = context.level().getBlockState(cell.position());
        BlockState supportBefore = context.level().getBlockState(cell.support());
        int totalBefore = inventoryCount(player, chainItem);
        NativeConfirmation confirmation = placementConfirmation(
                cell, targetBefore, supportBefore, expected, beforeStack, totalBefore);
        placementReceipt = context.actions().useBlock(context, InteractionHand.MAIN_HAND,
                hit, confirmation, PLACEMENT_CONFIRM_TICKS);
        phase = Phase.WAIT_PLACEMENT;
        return TaskState.RUNNING;
    }

    private TaskState settlePlacement(LocalPlayerContext context) {
        InputDriver.halt(player);
        placementReceipt = context.actions().poll(context, placementReceipt);
        if (!placementReceipt.terminal()) return TaskState.RUNNING;
        NativeActionReceipt.Status status = placementReceipt.status();
        String detail = placementReceipt.detail();
        placementReceipt = null;
        if (status != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            boolean uncertain = status == NativeActionReceipt.Status.UNCERTAIN
                    || status == NativeActionReceipt.Status.DIVERGED;
            beginFailure(uncertain ? "placement_outcome_uncertain" : "placement_not_applied",
                    "native chain-drive placement was not definitely applied: " + detail,
                    FailureType.UNKNOWN,
                    List.of("inspect_route_cell", uncertain ? "do_not_retry" : "resume", "cancel"),
                    uncertain);
            return TaskState.RUNNING;
        }
        cursor++;
        renewProgressLease();
        readyTicks = 0;
        context.body().clearLook();
        data.put("confirmed_route_cells", cursor);
        phase = cursor >= plan.cells().size() ? Phase.VERIFY : Phase.PREPARE;
        return TaskState.RUNNING;
    }

    private TaskState verifyNetwork(LocalPlayerContext context) {
        InputDriver.halt(player);
        Validation route = validateWholeRoute(context.level());
        if (route != null) {
            beginFailure(route.code, route.detail, route.type, route.recovery, false);
            return TaskState.RUNNING;
        }
        CreateKineticsBridge.Facts source = CreateKineticsBridge.inspect(
                context.level(), plan.source().position());
        CreateKineticsBridge.Facts destination = CreateKineticsBridge.inspect(
                context.level(), plan.destinationPosition());
        int expectedInventory = initialInventoryCount - (cursor - startCursor);
        if (inventoryCount(player, chainItem) != expectedInventory) {
            beginFailure("postcondition_failed",
                    "the completed route did not consume exactly one item per confirmed placement",
                    FailureType.UNKNOWN, List.of("inspect_inventory", "inspect_route", "cancel"), true);
            return TaskState.RUNNING;
        }
        boolean sourceAccepted = source != null && source.powered()
                || plan.progressive() && !context.level().isLoaded(plan.source().position());
        if (sourceAccepted && destination != null && destination.powered()) {
            float acceptedSourceSpeed = source == null ? plan.sourceSpeedAtSurvey() : source.speed();
            data.put("source_speed", canonicalSpeed(acceptedSourceSpeed));
            data.put("destination_speed", canonicalSpeed(destination.speed()));
            if (source != null && source.overstressed() != null) {
                data.put("source_overstressed", source.overstressed());
            }
            if (destination.overstressed() != null) data.put("destination_overstressed", destination.overstressed());
            data.put("speed_unit", "create_rpm");
            data.put("network_live", true);
            data.put("source_verification", source == null
                    ? "frozen_after_return_to_source_before_first_mutation"
                    : "live_at_acceptance");
            data.put("acceptance_basis",
                    "the formerly unpowered destination became a live non-zero-speed endpoint after the exact contiguous route was confirmed");
            data.put("placed_this_attempt", cursor - invocationStartCursor);
            data.put("confirmed_route_cells", cursor);
            failureCode = null;
            failureDetail = null;
            recoveryOptions = List.of();
            desiredSuccess = true;
            phase = Phase.RESTORE;
            return TaskState.RUNNING;
        }
        if (++networkWaitTicks < NETWORK_CONFIRM_TICKS) return TaskState.RUNNING;
        beginFailure("network_not_propagated",
                "every route cell was confirmed, but the destination did not report a live non-zero-speed kinetic network",
                FailureType.UNKNOWN,
                List.of("inspect_overstress_or_direction", "inspect_endpoint", "cancel"), false);
        return TaskState.RUNNING;
    }

    private TaskState restoreAndFinish(LocalPlayerContext context) {
        InputDriver.halt(player);
        if (restoreRetryBlocked) {
            data.put("inventory_restoration", stager == null
                    ? "automatic restoration retry blocked after a terminal receipt"
                    : stager.detail());
            fail(failureDetail == null
                    ? "inventory restoration stopped after a terminal receipt"
                    : failureDetail, failureType);
            return TaskState.FAILED;
        }
        CreateMechanicalStager.Status restored = stager == null
                ? CreateMechanicalStager.Status.RESTORED : stager.restore(context);
        if (restored == CreateMechanicalStager.Status.RUNNING) return TaskState.RUNNING;
        if (restored == CreateMechanicalStager.Status.FAILED
                || restored == CreateMechanicalStager.Status.UNCERTAIN) {
            nativeOutcomeUncertain |= restored == CreateMechanicalStager.Status.UNCERTAIN;
            failureCode = restored == CreateMechanicalStager.Status.UNCERTAIN
                    ? "inventory_restoration_uncertain" : "inventory_restoration_failed";
            failureDetail = stager.detail();
            failureType = restored == CreateMechanicalStager.Status.FAILED
                    ? FailureType.NO_SPACE : FailureType.UNKNOWN;
            recoveryOptions = List.of("inspect_inventory", "do_not_repeat_blindly", "cancel");
            desiredSuccess = false;
        }
        data.put("inventory_restoration", stager == null ? "not_needed" : stager.detail());
        if (desiredSuccess) {
            succeed();
            return TaskState.SUCCESS;
        }
        fail(failureDetail == null ? "mechanical connection failed" : failureDetail, failureType);
        return TaskState.FAILED;
    }

    private NativeConfirmation placementConfirmation(
            CreateMechanicalPlan.RouteCell cell,
            BlockState targetBefore,
            BlockState supportBefore,
            BlockState expected,
            ItemStack stackBefore,
            int totalBefore) {
        return context -> {
            ClientLevel level = context.level();
            if (!level.isLoaded(cell.position()) || !level.isLoaded(cell.support())) {
                return NativeConfirmation.Verdict.PENDING;
            }
            BlockState target = level.getBlockState(cell.position());
            BlockState support = level.getBlockState(cell.support());
            ItemStack held = context.player().getMainHandItem();
            if (!support.equals(supportBefore)) return NativeConfirmation.Verdict.DIVERGED;
            boolean blockAfter = target.equals(expected) && "y".equals(axisName(target));
            boolean itemAfter = exactlyOneConsumed(stackBefore, held)
                    && inventoryCount(context.player(), chainItem) == totalBefore - 1;
            boolean blockBefore = target.equals(targetBefore);
            boolean itemBefore = sameStack(held, stackBefore)
                    && inventoryCount(context.player(), chainItem) == totalBefore;
            if (blockAfter && itemAfter) return NativeConfirmation.Verdict.APPLIED;
            if (blockBefore && itemBefore) return NativeConfirmation.Verdict.PENDING;
            if ((blockBefore || blockAfter) && (itemBefore || itemAfter)) {
                return NativeConfirmation.Verdict.PENDING;
            }
            return NativeConfirmation.Verdict.DIVERGED;
        };
    }

    private Validation validateWholeRoute(ClientLevel level) {
        Validation endpoint = validateSourceAndDestination(level, cursor == startCursor);
        if (endpoint != null) return endpoint;
        int from = plan.progressive() ? Math.max(0, cursor - 8) : 0;
        int to = plan.progressive() ? Math.min(plan.cells().size(), cursor + 64) : plan.cells().size();
        for (int i = from; i < to; i++) {
            BlockPos position = plan.cells().get(i).position();
            if (!level.isLoaded(position)) {
                if (plan.progressive()) continue;
                return new Validation("route_needs_exploration",
                        "a planned route cell is no longer loaded", FailureType.TARGET_LOST,
                        List.of("travel_to_load_corridor", "resume", "cancel"));
            }
            BlockState state = level.getBlockState(position);
            if (i < cursor) {
                if (state.getBlock() != chainBlock || !"y".equals(axisName(state))) {
                    return new Validation("confirmed_prefix_changed",
                            "a previously confirmed route cell changed",
                            FailureType.TARGET_LOST,
                            List.of("inspect_partial_route", "repair_manually", "cancel"));
                }
            } else if (!CreateMechanicalPlanner.isEmptyRouteCell(level, position)) {
                return new Validation("route_changed",
                        "a remaining route cell is no longer empty and safe",
                        FailureType.TARGET_LOST,
                        List.of("inspect_obstruction", "choose_other_route", "cancel"));
            }
        }
        return null;
    }

    private Validation validateSourceAndDestination(ClientLevel level, boolean beforeFirstMutation) {
        CreateMechanicalPlan.KineticEndpoint source = plan.source();
        boolean sourceLoaded = level.isLoaded(source.position());
        if (!sourceLoaded && plan.progressive() && cursor > 0) {
            // The exact source was revalidated after returning from survey and immediately before
            // the first mutation. Once a long route advances beyond its loaded window, only the
            // frozen source fact is available; every newly loaded route cell is still rechecked.
        } else if (!sourceLoaded || !level.getBlockState(source.position()).equals(source.state())) {
            return new Validation("source_changed", "the selected source block changed or unloaded",
                    FailureType.TARGET_LOST, List.of("inspect_source", "cancel"));
        }
        CreateKineticsBridge.Facts sourceFacts = sourceLoaded
                ? CreateKineticsBridge.inspect(level, source.position()) : null;
        if (sourceLoaded && (sourceFacts == null || !sourceFacts.powered())) {
            return new Validation("source_changed", "the selected source is no longer a live powered endpoint",
                    FailureType.TARGET_LOST, List.of("restore_source_power", "choose_other_source", "cancel"));
        }
        CreateMechanicalPlan.KineticEndpoint destination = plan.destinationMachine();
        boolean destinationLoaded = destination == null
                || level.isLoaded(destination.position());
        if (destination != null && !destinationLoaded && plan.progressive()) {
            // Frozen during destination survey; revalidated when its corridor window loads again.
        } else if (destination != null && (!destinationLoaded
                || !level.getBlockState(destination.position()).equals(destination.state()))) {
            return new Validation("destination_changed", "the selected destination block changed or unloaded",
                    FailureType.TARGET_LOST, List.of("inspect_destination", "cancel"));
        }
        boolean receiverPending = plan.progressive()
                && cursor == plan.cells().size() - 1;
        if (beforeFirstMutation || receiverPending) {
            CreateKineticsBridge.Facts destinationFacts = level.isLoaded(plan.destinationPosition())
                    ? CreateKineticsBridge.inspect(level, plan.destinationPosition()) : null;
            if (destinationFacts != null && destinationFacts.powered()) {
                return new Validation("destination_already_powered",
                        receiverPending
                                ? "the destination became powered before the surveyed receiver was connected; source membership is not provable"
                                : "the destination became powered before the first approved placement; source membership is not provable",
                        FailureType.TARGET_LOST,
                        List.of("inspect_existing_network", "choose_unpowered_destination", "cancel"));
            }
        }
        return null;
    }

    private record Validation(
            String code, String detail, FailureType type, List<String> recovery) {}

    private void beginFailure(
            String code, String detail, FailureType type, List<String> recovery, boolean uncertain) {
        if (phase == Phase.RESTORE) return;
        failureCode = code;
        failureDetail = detail;
        failureType = type;
        recoveryOptions = List.copyOf(recovery);
        nativeOutcomeUncertain |= uncertain;
        desiredSuccess = false;
        if (progressiveSurvey != null) progressiveSurvey.stop();
        constructionTravel.stop();
        stopNavSafely();
        phase = Phase.RESTORE;
    }

    private void failNow(String code, String detail, FailureType type, List<String> recovery) {
        failureCode = code;
        failureDetail = detail;
        failureType = type;
        recoveryOptions = List.copyOf(recovery);
        fail(detail, type);
    }

    @Override
    public void stop(LocalPlayer companion, Task.StopReason why) {
        if (why != Task.StopReason.PREEMPTED) forcedStop = why;
        if (why != Task.StopReason.PREEMPTED && failureCode == null) {
            failureCode = why == Task.StopReason.BODY_GONE ? "body_gone" : "cancelled";
            failureDetail = why == Task.StopReason.BODY_GONE
                    ? "the local player body disappeared during the mechanical connection"
                    : "the mechanical connection was cancelled or replaced";
            recoveryOptions = why == Task.StopReason.BODY_GONE
                    ? List.of("rejoin_world", "inspect_partial_route", "cancel")
                    : List.of("resume_if_token_present", "inspect_partial_route", "cancel");
        }
        if (why != Task.StopReason.PREEMPTED
                && (placementReceipt != null || stager != null && stager.hasPendingReceipt())) {
            nativeOutcomeUncertain = true;
            recoveryOptions = List.of("inspect_world_and_inventory", "do_not_retry_blindly", "cancel");
        }
        if (progressiveSurvey != null) {
            if (why == Task.StopReason.PREEMPTED) progressiveSurvey.pause();
            else progressiveSurvey.stop();
        }
        if (why == Task.StopReason.PREEMPTED) constructionTravel.pause();
        else constructionTravel.stop();
        if (why == Task.StopReason.BODY_GONE) return;
        super.stop(companion, why);
    }

    @Override
    protected void cleanup() {
        if (supply.active()) supply.cancel(player);
        if (progressiveSurvey != null) progressiveSurvey.stop();
        constructionTravel.stop();
        stopNavSafely();
        issueContinuationIfSafe();
    }

    private void stopNavSafely() {
        try {
            stopNav();
        } catch (RuntimeException ignored) {
            nav = null;
        }
    }

    private void issueContinuationIfSafe() {
        if (continuationIssued || plan == null || cursor <= 0 || cursor >= plan.cells().size()
                || nativeOutcomeUncertain || placementReceipt != null
                || stager != null && stager.hasPendingReceipt()
                || forcedStop == Task.StopReason.BODY_GONE) return;
        CreateMechanicalStager.Snapshot staging = stager == null ? null : stager.snapshot();
        if (stager != null && staging == null) return;
        CreateMechanicalContinuations.Entry receipt = CreateMechanicalContinuations.issue(
                r.request, plan, cursor, bodyEpoch, dimension, staging);
        if (receipt == null) return;
        continuationIssued = true;
        data.put("continuation_token", receipt.token().toString());
        data.put("continuation_confirmed_prefix", cursor);
        data.put("continuation_prefix_hash", receipt.prefixHash());
        data.put("continuation_policy", "resume_only_after_revalidating_exact_prefix_and_remaining_empty_route");
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> safe = new LinkedHashMap<>();
        copyResultField(data, safe,
                "progressive_survey", "progressive_travel_segments",
                "progressive_rejected_route_candidates", "source_loaded_cells",
                "source_unloaded_cells", "destination_loaded_cells",
                "destination_unloaded_cells", "numeric_stress_margin_supported",
                "stress_evidence", "delivery_kind", "required_chain_drives_this_attempt",
                "total_route_chain_drives", "remaining_route_chain_drives",
                "available_chain_drives", "material_batches",
                "source_speed", "destination_speed",
                "source_overstressed", "destination_overstressed", "speed_unit",
                "network_live", "source_verification", "acceptance_basis",
                "placed_this_attempt",
                "inventory_restoration", "reinvestigated_after_supply",
                "continuation_token", "continuation_confirmed_prefix",
                "continuation_policy");
        if (failureCode != null) safe.put("failure_code", failureCode);
        if (!recoveryOptions.isEmpty()) safe.put("recovery_options", recoveryOptions);
        safe.put("confirmed_placements", cursor);
        safe.put("planned_placements", plan == null ? 0 : plan.cells().size());
        safe.put("native_outcome_uncertain", nativeOutcomeUncertain);
        safe.put("material_policy", r.materialPolicy.id());
        safe.put("supply_rounds", supplyRounds);
        safe.put("supply_receipts", List.copyOf(supplyReceipts));
        if (!supplyFailure.isEmpty()) safe.put("supply_failure", supplyFailure);
        if (failureDetail != null) safe.put("detail", failureDetail);
        return safe;
    }

    @Override
    protected String successMessage() {
        return "mechanical power connected through " + cursor
                + " confirmed first-person chain-drive placements";
    }

    @Override
    protected String timeoutMessage() {
        if (failureCode == null) {
            failureCode = "timeout";
            failureDetail = "the mechanical connection stopped making verifiable progress and its liveness lease expired";
            recoveryOptions = placementReceipt == null && (stager == null || !stager.hasPendingReceipt())
                    ? List.of("resume_if_token_present", "inspect_partial_route", "cancel")
                    : List.of("inspect_world_and_inventory", "do_not_retry_blindly", "cancel");
            nativeOutcomeUncertain |= placementReceipt != null
                    || stager != null && stager.hasPendingReceipt();
        }
        return failureDetail;
    }

    @Override
    protected String cancelledMessage() {
        return failureDetail == null ? "mechanical connection interrupted" : failureDetail;
    }

    private static int inventoryCount(LocalPlayer player, Item item) {
        int total = 0;
        for (int slot = 0; slot < Math.min(36, player.getInventory().getContainerSize()); slot++) {
            total += CreateMechanicalStager.usable(player.getInventory().getItem(slot), item);
        }
        return total;
    }

    /** Maximum final count that the current 36-slot inventory can physically carry. */
    private static int carryingCapacity(LocalPlayer player, Item item) {
        int capacity = 0;
        int defaultStack = new ItemStack(item).getMaxStackSize();
        for (int slot = 0; slot < Math.min(36, player.getInventory().getContainerSize()); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                capacity += defaultStack;
            } else if (CreateMechanicalStager.usable(stack, item) > 0) {
                capacity += stack.getMaxStackSize();
            }
        }
        return capacity;
    }

    private void renewProgressLease() {
        r.extendDeadlineTo(player.level().getGameTime() + PROGRESS_LEASE_TICKS);
    }

    private void observeProgressiveSurveyProgress() {
        if (progressiveSurvey == null) return;
        long revision = progressiveSurvey.progressRevision();
        boolean advanced = revision > progressiveProgressRevision;
        progressiveProgressRevision = Math.max(progressiveProgressRevision, revision);
        if (advanced
                || progressiveSurvey.hasRecentPhysicalProgress(PROGRESS_GRACE_TICKS)
                || progressiveSurvey.planningInFlight()) {
            renewProgressLease();
        }
    }

    private void observeConstructionTravelProgress() {
        long revision = constructionTravel.progressRevision();
        boolean advanced = revision > constructionProgressRevision;
        constructionProgressRevision = Math.max(constructionProgressRevision, revision);
        if (advanced
                || constructionTravel.hasRecentPhysicalProgress(PROGRESS_GRACE_TICKS)
                || constructionTravel.planningInFlight()) {
            renewProgressLease();
        }
    }

    private static BlockHitResult nativeRaycast(LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(player.blockInteractionRange()));
        BlockHitResult hit = player.level().clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private static Vec3 faceCenter(BlockPos support, Direction face) {
        return Vec3.atCenterOf(support).add(
                face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
    }

    private static float[] lookAngles(Vec3 eye, Vec3 target) {
        Vec3 delta = target.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0f;
        float pitch = (float) -(Mth.atan2(delta.y, horizontal) * Mth.RAD_TO_DEG);
        return new float[]{yaw, pitch};
    }

    private static boolean lookReady(LocalPlayer player, float yaw, float pitch) {
        return Math.abs(Mth.wrapDegrees(player.getYRot() - yaw)) <= LOOK_EPSILON
                && Math.abs(player.getXRot() - pitch) <= LOOK_EPSILON;
    }

    private static BlockState predictedState(
            LocalPlayer player, ItemStack stack, BlockHitResult hit) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) return null;
        try {
            BlockPlaceContext placement = new BlockPlaceContext(new UseOnContext(
                    player.level(), player, InteractionHand.MAIN_HAND, stack, hit) {});
            BlockState predicted = blockItem.getBlock().getStateForPlacement(placement);
            return predicted != null && placement.canPlace() ? predicted : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean exactlyOneConsumed(ItemStack before, ItemStack after) {
        if (before.getCount() == 1) return after.isEmpty();
        return after.getCount() == before.getCount() - 1
                && before.getItem() == after.getItem()
                && ItemStack.isSameItemSameComponents(before, after);
    }

    private static boolean sameStack(ItemStack left, ItemStack right) {
        return left.getCount() == right.getCount()
                && ItemStack.isSameItemSameComponents(left, right);
    }

    private static String axisName(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals("axis")) return propertyName(state, property);
        }
        return null;
    }

    private static <T extends Comparable<T>> String propertyName(
            BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static String canonicalSpeed(float speed) {
        return new java.math.BigDecimal(Float.toString(speed)).stripTrailingZeros().toPlainString();
    }

    private static FailureType mapFailure(String code) {
        if (code == null) return FailureType.UNKNOWN;
        if (code.contains("material")) return FailureType.NO_MATERIAL;
        if (code.contains("route") || code.contains("stance")) return FailureType.NO_PATH;
        if (code.contains("exploration") || code.contains("not_found")) return FailureType.TARGET_LOST;
        if (code.contains("unavailable")) return FailureType.UNSUPPORTED;
        return FailureType.UNKNOWN;
    }

    private static boolean progressiveEligible(String code) {
        return "source_needs_exploration".equals(code)
                || "destination_needs_exploration".equals(code)
                || "route_needs_exploration".equals(code);
    }

    private static String stringFact(Map<String, Object> facts, String key, String fallback) {
        Object value = facts.get(key);
        return value == null ? fallback : value.toString();
    }

    private static List<String> recovery(Map<String, Object> facts) {
        Object value = facts.get("recovery_options");
        if (!(value instanceof List<?> list)) return List.of("inspect_facts", "cancel");
        List<String> result = new ArrayList<>();
        for (Object entry : list) if (entry != null) result.add(entry.toString());
        return result.isEmpty() ? List.of("inspect_facts", "cancel") : List.copyOf(result);
    }

    private static void copyResultField(
            Map<String, Object> from, Map<String, Object> to, String... keys) {
        for (String key : keys) if (from.containsKey(key)) to.put(key, from.get(key));
    }

    private static String stringValue(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static List<String> recoveryIds(Object value) {
        if (!(value instanceof List<?> list)) return List.of(
                "change_material_source_policy", "change_route_or_endpoints", "cancel");
        List<String> result = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map && map.get("id") != null) {
                result.add(map.get("id").toString());
            } else if (entry != null) result.add(entry.toString());
        }
        return result.isEmpty()
                ? List.of("change_material_source_policy", "change_route_or_endpoints", "cancel")
                : List.copyOf(result);
    }
}
