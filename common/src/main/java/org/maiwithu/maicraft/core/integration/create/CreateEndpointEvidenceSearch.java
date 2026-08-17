// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tick-sliced physical endpoint discovery around one semantic anchor.
 *
 * <p>The search has no model-authored or implementation-authored maximum radius. It observes
 * loaded chunk evidence in expanding rings and asks the first-person survey to load the next
 * missing ring chunk. A candidate becomes authoritative only after every chunk that could contain
 * a closer candidate has been observed. An empty world therefore does not turn an arbitrary
 * radius into a false "not found": it ends only at world-border exhaustion or when first-person
 * travel reports that the next required observation frontier is unreachable.</p>
 */
final class CreateEndpointEvidenceSearch {
    enum Status { RUNNING, NEEDS_OBSERVATION, READY, EXHAUSTED }

    record Snapshot(
            List<CreateMechanicalPlan.KineticEndpoint> endpoints,
            List<BlockPos> freeReceivers,
            int observedChunks,
            int missingChunks,
            int poweredEndpoints,
            BlockPos nextObservation) {}

    private record EndpointKey(long position, Direction face) {}

    private static final int CHUNKS_PER_TICK = 2;
    private static final int FREE_CELLS_PER_TICK = 96;

    private final CreateMechanicalPower.Endpoint endpoint;
    private final boolean poweredOnly;
    private final boolean allowFreeReceiver;
    private final Map<EndpointKey, CreateMechanicalPlan.KineticEndpoint> endpoints =
            new LinkedHashMap<>();
    private int chunkRing;
    private long ringIndex;
    private long ringSize = 1L;
    private int eligibleChunksInRing;
    private long firstMissingRingIndex = -1L;
    private BlockPos firstMissingObservation;
    private double missingChunkLowerBoundSq = Double.POSITIVE_INFINITY;
    private int observedChunks;
    private BlockPos nextObservation;
    private int freeRadius;
    private long freeShellIndex;
    private BlockPos pendingFreeProbe;
    private boolean freeReceiverProven;
    private boolean freeSearchExhausted;
    private boolean kineticSearchExhausted;
    private BlockPos freeReceiver;
    private Status status = Status.RUNNING;
    private long progressRevision;

    CreateEndpointEvidenceSearch(
            CreateMechanicalPower.Endpoint endpoint,
            boolean poweredOnly,
            boolean allowFreeReceiver) {
        this.endpoint = endpoint;
        this.poweredOnly = poweredOnly;
        this.allowFreeReceiver = allowFreeReceiver;
    }

    Status tick(ClientLevel level) {
        if (status == Status.READY || status == Status.EXHAUSTED) return status;
        if (nextObservation != null) {
            if (!level.isLoaded(nextObservation)) return Status.NEEDS_OBSERVATION;
            nextObservation = null;
            markProgress();
        }

        if (kineticSearchExhausted) {
            if (allowFreeReceiver && !freeReceiverProven && !freeSearchExhausted) {
                scanFreeReceiverEvidence(level);
                if (pendingFreeProbe != null && !level.isLoaded(pendingFreeProbe)) {
                    nextObservation = pendingFreeProbe.immutable();
                    return Status.NEEDS_OBSERVATION;
                }
            }
            return finishAfterKineticExhaustion();
        }

        int budget = CHUNKS_PER_TICK;
        while (budget-- > 0) {
            if (ringIndex >= ringSize) {
                double unseenLowerBound = Math.min(missingChunkLowerBoundSq,
                        ringMinimumHorizontalDistanceSq(level, chunkRing + 1));
                if (evidenceProvenAgainst(unseenLowerBound)) {
                    status = Status.READY;
                    markProgress();
                    return status;
                }
                if (firstMissingRingIndex >= 0L) {
                    ringIndex = firstMissingRingIndex;
                    nextObservation = firstMissingObservation;
                    firstMissingRingIndex = -1L;
                    firstMissingObservation = null;
                    missingChunkLowerBoundSq = Double.POSITIVE_INFINITY;
                    markProgress();
                    return Status.NEEDS_OBSERVATION;
                }
                if (eligibleChunksInRing == 0 && chunkRing > 0) {
                    kineticSearchExhausted = true;
                    markProgress();
                    return finishAfterKineticExhaustion();
                }
                chunkRing++;
                ringIndex = 0;
                ringSize = 8L * chunkRing;
                eligibleChunksInRing = 0;
                firstMissingRingIndex = -1L;
                firstMissingObservation = null;
                missingChunkLowerBoundSq = Double.POSITIVE_INFINITY;
                markProgress();
                continue;
            }

            ChunkPos chunkPos = chunkAt(endpoint.center(), chunkRing, ringIndex);
            if (!intersectsWorldBorder(level, chunkPos)) {
                ringIndex++;
                markProgress();
                continue;
            }
            eligibleChunksInRing++;
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
            if (chunk == null) {
                if (firstMissingRingIndex < 0L) {
                    firstMissingRingIndex = ringIndex;
                    firstMissingObservation = observationTarget(level, chunkPos);
                }
                missingChunkLowerBoundSq = Math.min(missingChunkLowerBoundSq,
                        chunkMinimumHorizontalDistanceSq(endpoint.center(), chunkPos));
                ringIndex++;
                markProgress();
                continue;
            }
            scanKineticEvidence(level, chunk);
            observedChunks++;
            ringIndex++;
            markProgress();
        }
        if (allowFreeReceiver && !freeReceiverProven && !freeSearchExhausted) {
            scanFreeReceiverEvidence(level);
        }
        return Status.RUNNING;
    }

