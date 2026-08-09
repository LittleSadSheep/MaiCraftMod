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
