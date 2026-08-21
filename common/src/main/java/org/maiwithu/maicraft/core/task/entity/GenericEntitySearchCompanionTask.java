// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.moves.MovementHelper;
import org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeight;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** Loaded evidence first, then bounded first-person frontier travel and another loaded scan. */
public final class GenericEntitySearchCompanionTask
        extends AbstractCompanionTask<GenericEntitySearchTaskRecord> {
    public static final int LOADED_EVIDENCE_RADIUS = 112;
    private static final int WAYPOINT_GRID = 64;
    private static final int MAX_LEG_DISTANCE = 80;
    /** A frontier should expose another chunk-width of terrain, not circle in place. */
    private static final int MIN_FRONTIER_PROGRESS = 16;
    /** Look beside the geometric ray for a dry observation post before accepting water. */
    private static final int LAND_FRONTIER_PROBE_RADIUS = WAYPOINT_GRID / 2;
    private static final int LAND_FRONTIER_PROBE_STRIDE = 4;
    /** Initial leg lease. MoveTo renews its own record while verified route progress continues. */
    private static final int INITIAL_LEG_LEASE_TICKS = 90 * 20;
    private static final int SCOPE_TOLERANCE = 8;
    private static final int MAX_SPIRAL_PROBES = 10_000;

    private enum Stage { OBSERVE, TRAVEL_FRONTIER }

    private BlockPos origin;
    private Stage stage;
    private MoveToCompanionTask moveChild;
    private MoveToTaskRecord moveRecord;
    private int legSerial;
    private int scanCycles;
    private int frontierAttempts;
    private int frontierReached;
    private int frontierFailed;
    private double farthestBodyDistance;
    private String failureCode;
    private boolean preferLandFrontiers;

    private final Set<Long> attemptedFrontiers = new LinkedHashSet<>();
    private final Map<UUID, ResourceLocation> observedSafe = new LinkedHashMap<>();
    private final Set<UUID> observedProtected = new LinkedHashSet<>();
    private final Map<String, Integer> protectedReasonCounts = new LinkedHashMap<>();
    private int protectedOrAmbiguousSeen;
    private int lastLoadedMatching;

    private int spiralX;
    private int spiralZ;
    private int spiralDirection;
    private int spiralSegmentLength = 1;
    private int spiralSegmentProgress;
    private int spiralSegmentsAtLength;

    public GenericEntitySearchCompanionTask(
            LocalPlayer player, GenericEntitySearchTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        origin = player.blockPosition().immutable();
        preferLandFrontiers = targetsUseGroundSpawnPlacement();
        stage = Stage.OBSERVE;
    }

    @Override
    protected TaskState onTick() {
        double bodyDistance = horizontalDistance(origin, player.blockPosition());
        farthestBodyDistance = Math.max(farthestBodyDistance, bodyDistance);
        if (bodyDistance > r.maxDistance + SCOPE_TOLERANCE) {
            stopMove(TaskState.FAILED);
            return failSearch(
                    "entity_search_left_bound",
                    "first-person movement left the bounded entity-search radius and was stopped",
                    FailureType.NO_PATH);
        }
        return switch (stage) {
            case OBSERVE -> observeThenContinue();
            case TRAVEL_FRONTIER -> tickFrontierTravel();
        };
    }

    private TaskState observeThenContinue() {
        scanCycles++;
        scanLoadedEntities();
        if (observedSafe.size() >= r.count) return TaskState.SUCCESS;
        if (frontierAttempts >= r.maxWaypoints) return exhausted();

        BlockPos frontier = nextFrontier(player.clientLevel);
        if (frontier == null) return exhausted();
        frontierAttempts++;
        startMove(frontier);
        stage = Stage.TRAVEL_FRONTIER;
        return TaskState.RUNNING;
    }

    private void scanLoadedEntities() {
        ClientLevel level = player.clientLevel;
        AABB box = player.getBoundingBox().inflate(LOADED_EVIDENCE_RADIUS);
        int loadedMatching = 0;
        for (Entity entity : level.getEntities(player, box, candidate ->
                candidate != player && !candidate.isRemoved() && candidate.isAlive())) {
            ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (!r.entityTypeIds.contains(type) || !insideScope(entity.getX(), entity.getZ())) {
                continue;
            }
            if (!EntitySemanticSafety.matchesRelation(entity, r.relation)) continue;
            loadedMatching++;
            if (r.harmIntent && !entity.isAttackable()) {
                if (observedProtected.add(entity.getUUID())) {
                    protectedOrAmbiguousSeen++;
                    protectedReasonCounts.merge("not_attackable", 1, Integer::sum);
                }
                continue;
            }
            List<String> reasons = EntitySemanticSafety.protectionReasons(
                    player, entity, r.relation, r.protectedLabels, r.harmIntent);
            if (reasons.isEmpty()) {
                observedSafe.putIfAbsent(entity.getUUID(), type);
            } else if (observedProtected.add(entity.getUUID())) {
                protectedOrAmbiguousSeen++;
                for (String reason : reasons) protectedReasonCounts.merge(reason, 1, Integer::sum);
            }
        }
        lastLoadedMatching = loadedMatching;
    }

    private void startMove(BlockPos target) {
        long now = player.level().getGameTime();
        String parent = r.getToolCallId() == null ? "find-entity" : r.getToolCallId();
        moveRecord = new MoveToTaskRecord(
                parent + "-internal-frontier-" + (++legSerial),
                now + INITIAL_LEG_LEASE_TICKS,
                (double) target.getX(), null, (double) target.getZ(), null,
                r.mayAlterTerrain);
        moveChild = new MoveToCompanionTask(player, moveRecord);
    }

    private TaskState tickFrontierTravel() {
        // A frontier leg exists only to load more evidence. Newly loaded acceptable evidence
        // therefore invalidates the leg immediately; waiting until the waypoint was reached made
        // the body visibly walk past the very entity it was searching for.
        scanCycles++;
        scanLoadedEntities();
        if (observedSafe.size() >= r.count) {
            stopMove(TaskState.CANCELLED);
            return TaskState.SUCCESS;
        }

        TaskState terminal;
        if (player.level().getGameTime() >= moveRecord.getDeadlineGameTime()) {
            moveChild.stop(player, Task.StopReason.REPLACED);
            terminal = TaskState.TIMEOUT;
        } else {
            terminal = runChild(moveChild);
            if (terminal == null) {
                // The child owns the liveness signal. Carry its renewed progress lease to this
                // semantic parent instead of imposing an unrelated wall-clock leg timeout.
                r.extendDeadlineTo(moveRecord.getDeadlineGameTime());
                return TaskState.RUNNING;
            }
        }
        moveChild.result(terminal);
        moveChild = null;
        moveRecord = null;
        if (terminal == TaskState.SUCCESS) frontierReached++; else frontierFailed++;
        stage = Stage.OBSERVE;
        return TaskState.RUNNING;
    }

    private BlockPos nextFrontier(ClientLevel level) {
        for (int probe = 0; probe < MAX_SPIRAL_PROBES; probe++) {
            BlockPos desired = nextSpiralPoint();
            if (!insideScope(desired.getX(), desired.getZ())) continue;
            BlockPos frontier = loadedFrontierToward(level, desired);
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
                origin.getX() + spiralX * WAYPOINT_GRID,
                player.blockPosition().getY(),
                origin.getZ() + spiralZ * WAYPOINT_GRID);
    }

    private BlockPos loadedFrontierToward(ClientLevel level, BlockPos desired) {
        BlockPos current = player.blockPosition();
        double dx = desired.getX() - current.getX();
        double dz = desired.getZ() - current.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0) return null;
        double farthest = Math.min(MAX_LEG_DISTANCE, distance);
        for (double leg = farthest; leg >= Math.min(16.0, farthest); leg -= 16.0) {
            int x = (int) Math.round(current.getX() + dx / distance * leg);
            int z = (int) Math.round(current.getZ() + dz / distance * leg);
            if (!insideScope(x, z) || !columnLoaded(level, x, z)) continue;
            if (preferLandFrontiers) {
                BlockPos land = nearestDryLandFrontier(level, current, x, z);
                if (land != null) return land;
            }
            // Water is still a valid way to reveal new terrain. It is a fallback after the
            // target type's vanilla spawn semantics told us that dry ground is better evidence.
            return new BlockPos(x, current.getY(), z);
        }
        return null;
    }

    /**
     * Resolve a geometric frontier to nearby dry, standable loaded terrain. The preference is
     * enabled only when every requested entity type uses vanilla's ON_GROUND spawn placement;
     * aquatic or unrestricted types retain the ordinary terrain-neutral frontier search.
     */
    private BlockPos nearestDryLandFrontier(
            ClientLevel level, BlockPos current, int projectedX, int projectedZ) {
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int dx = -LAND_FRONTIER_PROBE_RADIUS;
                dx <= LAND_FRONTIER_PROBE_RADIUS;
                dx += LAND_FRONTIER_PROBE_STRIDE) {
            for (int dz = -LAND_FRONTIER_PROBE_RADIUS;
                    dz <= LAND_FRONTIER_PROBE_RADIUS;
                    dz += LAND_FRONTIER_PROBE_STRIDE) {
                int x = projectedX + dx;
                int z = projectedZ + dz;
                if (!insideScope(x, z) || !columnLoaded(level, x, z)) continue;
                double progress = horizontalDistance(current, new BlockPos(x, current.getY(), z));
                if (progress < MIN_FRONTIER_PROGRESS) continue;
                BlockPos candidate = drySurface(level, x, z);
                if (candidate == null) continue;
                double score = (double) dx * dx + (double) dz * dz;
                if (score < bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        if (best == null) return null;

        // Coarse sampling finds the land mass cheaply; refine within its four-block sample cell
        // so the child receives a real standable column rather than an arbitrary grid point.
        BlockPos refined = best;
        double refinedScore = distanceSqr(best.getX(), best.getZ(), projectedX, projectedZ);
        for (int dx = -LAND_FRONTIER_PROBE_STRIDE + 1;
                dx < LAND_FRONTIER_PROBE_STRIDE;
                dx++) {
            for (int dz = -LAND_FRONTIER_PROBE_STRIDE + 1;
                    dz < LAND_FRONTIER_PROBE_STRIDE;
                    dz++) {
                int x = best.getX() + dx;
                int z = best.getZ() + dz;
                if (!insideScope(x, z) || !columnLoaded(level, x, z)) continue;
                BlockPos candidate = drySurface(level, x, z);
                if (candidate == null) continue;
                double score = distanceSqr(x, z, projectedX, projectedZ);
                if (score < refinedScore) {
                    refined = candidate;
                    refinedScore = score;
                }
            }
        }
        return refined;
    }

    private BlockPos drySurface(ClientLevel level, int x, int z) {
        int estimate = ClientSurfaceHeight.motionBlockingNoLeaves(level, x, z);
        int upper = Math.min(level.getMaxBuildHeight() - 2, estimate + 1);
        int lower = Math.max(level.getMinBuildHeight() + 1, estimate - 2);
        for (int y = upper; y >= lower; y--) {
            BlockPos feet = new BlockPos(x, y, z);
            if (!level.getFluidState(feet.below()).isEmpty()
                    || !level.getFluidState(feet).isEmpty()
                    || !level.getFluidState(feet.above()).isEmpty()) {
                continue;
            }
            if (MovementHelper.canWalkOn(level, feet.below())
                    && MovementHelper.canWalkThrough(level, feet)
                    && MovementHelper.canWalkThrough(level, feet.above())) {
                return feet;
            }
        }
        return null;
    }

    private boolean targetsUseGroundSpawnPlacement() {
        for (ResourceLocation id : r.entityTypeIds) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            if (type == null
                    || SpawnPlacements.getPlacementType(type) != SpawnPlacementTypes.ON_GROUND) {
                return false;
            }
        }
        return !r.entityTypeIds.isEmpty();
    }

    private static double distanceSqr(int x, int z, int otherX, int otherZ) {
        double dx = x - otherX;
        double dz = z - otherZ;
        return dx * dx + dz * dz;
    }

    private TaskState exhausted() {
        if (!observedSafe.isEmpty()) {
            return failSearch(
                    "insufficient_entity_count",
                    "bounded first-person search observed only " + observedSafe.size() + "/"
                            + r.count + " acceptable entities",
                    FailureType.TARGET_LOST);
        }
        if (protectedOrAmbiguousSeen > 0) {
            return failSearch(
                    "only_protected_or_ambiguous_entity_evidence",
                    "matching entities were observed, but none had sufficient unprotected semantic evidence",
                    FailureType.TARGET_LOST);
        }
        return failSearch(
                frontierAttempts > 0 && frontierFailed == frontierAttempts
                        ? "entity_frontier_unreachable" : "no_entity_evidence_within_bound",
                "bounded first-person search found no acceptable matching entity evidence",
                frontierAttempts > 0 && frontierFailed == frontierAttempts
                        ? FailureType.NO_PATH : FailureType.TARGET_LOST);
    }

    private TaskState failSearch(String code, String message, FailureType type) {
        failureCode = code;
        fail(message, type);
        return TaskState.FAILED;
    }

    private boolean insideScope(double x, double z) {
        double dx = x - origin.getX();
        double dz = z - origin.getZ();
        return dx * dx + dz * dz <= (double) r.maxDistance * r.maxDistance;
    }

    private boolean columnLoaded(ClientLevel level, int x, int z) {
        int y = Math.clamp(player.blockPosition().getY(),
                level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        return level.isLoaded(new BlockPos(x, y, z));
    }

    private static double horizontalDistance(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void stopMove(TaskState terminal) {
        if (moveChild == null) return;
        moveChild.stop(player, Task.StopReason.REPLACED);
        moveChild.result(terminal);
        moveChild = null;
        moveRecord = null;
    }

    @Override
    protected void cleanup() {
        stopMove(TaskState.CANCELLED);
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Integer> observedByType = new LinkedHashMap<>();
        for (ResourceLocation type : observedSafe.values()) {
            observedByType.merge(type.toString(), 1, Integer::sum);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entity_type_ids", r.entityTypeIds.stream().map(ResourceLocation::toString).toList());
        data.put("relation", r.relation.name().toLowerCase(Locale.ROOT));
        data.put("requested_count", r.count);
        data.put("observed_acceptable_count", observedSafe.size());
        data.put("observed_acceptable_by_type", Map.copyOf(observedByType));
        data.put("verified", observedSafe.size() >= r.count);
        data.put("scope", "loaded_client_entities_and_first_person_loaded_frontiers");
        data.put("frontier_surface_preference",
                preferLandFrontiers ? "vanilla_on_ground_spawn_evidence" : "terrain_neutral");
        data.put("max_distance", r.maxDistance);
        data.put("scan_cycles", scanCycles);
        data.put("last_loaded_relation_match_count", lastLoadedMatching);
        data.put("frontier_legs_attempted", frontierAttempts);
        data.put("frontier_legs_reached", frontierReached);
        data.put("frontier_legs_failed", frontierFailed);
        data.put("farthest_body_distance", farthestBodyDistance);
        data.put("protected_or_ambiguous_observations", protectedOrAmbiguousSeen);
        data.put("protection_reason_counts", Map.copyOf(protectedReasonCounts));
        if (observedSafe.size() < r.count) {
            String code = failureCode == null ? "entity_search_timeout" : failureCode;
            data.put("failure_code", code);
            data.put("recoverable", true);
            data.put("requires_narration", true);
            data.put("requires_decision",
                    "only_protected_or_ambiguous_entity_evidence".equals(code));
            List<String> suggestions = new ArrayList<>();
            suggestions.add("increase max_distance or continue from a different semantic area");
            if (!r.mayAlterTerrain && frontierFailed > 0) {
                suggestions.add("allow terrain alteration only if opening those routes is acceptable");
            }
            if (protectedOrAmbiguousSeen > 0) {
                suggestions.add("choose a clearly unowned/unprotected population; do not weaken protection by runtime id");
            }
            suggestions.add("stop without treating the partial observed count as success");
            data.put("suggestions", List.copyOf(suggestions));
        }
        return data;
    }

    @Override
    protected String successMessage() {
        return "verified " + observedSafe.size() + "/" + r.count + " acceptable "
                + r.relation.name().toLowerCase(Locale.ROOT)
                + " entity observations through loaded client evidence";
    }

    @Override
    protected String timeoutMessage() {
        return "entity search stopped making verifiable progress with "
                + observedSafe.size() + "/" + r.count
                + " acceptable observations; partial evidence was not reported as success";
    }

    @Override
    protected String cancelledMessage() {
        return "entity search was interrupted; partial observations were not reported as success";
    }
}