    Snapshot snapshot() {
        List<CreateMechanicalPlan.KineticEndpoint> observed = endpoints.values().stream()
                .sorted(Comparator.comparingDouble(
                                (CreateMechanicalPlan.KineticEndpoint value) ->
                                value.position().distSqr(endpoint.center()))
                        .thenComparingLong(value -> value.position().asLong())
                        .thenComparingInt(value -> value.shaftFace().ordinal()))
                .toList();
        int powered = (int) observed.stream().filter(value -> value.network()
                && Math.abs(value.speed()) > 0.0001f).count();
        double authoritativeDistanceSq = status == Status.READY
                ? bestAdmissibleEvidenceDistanceSq() : Double.POSITIVE_INFINITY;
        List<CreateMechanicalPlan.KineticEndpoint> ordered = status != Status.READY
                ? observed
                : observed.stream()
                        .filter(this::admissibleKineticEndpoint)
                        .filter(value -> Double.compare(
                                value.position().distSqr(endpoint.center()),
                                authoritativeDistanceSq) == 0)
                        .toList();
        boolean exposeFreeReceiver = freeReceiverProven && freeReceiver != null
                && (status != Status.READY || Double.compare(
                        freeReceiver.distSqr(endpoint.center()),
                        authoritativeDistanceSq) == 0);
        return new Snapshot(ordered,
                exposeFreeReceiver ? List.of(freeReceiver) : List.of(),
                observedChunks, nextObservation == null ? 0 : 1, powered,
                nextObservation == null ? null : nextObservation.immutable());
    }

    long progressRevision() {
        return progressRevision;
    }

    private Status scanFreeReceiverEvidence(ClientLevel level) {
        int budget = FREE_CELLS_PER_TICK;
        while (budget-- > 0 && !freeReceiverProven && !freeSearchExhausted) {
            BlockPos candidate = pendingFreeProbe == null
                    ? nextFreeProbe(level) : pendingFreeProbe;
            if (candidate == null) {
                freeSearchExhausted = true;
                if (freeReceiver != null) freeReceiverProven = true;
                markProgress();
                break;
            }
            if (level.isOutsideBuildHeight(candidate)
                    || !level.getWorldBorder().isWithinBounds(candidate)) {
                pendingFreeProbe = null;
                markProgress();
                continue;
            }
            if (!level.isLoaded(candidate)) {
                pendingFreeProbe = candidate;
                return Status.RUNNING;
            }
            pendingFreeProbe = null;
            if (CreateMechanicalPlanner.isFreeReceiverCell(level, candidate)) {
                if (freeReceiver == null || candidate.distSqr(endpoint.center())
                        < freeReceiver.distSqr(endpoint.center())) {
                    freeReceiver = candidate.immutable();
                }
            }
            markProgress();
            if (freeReceiver != null && freeShellIndex == 0L
                    && (double) freeRadius * freeRadius
                            > freeReceiver.distSqr(endpoint.center())) {
                freeReceiverProven = true;
                markProgress();
            }
        }
        return Status.RUNNING;
    }

    private Status finishAfterKineticExhaustion() {
        if (allowFreeReceiver && !freeReceiverProven && !freeSearchExhausted) {
            return Status.RUNNING;
        }
        status = freeReceiverProven ? Status.READY : Status.EXHAUSTED;
        markProgress();
        return status;
    }

    /** Returns exactly one non-duplicated expanding-shell probe covered by the tick budget. */
    private BlockPos nextFreeProbe(ClientLevel level) {
        if (freeRadius > freeWorldEvidenceRadius(level)) return null;
        if (freeRadius == 0) {
            freeRadius = 1;
            return endpoint.center();
        }
        int[] offset = shellOffset(freeRadius, freeShellIndex++);
        long side = 2L * freeRadius + 1L;
        long inner = side - 2L;
        long shellSize = 2L * side * side + 2L * inner * side
                + 2L * inner * inner;
        if (freeShellIndex >= shellSize) {
            freeRadius++;
            freeShellIndex = 0L;
        }
        return endpoint.center().offset(offset[0], offset[1], offset[2]).immutable();
    }

    private static int[] shellOffset(int radius, long index) {
        long side = 2L * radius + 1L;
        long inner = side - 2L;
        long faceArea = side * side;
        if (index < faceArea) {
            return new int[]{(int) (index / side) - radius, -radius,
                    (int) (index % side) - radius};
        }
        index -= faceArea;
        if (index < faceArea) {
            return new int[]{(int) (index / side) - radius, radius,
                    (int) (index % side) - radius};
        }
        index -= faceArea;
        long sideArea = inner * side;
        if (index < sideArea) {
            return new int[]{-radius, (int) (index / side) - radius + 1,
                    (int) (index % side) - radius};
        }
        index -= sideArea;
        if (index < sideArea) {
            return new int[]{radius, (int) (index / side) - radius + 1,
                    (int) (index % side) - radius};
        }
        index -= sideArea;
        long edgeArea = inner * inner;
        if (index < edgeArea) {
            return new int[]{(int) (index % inner) - radius + 1,
                    (int) (index / inner) - radius + 1, -radius};
        }
        index -= edgeArea;
        return new int[]{(int) (index % inner) - radius + 1,
                (int) (index / inner) - radius + 1, radius};
    }

    private int freeWorldEvidenceRadius(ClientLevel level) {
        var border = level.getWorldBorder();
        int minimumX = (int) Math.ceil(border.getMinX());
        int maximumX = (int) Math.floor(Math.nextDown(border.getMaxX()));
        int minimumZ = (int) Math.ceil(border.getMinZ());
        int maximumZ = (int) Math.floor(Math.nextDown(border.getMaxZ()));
        BlockPos center = endpoint.center();
