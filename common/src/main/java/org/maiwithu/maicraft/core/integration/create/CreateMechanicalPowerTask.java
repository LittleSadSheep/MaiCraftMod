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
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
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

    private enum Phase { PROGRESSIVE, RESUME_AUDIT, SUPPLY, PREPARE, NAVIGATE, ALIGN, WAIT_PLACEMENT, VERIFY, RESTORE }

    private final Map<String, Object> data = new LinkedHashMap<>();
    private final SemanticMaterialSupplyCoordinator supply =
            new SemanticMaterialSupplyCoordinator();
    private final List<Map<String, Object>> supplyReceipts = new ArrayList<>();
    private Map<String, Object> supplyFailure = Map.of();
    private int supplyRounds;
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
    private int startCursor;
    private int initialInventoryCount;
    private int readyTicks;
    private int networkWaitTicks;
    private long progressiveStartedGameTime = -1L;
    private long progressiveActiveTicks;
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
                    phase = Phase.PROGRESSIVE;
                    // Same bounded wall as the source implementation's progressive action: at
                    // normal tick rate this is thirty minutes, and preemption still freezes it.
                    progressiveStartedGameTime = player.level().getGameTime();
                    r.extendDeadlineTo(progressiveStartedGameTime + 36_000L);
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
                progressiveStartedGameTime = player.level().getGameTime();
                r.extendDeadlineTo(progressiveStartedGameTime + 36_000L);
            } else {
                Validation validation = validateWholeRoute(context.level());
                if (validation != null) {
                    failNow(validation.code, validation.detail, validation.type, validation.recovery);
                    return;
                }
            }
        }
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
        if (progressiveStartedGameTime >= 0 && phase != Phase.RESTORE
                && ++progressiveActiveTicks >= 36_000L) {
            beginFailure("progressive_timeout",
                    "the bounded thirty-minute progressive survey/construction window elapsed",
                    FailureType.TIMED_OUT,
                    List.of("resume_if_token_present", "choose_closer_endpoint", "cancel"), false);
        }
        if (placementReceipt != null || stager != null && stager.hasPendingReceipt()
                || phase == Phase.RESTORE) {
            r.extendDeadlineTo(Math.max(r.getDeadlineGameTime(), player.level().getGameTime() + 2));
        }
        return switch (phase) {
            case PROGRESSIVE -> progressSurvey(context);
            case RESUME_AUDIT -> auditProgressiveContinuation(context);
            case SUPPLY -> tickSupply(context);
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
        }
        return TaskState.RUNNING;
    }

    private TaskState progressSurvey(LocalPlayerContext context) {
        CreateProgressiveSurvey.Status status = progressiveSurvey.tick(context);
        if (status == CreateProgressiveSurvey.Status.RUNNING) return TaskState.RUNNING;
        if (status == CreateProgressiveSurvey.Status.FAILED) {
            CreateProgressiveSurvey.Failure failure = progressiveSurvey.failure();
            beginFailure(failure.code(), failure.detail(), failure.type(), failure.recovery(), false);
            return TaskState.RUNNING;
        }
        plan = progressiveSurvey.plan();
        data.put("progressive_survey", true);
        data.put("progressive_travel_segments", progressiveSurvey.travelSegments());
        data.put("progressive_rejected_route_candidates",
                progressiveSurvey.rejectedRouteCandidates());
        data.put("source_loaded_cells", progressiveSurvey.sourceLoadedCells());
        data.put("source_unloaded_cells", progressiveSurvey.sourceUnloadedCells());
