// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.structure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.FirstPersonActionGate;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * Discover a structure without seed, generator or server-locate authority.
 *
 * <p>Strongholds are followed by real, receipt-confirmed eye throws. Every other registered
 * structure shares one data-driven evidence matcher and one bounded frontier explorer. The model
 * never supplies (or receives) the internal travel legs.</p>
 */
public final class PhysicalStructureSearchCompanionTask
        extends AbstractCompanionTask<PhysicalStructureSearchTaskRecord> {
    private enum Stage {
        OBSERVE,
        SELECT_EYE,
        WAIT_EYE_RECEIPT,
        TRACK_EYE,
        MOVE_DIRECTION,
        MOVE_FRONTIER,
        MOVE_EVIDENCE,
        VERIFY_EVIDENCE
    }

    private record EvidenceMatch(
            BlockPos position,
            Map<String, Integer> groupCounts,
            Map<String, Integer> blockCounts,
            int totalBlocks) {}

    private record EvidenceScan(EvidenceMatch match, boolean complete, int observedBlocks) {}

    private static final String STRONGHOLD = "minecraft:stronghold";
    private static final String OVERWORLD = "minecraft:overworld";
    private static final int INDEX_BUILD_BUDGET_PER_GROUP = 4;
    private static final int MAX_GROUP_HITS = 384;
    private static final int EVIDENCE_SCAN_RADIUS = 192;
    private static final int FRONTIER_GRID = 64;
    private static final int MAX_FRONTIER_LEG = 80;
    private static final int MIN_FRONTIER_LEG = 12;
    private static final int MAX_FRONTIER_PROBES = 10_000;
    private static final int MAX_FRONTIER_LEGS = 256;
    private static final int MAX_EVIDENCE_APPROACHES = 8;
    private static final int LEG_TIMEOUT_TICKS = 90 * 20;
    private static final int MOVING_EVIDENCE_SCAN_INTERVAL = 5;
    private static final int MAX_EYE_THROWS = 20;
    private static final int EYE_RECEIPT_TICKS = 40;
    private static final int EYE_SPAWN_RADIUS = 12;
    private static final int EYE_REACQUIRE_RADIUS = 40;
    private static final int MIN_EYE_TRACK_TICKS = 4;
    private static final int MAX_EYE_TRACK_TICKS = 24;
    private static final double MIN_EYE_DISPLACEMENT = 2.0;
    private static final double SETTLED_EYE_DISPLACEMENT = 8.0;
    private static final double INITIAL_DIRECTION_STEP = 256.0;
    private static final double MIN_DIRECTION_STEP = 8.0;
    private static final double REVERSE_DOT = -0.15;
    private static final int SCOPE_TOLERANCE = 8;

    private final FirstPersonActionGate eyeSelection = new FirstPersonActionGate();
    private final Set<Long> rejectedEvidence = new HashSet<>();
    private final Set<Long> attemptedFrontiers = new HashSet<>();
    private final List<String> routeFailureKinds = new ArrayList<>();
    private StructureEvidenceProfiles.ResolvedProfile profile;
    private ClientLevel indexedLevel;
    private Set<Block> indexedBlocks = Set.of();
    private BlockPos origin;
    private Stage stage = Stage.OBSERVE;
    private boolean stronghold;
    private boolean cleaned;
    private MoveToCompanionTask moveChild;
    private long legDeadline;
    private int legSerial;
    private int frontierAttempts;
    private int frontierReached;
    private int frontierFailed;
    private int evidenceApproaches;
    private int spiralX;
    private int spiralZ;
    private int spiralDirection;
    private int spiralSegmentLength = 1;
    private int spiralSegmentProgress;
    private int spiralSegmentsAtLength;
    private EvidenceMatch activeEvidence;
    private EvidenceMatch verifiedEvidence;
    private String issueCode;
    private NativeActionReceipt eyeReceipt;
    private Set<UUID> eyesBefore = Set.of();
    private Vec3 throwOrigin;
    private EyeOfEnder trackedEye;
    private Vec3 trackStart;
    private Vec3 trackLast;
    private int trackTicks;
    private int eyeCountBeforeThrow = -1;
    private int eyesConsumed;
    private int directionLegs;
    private int directionReversals;
    private Vec3 previousDirection;
    private double directionStep = INITIAL_DIRECTION_STEP;
    private Vec3 pendingDirection;
    private double pendingDirectionDistance;
    private double directionSegmentDistance;
    private int directionLoadWaitTicks;
    private int directionSegments;
    private long nextMovingEvidenceScan;

    public PhysicalStructureSearchCompanionTask(
            LocalPlayer player, PhysicalStructureSearchTaskRecord record) {
        super(player, record);
    }

    /**
     * Internal typed evidence handoff for composing semantic tasks. The public result deliberately
     * omits this coordinate so callers cannot turn a semantic search into coordinate micromanagement.
     */
    public BlockPos verifiedEvidenceAnchor() {
        return verifiedEvidence == null ? null : verifiedEvidence.position().immutable();
    }

    @Override
    protected void onStart() {
        origin = player.blockPosition().immutable();
        profile = StructureEvidenceProfiles.resolve(r.structureId);
        if (profile == null) {
            failIssue(
                    "evidence_profile_missing",
                    "No visible-evidence profile is registered for " + r.structureId
                            + ". This client cannot ask the server for a seed-based structure "
                            + "location and will not invent coordinates.",
                    FailureType.UNSUPPORTED);
            return;
        }
        String dimension = dimension();
        if (!profile.profile().dimensions().isEmpty()
                && !profile.profile().dimensions().contains(dimension)) {
            failIssue(
                    "wrong_dimension",
                    r.structureId + " is not physically searchable with its evidence profile in "
                            + dimension + "; reach one of " + profile.profile().dimensions()
                            + " first.",
                    FailureType.TARGET_LOST);
            return;
        }
        stronghold = STRONGHOLD.equals(profile.profile().canonicalId());
        if (stronghold && !OVERWORLD.equals(dimension)) {
            failIssue(
                    "wrong_dimension",
                    "Stronghold eye guidance is only physically valid in minecraft:overworld.",
                    FailureType.TARGET_LOST);
            return;
        }
        indexedLevel = player.clientLevel;
        indexedBlocks = profile.targetBlocks();
        TargetIndex.register(indexedLevel, indexedBlocks);
    }

    @Override
    protected TaskState onTick() {
        if (indexedLevel != player.clientLevel) {
            failIssue(
                    "dimension_changed",
                    "The world changed during structure search. Start a new bounded search in the "
                            + "current dimension.",
                    FailureType.INTERRUPTED);
            return TaskState.FAILED;
        }
        if (!insideScope(player.blockPosition())) {
            stopActiveChild(TaskState.FAILED);
            failIssue(
                    "search_radius_exceeded",
                    "First-person travel left the requested " + r.maxDistance
                            + "-block search radius and was stopped.",
                    FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        long now = player.level().getGameTime();
        boolean scanNow = stronghold
                || stage == Stage.OBSERVE
                || stage == Stage.VERIFY_EVIDENCE
                || now >= nextMovingEvidenceScan;
        EvidenceScan scan = scanNow
                ? scanEvidence()
                : new EvidenceScan(null, false, 0);
        if (scanNow) nextMovingEvidenceScan = now + MOVING_EVIDENCE_SCAN_INTERVAL;
        if (scan.match() != null
                && stage != Stage.WAIT_EYE_RECEIPT
                && stage != Stage.MOVE_EVIDENCE
                && stage != Stage.VERIFY_EVIDENCE) {
            stopActiveChild(TaskState.CANCELLED);
            eyeSelection.reset();
            clearEyeTracking();
            clearDirectionTravel();
            return beginEvidence(scan.match());
        }
        return switch (stage) {
            case OBSERVE -> tickObserve(scan);
            case SELECT_EYE -> tickSelectAndThrow();
            case WAIT_EYE_RECEIPT -> tickEyeReceipt();
            case TRACK_EYE -> tickEyeTracking();
            case MOVE_DIRECTION -> tickMove(false, true);
            case MOVE_FRONTIER -> tickMove(false, false);
            case MOVE_EVIDENCE -> tickMove(true, false);
            case VERIFY_EVIDENCE -> tickEvidenceVerification(scan);
        };
    }

    private TaskState tickObserve(EvidenceScan scan) {
        if (!scan.complete()) return TaskState.RUNNING;
        if (stronghold) {
            if (pendingDirection != null
                    && pendingDirectionDistance >= MIN_FRONTIER_LEG) {
                return continueDirectionTravel();
            }
            stage = Stage.SELECT_EYE;
            return TaskState.RUNNING;
        }
        return beginFrontierTravel();
    }

    private TaskState tickSelectAndThrow() {
        if (eyesConsumed >= MAX_EYE_THROWS) {
            failIssue(
                    "eye_throw_budget_exhausted",
                    "The bounded stronghold search used " + eyesConsumed
                            + " confirmed eye throws without loading an end portal frame.",
                    FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        int slot = findEyeSlot();
        if (slot < 0) {
            failIssue(
                    eyesConsumed == 0 ? "ender_eye_missing" : "ender_eyes_exhausted",
                    "No ender eye remains in the real inventory. The search stopped before "
                            + "choosing how to acquire more; it will not kill anything automatically.",
                    FailureType.NO_MATERIAL);
            return TaskState.FAILED;
        }
        if (!r.allowRareConsumables) {
            failIssue(
                    "rare_consumable_permission_required",
                    "A real ender-eye throw is now required. The task stopped before using it because "
                            + "allow_rare_consumables was not explicitly true.",
                    FailureType.INTERRUPTED);
            return TaskState.FAILED;
        }
        if (!safeEyeThrowStance()) {
            failIssue(
                    "unsafe_ender_eye_throw_stance",
                    "The task stopped before throwing an ender eye because loaded solid footing and "
                            + "clear, fluid-free body space could not be proven.",
                    FailureType.HAZARD);
            return TaskState.FAILED;
        }
        InputDriver.halt(player);
        FirstPersonActionGate.Status selected = eyeSelection.select(player, slot);
        if (selected == FirstPersonActionGate.Status.RUNNING) return TaskState.RUNNING;
        if (selected == FirstPersonActionGate.Status.FAILED) {
            failIssue(
                    "ender_eye_selection_unconfirmed",
                    "The ender eye could not be selected through synchronized first-person actions: "
                            + eyeSelection.failure(),
                    FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        if (!player.getMainHandItem().is(Items.ENDER_EYE)) return TaskState.RUNNING;
        if (eyeCount() <= 0) {
            failIssue(
                    "ender_eyes_exhausted",
                    "No ender eye remains immediately before the native use request.",
                    FailureType.NO_MATERIAL);
            return TaskState.FAILED;
        }
        if (!safeEyeThrowStance()) {
            failIssue(
                    "unsafe_ender_eye_throw_stance",
                    "Loaded safe footing changed before the native use request, so no ender eye was thrown.",
                    FailureType.HAZARD);
            return TaskState.FAILED;
        }

        throwOrigin = player.getEyePosition();
        eyeCountBeforeThrow = eyeCount();
        eyesBefore = loadedEyeUuids(player.clientLevel);
        Set<UUID> frozenBefore = Set.copyOf(eyesBefore);
        Vec3 frozenOrigin = throwOrigin;
        AABB confirmationBox = new AABB(frozenOrigin, frozenOrigin).inflate(EYE_SPAWN_RADIUS);
        var context = ClientRuntime.requireContext(player);
        eyeReceipt = context.actions().useItem(
                context,
                InteractionHand.MAIN_HAND,
                c -> c.level().getEntitiesOfClass(EyeOfEnder.class, confirmationBox).stream()
                        .anyMatch(eye -> !frozenBefore.contains(eye.getUUID()))
                        ? NativeConfirmation.Verdict.APPLIED
                        : NativeConfirmation.Verdict.PENDING,
                EYE_RECEIPT_TICKS);
        stage = Stage.WAIT_EYE_RECEIPT;
        return TaskState.RUNNING;
    }

    private TaskState tickEyeReceipt() {
        InputDriver.halt(player);
        var context = ClientRuntime.requireContext(player);
        eyeReceipt = context.actions().poll(context, eyeReceipt);
        if (!eyeReceipt.terminal()) return TaskState.RUNNING;
        if (eyeReceipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED) {
            String detail = eyeReceipt.detail();
            eyeReceipt = null;
            eyeSelection.reset();
            failIssue(
                    "eye_throw_unconfirmed",
                    "A native ender-eye use was attempted, but a new EyeOfEnder entity was not "
                            + "confirmed. The task stopped rather than risk consuming another eye: "
                            + detail,
                    FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        trackedEye = findNewEye();
        eyeReceipt = null;
        eyeSelection.reset();
        if (trackedEye == null) {
            failIssue(
                    "eye_entity_lost",
                    "The actor receipt confirmed a new EyeOfEnder, but it left the bounded loaded "
                            + "observation before its trajectory could be attached.",
                    FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        if (eyeCountBeforeThrow <= 0 || eyeCount() >= eyeCountBeforeThrow) {
            clearEyeTracking();
            failIssue(
                    "eye_consumption_unconfirmed",
                    "The task's native use receipt and EyeOfEnder evidence were confirmed, but no "
                            + "corresponding main-inventory decrease was observed. It will not count "
                            + "or repeat the use blindly.",
                    FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        eyesConsumed++;
        trackStart = trackedEye.position();
        trackLast = trackStart;
        trackTicks = 0;
        stage = Stage.TRACK_EYE;
        return TaskState.RUNNING;
    }

    private TaskState tickEyeTracking() {
        InputDriver.halt(player);
        trackTicks++;
        if (trackedEye != null && !trackedEye.isRemoved()) {
            trackLast = trackedEye.position();
        }
        double displacement = horizontalDistance(trackStart, trackLast);
        boolean enough = trackTicks >= MIN_EYE_TRACK_TICKS
                && displacement >= SETTLED_EYE_DISPLACEMENT;
        boolean ended = trackedEye == null || trackedEye.isRemoved()
                || trackTicks >= MAX_EYE_TRACK_TICKS;
        if (!enough && !ended) return TaskState.RUNNING;
        if (displacement < MIN_EYE_DISPLACEMENT) {
            clearEyeTracking();
            failIssue(
                    "eye_trajectory_unobserved",
                    "The eye throw was real, but its loaded horizontal trajectory was too short to "
                            + "choose a reliable first-person travel direction.",
                    FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        Vec3 direction = horizontalUnit(trackLast.subtract(trackStart));
        if (direction == null) {
            clearEyeTracking();
            failIssue(
                    "eye_trajectory_unobserved",
                    "The eye trajectory had no usable horizontal direction.",
                    FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        if (previousDirection != null) {
            double dot = previousDirection.x * direction.x + previousDirection.z * direction.z;
            if (dot < REVERSE_DOT) {
                directionStep = Math.max(MIN_DIRECTION_STEP, directionStep * 0.5);
                directionReversals++;
            } else if (dot < 0.5) {
                directionStep = Math.max(MIN_DIRECTION_STEP, directionStep * 0.75);
            }
        }
        previousDirection = direction;
        clearEyeTracking();
        return beginDirectionTravel(direction);
    }

    private TaskState beginDirectionTravel(Vec3 direction) {
        double travelled = horizontalDistance(origin, player.blockPosition());
        double remaining = r.maxDistance - travelled;
        if (remaining < MIN_FRONTIER_LEG) {
            failIssue(
                    "max_distance_reached",
                    "The eye still pointed onward at the edge of the requested search radius.",
                    FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        pendingDirection = direction;
        pendingDirectionDistance = Math.min(directionStep, remaining);
        directionLoadWaitTicks = 0;
        directionLegs++;
        return continueDirectionTravel();
    }

    private TaskState continueDirectionTravel() {
        if (pendingDirection == null
                || pendingDirectionDistance < MIN_FRONTIER_LEG) {
            clearDirectionTravel();
            stage = Stage.OBSERVE;
            return TaskState.RUNNING;
        }
        BlockPos desired = new BlockPos(
                (int) Math.round(player.getX()
                        + pendingDirection.x * pendingDirectionDistance),
                player.blockPosition().getY(),
                (int) Math.round(player.getZ()
                        + pendingDirection.z * pendingDirectionDistance));
        BlockPos frontier = loadedFrontierToward(desired);
        if (frontier == null) {
            stage = Stage.OBSERVE;
            if (++directionLoadWaitTicks <= 40) return TaskState.RUNNING;
            clearDirectionTravel();
            failIssue(
                    "eye_direction_not_traversable",
                    "The eye supplied a real direction, but no loaded first-person frontier in that "
                            + "direction became available for a bounded route.",
                    FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        directionLoadWaitTicks = 0;
        directionSegmentDistance = horizontalDistance(
                player.blockPosition(), frontier);
        startMove(frontier, false);
        directionSegments++;
        stage = Stage.MOVE_DIRECTION;
        return TaskState.RUNNING;
    }

    private TaskState beginFrontierTravel() {
        if (frontierAttempts >= MAX_FRONTIER_LEGS) {
            failIssue(
                    "frontier_budget_exhausted",
                    "Bounded first-person frontier exploration finished without a matching loaded "
                            + "evidence cluster for " + r.structureId + ".",
                    FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        BlockPos frontier = nextFrontier();
        if (frontier == null) {
            failIssue(
                    "no_loaded_frontier",
                    "No new loaded first-person frontier remained inside the requested search radius.",
                    FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        frontierAttempts++;
        startMove(frontier, false);
        stage = Stage.MOVE_FRONTIER;
        return TaskState.RUNNING;
    }

    private TaskState beginEvidence(EvidenceMatch match) {
        activeEvidence = match;
        if (!r.reachStructure) {
            verifiedEvidence = match;
            return TaskState.SUCCESS;
        }
        if (evidenceApproaches >= MAX_EVIDENCE_APPROACHES) {
            failIssue(
                    "evidence_unreachable",
                    "Visible evidence was repeatedly observed, but no approach could be completed "
                            + "under the allowed terrain policy.",
                    FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        evidenceApproaches++;
        startMove(match.position(), true);
        stage = Stage.MOVE_EVIDENCE;
        return TaskState.RUNNING;
    }

    private TaskState tickMove(boolean evidenceMove, boolean directionMove) {
        if (moveChild == null) {
            failIssue(
                    "internal_route_lost",
                    "The active first-person route disappeared.",
                    FailureType.INTERNAL);
            return TaskState.FAILED;
        }
        TaskState terminal;
        if (player.level().getGameTime() >= legDeadline) {
            moveChild.stop(player, Task.StopReason.REPLACED);
            terminal = TaskState.TIMEOUT;
        } else {
            terminal = runChild(moveChild);
            if (terminal == null) return TaskState.RUNNING;
        }
        TaskResult result = moveChild.result(terminal);
        moveChild = null;

        if (terminal == TaskState.SUCCESS && result != null && result.success()) {
            if (evidenceMove) {
                stage = Stage.VERIFY_EVIDENCE;
            } else if (directionMove) {
                pendingDirectionDistance = Math.max(
                        0.0, pendingDirectionDistance - directionSegmentDistance);
                directionSegmentDistance = 0.0;
                if (pendingDirectionDistance < MIN_FRONTIER_LEG) {
                    clearDirectionTravel();
                }
                stage = Stage.OBSERVE;
            } else {
                frontierReached++;
                stage = Stage.OBSERVE;
            }
            return TaskState.RUNNING;
        }
        recordRouteFailure(terminal);
        if (evidenceMove) {
            if (activeEvidence != null) rejectedEvidence.add(activeEvidence.position().asLong());
            activeEvidence = null;
            if (stronghold) {
                failIssue(
                        "evidence_unreachable",
                        "An end portal frame was verified in loaded world facts, but the first-person "
                                + "route to it failed under the allowed terrain policy. No more eyes "
                                + "will be thrown for a structure that is already located.",
                        FailureType.NO_PATH);
                return TaskState.FAILED;
            }
            if (evidenceApproaches >= MAX_EVIDENCE_APPROACHES) {
                failIssue(
                        "evidence_unreachable",
                        "Loaded evidence was found, but first-person approach failed under the "
                                + "allowed terrain policy.",
                        FailureType.NO_PATH);
                return TaskState.FAILED;
            }
            stage = Stage.OBSERVE;
            return TaskState.RUNNING;
        }
        if (directionMove) {
            clearDirectionTravel();
            failIssue(
                    "eye_direction_route_failed",
                    "A real eye trajectory supplied the direction, but the first-person route in "
                            + "that direction failed under the allowed terrain policy.",
                    FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        frontierFailed++;
        stage = Stage.OBSERVE;
        return TaskState.RUNNING;
    }

    private TaskState tickEvidenceVerification(EvidenceScan scan) {
        if (!scan.complete()) return TaskState.RUNNING;
        EvidenceMatch match = scan.match();
        if (match != null && activeEvidence != null
                && horizontalDistance(match.position(), activeEvidence.position())
                        <= profile.profile().clusterRadius() * 2.0) {
            verifiedEvidence = match;
            return TaskState.SUCCESS;
        }
        if (activeEvidence != null) rejectedEvidence.add(activeEvidence.position().asLong());
        activeEvidence = null;
        stage = Stage.OBSERVE;
        return TaskState.RUNNING;
    }

    private EvidenceScan scanEvidence() {
        int chunkRadius = Math.max(
                1, (Math.min(EVIDENCE_SCAN_RADIUS, r.maxDistance) + 15) / 16);
        Map<Long, BlockPos> merged = new LinkedHashMap<>();
        boolean complete = true;
        for (StructureEvidenceProfiles.ResolvedGroup group : profile.groups()) {
            TargetIndex.Result result = TargetIndex.query(
                    player.clientLevel,
                    player.blockPosition(),
                    group.blocks(),
                    MAX_GROUP_HITS,
                    chunkRadius,
                    INDEX_BUILD_BUDGET_PER_GROUP);
            complete &= result.complete();
            for (BlockPos hit : result.hits()) {
                if (!insideScope(hit) || !liveTargetBlock(hit)) continue;
                merged.putIfAbsent(hit.asLong(), hit.immutable());
            }
        }
        List<BlockPos> hits = new ArrayList<>(merged.values());
        hits.sort(Comparator.comparingDouble(
                position -> position.distSqr(player.blockPosition())));
        return new EvidenceScan(matchEvidence(hits), complete, hits.size());
    }

    private EvidenceMatch matchEvidence(List<BlockPos> hits) {
        if (hits.isEmpty()) return null;
        double radiusSqr = (double) profile.profile().clusterRadius()
                * profile.profile().clusterRadius();
        for (BlockPos anchor : hits) {
            if (evidenceRejected(anchor)) continue;
            Map<String, Integer> groups = new LinkedHashMap<>();
            Map<String, Integer> blocks = new LinkedHashMap<>();
            int total = 0;
            for (BlockPos hit : hits) {
                if (hit.distSqr(anchor) > radiusSqr) continue;
                total++;
                Block live = player.clientLevel.getBlockState(hit).getBlock();
                String id = BuiltInRegistries.BLOCK.getKey(live).toString();
                blocks.merge(id, 1, Integer::sum);
            }
            boolean allGroups = true;
            for (StructureEvidenceProfiles.ResolvedGroup group : profile.groups()) {
                int count = 0;
                for (BlockPos hit : hits) {
                    if (hit.distSqr(anchor) <= radiusSqr
                            && group.blocks().contains(
                                    player.clientLevel.getBlockState(hit).getBlock())) {
                        count++;
                    }
                }
                groups.put(group.label(), count);
                if (count < group.minimum()) allGroups = false;
            }
            if (allGroups && total >= profile.profile().minimumTotal()) {
                return new EvidenceMatch(
                        anchor.immutable(),
                        Map.copyOf(groups),
                        Map.copyOf(blocks),
                        total);
            }
        }
        return null;
    }

    private boolean liveTargetBlock(BlockPos position) {
        return player.clientLevel.isLoaded(position)
                && indexedBlocks.contains(player.clientLevel.getBlockState(position).getBlock());
    }

    private boolean evidenceRejected(BlockPos position) {
        if (r.evidenceExclusionRadius > 0) {
            long exclusionRadiusSqr = (long) r.evidenceExclusionRadius
                    * r.evidenceExclusionRadius;
            for (BlockPos excluded : r.excludedEvidenceAnchors) {
                if (horizontalDistanceSquared(position, excluded) <= exclusionRadiusSqr) {
                    return true;
                }
            }
        }
        double radiusSqr = (double) profile.profile().clusterRadius()
                * profile.profile().clusterRadius();
        for (long packed : rejectedEvidence) {
            if (position.distSqr(BlockPos.of(packed)) <= radiusSqr) return true;
        }
        return false;
    }

    private static long horizontalDistanceSquared(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private void startMove(BlockPos target, boolean exact) {
        long now = player.level().getGameTime();
        String parentCall = r.getToolCallId() == null ? "structure-search" : r.getToolCallId();
        MoveToTaskRecord moveRecord = new MoveToTaskRecord(
                parentCall + "-internal-structure-leg-" + (++legSerial),
                now + LEG_TIMEOUT_TICKS,
                (double) target.getX(),
                exact ? (double) target.getY() : null,
                (double) target.getZ(),
                null,
                r.mayAlterTerrain);
        moveChild = new MoveToCompanionTask(player, moveRecord);
        legDeadline = now + LEG_TIMEOUT_TICKS;
    }

    private BlockPos nextFrontier() {
        for (int probe = 0; probe < MAX_FRONTIER_PROBES; probe++) {
            BlockPos desired = nextSpiralPoint();
            if (!insideScope(desired)) continue;
            BlockPos frontier = loadedFrontierToward(desired);
            if (frontier == null) continue;
            long key = BlockPos.asLong(frontier.getX(), 0, frontier.getZ());
            if (attemptedFrontiers.add(key)) return frontier;
        }
        return null;
    }

    private BlockPos nextSpiralPoint() {
        switch (spiralDirection) {
            case 0 -> spiralX++;
            case 1 -> spiralZ++;
            case 2 -> spiralX--;
            default -> spiralZ--;
        }
        spiralSegmentProgress++;
        if (spiralSegmentProgress >= spiralSegmentLength) {
            spiralSegmentProgress = 0;
            spiralDirection = (spiralDirection + 1) & 3;
            if (++spiralSegmentsAtLength >= 2) {
                spiralSegmentsAtLength = 0;
                spiralSegmentLength++;
            }
        }
        return new BlockPos(
                origin.getX() + spiralX * FRONTIER_GRID,
                player.blockPosition().getY(),
                origin.getZ() + spiralZ * FRONTIER_GRID);
    }

    private BlockPos loadedFrontierToward(BlockPos desired) {
        BlockPos current = player.blockPosition();
        double dx = desired.getX() - current.getX();
        double dz = desired.getZ() - current.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < MIN_FRONTIER_LEG) return null;
        double farthest = Math.min(MAX_FRONTIER_LEG, distance);
        for (double leg = farthest;
                leg >= Math.min(MIN_FRONTIER_LEG, farthest);
                leg -= 8.0) {
            int x = (int) Math.round(current.getX() + dx / distance * leg);
            int z = (int) Math.round(current.getZ() + dz / distance * leg);
            BlockPos candidate = new BlockPos(x, current.getY(), z);
            if (insideScope(candidate) && columnLoaded(x, z)) return candidate;
        }
        return null;
    }

    private boolean columnLoaded(int x, int z) {
        int y = Math.clamp(
                player.blockPosition().getY(),
                player.clientLevel.getMinBuildHeight(),
                player.clientLevel.getMaxBuildHeight() - 1);
        return player.clientLevel.isLoaded(new BlockPos(x, y, z));
    }

    private int findEyeSlot() {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.is(Items.ENDER_EYE)) return slot;
        }
        return -1;
    }

    private int eyeCount() {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.is(Items.ENDER_EYE)) count += stack.getCount();
        }
        return count;
    }

    private boolean safeEyeThrowStance() {
        ClientLevel level = player.clientLevel;
        BlockPos feet = player.blockPosition();
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        if (!player.onGround() || player.fallDistance > 0.5F
                || feet.getY() <= level.getMinBuildHeight() + 2) return false;
        if (!level.isLoaded(feet) || !level.isLoaded(head) || !level.isLoaded(floor)) return false;
        if (!level.getFluidState(feet).isEmpty()
                || !level.getFluidState(head).isEmpty()
                || !level.getFluidState(floor).isEmpty()) return false;
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                || !level.getBlockState(head).getCollisionShape(level, head).isEmpty()) return false;
        return level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
    }

    private Set<UUID> loadedEyeUuids(ClientLevel level) {
        AABB box = new AABB(
                player.position(), player.position()).inflate(EYE_REACQUIRE_RADIUS);
        Set<UUID> ids = new HashSet<>();
        for (EyeOfEnder eye : level.getEntitiesOfClass(EyeOfEnder.class, box)) {
            ids.add(eye.getUUID());
        }
        return Set.copyOf(ids);
    }

    private EyeOfEnder findNewEye() {
        AABB box = new AABB(throwOrigin, throwOrigin).inflate(EYE_REACQUIRE_RADIUS);
        return player.clientLevel.getEntitiesOfClass(EyeOfEnder.class, box).stream()
                .filter(eye -> !eyesBefore.contains(eye.getUUID()))
                .min(Comparator.comparingDouble(
                        eye -> eye.position().distanceToSqr(throwOrigin)))
                .orElse(null);
    }

    private void clearEyeTracking() {
        trackedEye = null;
        trackStart = null;
        trackLast = null;
        trackTicks = 0;
        eyesBefore = Set.of();
        throwOrigin = null;
        eyeCountBeforeThrow = -1;
    }

    private void clearDirectionTravel() {
        pendingDirection = null;
        pendingDirectionDistance = 0.0;
        directionSegmentDistance = 0.0;
        directionLoadWaitTicks = 0;
    }

    private void recordRouteFailure(TaskState terminal) {
        if (routeFailureKinds.size() >= 16) return;
        routeFailureKinds.add(switch (terminal) {
            case TIMEOUT -> "timeout";
            case CANCELLED -> "cancelled";
            case FAILED -> "route_failed";
            default -> "not_confirmed";
        });
    }

    private boolean insideScope(BlockPos position) {
        double dx = position.getX() - origin.getX();
        double dz = position.getZ() - origin.getZ();
        double limit = r.maxDistance + SCOPE_TOLERANCE;
        return dx * dx + dz * dz <= limit * limit;
    }

    private String dimension() {
        return player.level().dimension().location().toString();
    }

    private void failIssue(String code, String message, FailureType type) {
        issueCode = code;
        fail(message, type);
    }

    private static Vec3 horizontalUnit(Vec3 vector) {
        double length = Math.sqrt(vector.x * vector.x + vector.z * vector.z);
        if (length < 1.0e-6) return null;
        return new Vec3(vector.x / length, 0.0, vector.z / length);
    }

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double horizontalDistance(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void stopActiveChild(TaskState terminal) {
        if (moveChild == null) return;
        try {
            moveChild.stop(player, Task.StopReason.REPLACED);
            moveChild.result(terminal);
        } finally {
            moveChild = null;
        }
    }

    @Override
    protected void cleanup() {
        if (cleaned) return;
        cleaned = true;
        stopActiveChild(TaskState.CANCELLED);
        InputDriver.halt(player);
        eyeSelection.reset();
        eyeReceipt = null;
        clearEyeTracking();
        clearDirectionTravel();
        if (indexedLevel != null && !indexedBlocks.isEmpty()) {
            TargetIndex.unregister(indexedLevel, indexedBlocks);
        }
        indexedLevel = null;
        indexedBlocks = Set.of();
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("structure_id", r.structureId);
        data.put(
                "canonical_profile",
                profile == null ? r.structureId : profile.profile().canonicalId());
        data.put("verified", verifiedEvidence != null);
        data.put("reached", verifiedEvidence != null && r.reachStructure);
        data.put("scope", "loaded_client_facts_and_first_person_travel_only");
        data.put("max_distance", r.maxDistance);
        data.put("frontier_legs_attempted", frontierAttempts);
        data.put("frontier_legs_reached", frontierReached);
        data.put("frontier_legs_failed", frontierFailed);
        data.put("evidence_approaches", evidenceApproaches);
        if (!routeFailureKinds.isEmpty()) {
            data.put("route_failure_kinds", List.copyOf(routeFailureKinds));
        }
        if (stronghold) {
            data.put("consumed", Map.of(
                    "item_id", "minecraft:ender_eye",
                    "count", eyesConsumed));
            data.put("eye_direction_legs", directionLegs);
            data.put("eye_direction_segments", directionSegments);
            data.put("eye_direction_reversals", directionReversals);
        }
        if (verifiedEvidence != null) {
            data.put("evidence", Map.of(
                    "description", profile.profile().evidenceDescription(),
                    "total_profile_blocks", verifiedEvidence.totalBlocks(),
                    "group_counts", verifiedEvidence.groupCounts(),
                    "block_counts", verifiedEvidence.blockCounts(),
                    "authority", "observed_loaded_world"));
        }
        if (issueCode != null) {
            data.put("issue_code", issueCode);
            data.put("requires_decision", true);
            data.put("recovery_options", recoveryOptions());
        } else if (verifiedEvidence == null) {
            data.put("recovery_options", recoveryOptions());
        }
        return data;
    }

    private List<Map<String, Object>> recoveryOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        if ("rare_consumable_permission_required".equals(issueCode)) {
            options.add(Map.of(
                    "choice", "retry",
                    "description", "Retry with details.parameters.allow_rare_consumables=true."));
            options.add(Map.of(
                    "choice", "skip",
                    "description", "Skip the stronghold search without throwing an ender eye."));
            options.add(Map.of(
                    "choice", "cancel",
                    "description", "Cancel the task with zero rare-consumable use."));
            return List.copyOf(options);
        }
        if ("evidence_profile_missing".equals(issueCode)) {
            options.add(Map.of(
                    "choice", "register_evidence_profile",
                    "description",
                    "Register visible block evidence for this vanilla or Mod structure, then retry."));
            options.add(Map.of(
                    "choice", "stop",
                    "description", "Stop rather than guess a location."));
            return List.copyOf(options);
        }
        if ("wrong_dimension".equals(issueCode) || "dimension_changed".equals(issueCode)) {
            options.add(Map.of(
                    "choice", "travel_dimension",
                    "description",
                    "Reach a dimension supported by this structure's physical evidence."));
            options.add(Map.of(
                    "choice", "stop",
                    "description", "Keep the current dimension and stop this search."));
            return List.copyOf(options);
        }
        if ((issueCode != null && issueCode.startsWith("ender_eye"))
                || "eye_throw_budget_exhausted".equals(issueCode)) {
            options.add(Map.of(
                    "choice", "acquire",
                    "item", "minecraft:ender_eye",
                    "description",
                    "Acquire ender eyes through a separately approved semantic task."));
            options.add(Map.of(
                    "choice", "stop",
                    "description",
                    "Stop; do not choose mobs, villagers or protected resources automatically."));
            return List.copyOf(options);
        }
        options.add(Map.of(
                "choice", "increase_search_bound",
                "description",
                "Retry with a larger max_distance if more first-person travel is acceptable."));
        if (!r.mayAlterTerrain) {
            options.add(Map.of(
                    "choice", "allow_route_changes",
                    "description",
                    "Retry with may_alter_terrain only after deciding that digging/bridging is acceptable."));
        }
        options.add(Map.of(
                "choice", "stop",
                "description", "Stop without inventing coordinates or widening the goal."));
        return List.copyOf(options);
    }

    @Override
    protected String successMessage() {
        return r.reachStructure
                ? "physically reached and re-verified " + r.structureId
                : "verified loaded physical evidence for " + r.structureId;
    }

    @Override
    protected String timeoutMessage() {
        return "physical structure search timed out before " + r.structureId
                + " was verified; no hidden locate result was substituted";
    }

    @Override
    protected String cancelledMessage() {
        return "physical structure search was interrupted";
    }
}
