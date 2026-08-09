package org.maiwithu.maicraft.core.task.explore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.CompassUtil;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * Observe loaded facts, travel to an internal frontier, load more terrain, and repeat until the
 * semantic target is verified at the body. No seed, generator or chunk-loading API is used.
 */
public final class SemanticExploreCompanionTask
        extends AbstractCompanionTask<SemanticExploreTaskRecord> {

    private enum TargetKind { BIOME, COAST }
    private enum Stage { OBSERVE, TRAVEL_TARGET, VERIFY_TARGET, TRAVEL_WAYPOINT }
    private enum SurfaceKind { LAND, WATER, UNKNOWN }

    private record ColumnOffset(int dx, int dz, int distanceSquared) {}
    private record SurfaceInfo(
            SurfaceKind kind, BlockPos approach, BlockPos evidence, int waterDepth) {}
    private record TargetCandidate(
            BlockPos approach, BlockPos evidence, String description) {}

    private static final int OBSERVATION_RADIUS = 112;
    private static final int BIOME_OBSERVATION_STEP = 4;
    private static final int COAST_OBSERVATION_STEP = 8;
    private static final int BIOME_Y_STEP = 32;
    private static final int BIOME_SAMPLES_PER_TICK = 192;
    private static final int COAST_COLUMNS_PER_TICK = 8;
    private static final int COAST_LOCAL_RADIUS = 4;
    private static final int WAYPOINT_GRID = 64;
    private static final int MAX_LEG_DISTANCE = 80;
    private static final int LEG_TIMEOUT_TICKS = 90 * 20;
    private static final int SCOPE_TOLERANCE = 8;
    private static final int MAX_TARGET_ATTEMPTS = 24;
    private static final int MAX_REPORTED_FAILURES = 16;
    private static final int MAX_REPORTED_FRONTIERS = 16;
    private static final int MAX_SPIRAL_PROBES = 10_000;

    private static final List<ColumnOffset> BIOME_OBSERVATION_OFFSETS =
            buildOffsets(BIOME_OBSERVATION_STEP);
    private static final List<ColumnOffset> COAST_OBSERVATION_OFFSETS =
            buildOffsets(COAST_OBSERVATION_STEP);

    private TargetKind targetKind;
    private Predicate<Holder<Biome>> biomeMatch;
    private String canonicalTarget;
    private String inputFailure;
    private BlockPos origin;
    private Stage stage;

    private int observationColumn;
    private int biomeYIndex;
    private int observationCycles;
    private BlockPos observationCenter;
    private long loadedSampleCount;
    private long unloadedSampleCount;
    private long unapproachableMatchCount;
    private int observedMinX = Integer.MAX_VALUE;
    private int observedMaxX = Integer.MIN_VALUE;
    private int observedMinZ = Integer.MAX_VALUE;
    private int observedMaxZ = Integer.MIN_VALUE;
    private double farthestObservedDistance;
    private final Set<Long> observedLoadedColumns = new HashSet<>();
    private final Set<Long> exploredCenterColumns = new HashSet<>();
    private final List<BlockPos> exploredCenters = new ArrayList<>();
    private final List<BlockPos> unloadedFrontiers = new ArrayList<>();
    private final Map<Long, SurfaceInfo> surfaceCache = new HashMap<>();

    private MoveToCompanionTask moveChild;
    private long legDeadline;
    private int legSerial;
    private TargetCandidate candidate;
    private BlockPos activeWaypoint;
    private int waypointAttempts;
    private int waypointReached;
    private int waypointFailed;
    private int targetAttempts;
    private final Set<Long> rejectedTargets = new HashSet<>();
    private final Set<Long> attemptedWaypoints = new HashSet<>();
    private final List<Map<String, Object>> legFailures = new ArrayList<>();

    private int spiralX;
    private int spiralZ;
    private int spiralDirection;
    private int spiralSegmentLength = 1;
    private int spiralSegmentProgress;
    private int spiralSegmentsAtLength;

    private BlockPos verifiedPosition;
    private String verifiedDescription;
    private double farthestBodyDistance;

    public SemanticExploreCompanionTask(
            LocalPlayer player, SemanticExploreTaskRecord record) {
        super(player, record);
    }

    @Override protected void onStart() {
        origin = player.blockPosition().immutable();
        ClientLevel level = ClientRuntime.requireContext(player).level();
        if (!resolveTarget(level, r.target)) {
            fail(inputFailure, FailureType.UNSUPPORTED);
            return;
        }
        beginObservation();
    }

    @Override protected TaskState onTick() {
        double bodyDistance = horizontalDistance(origin, player.blockPosition());
        farthestBodyDistance = Math.max(farthestBodyDistance, bodyDistance);
        if (bodyDistance > r.maxDistance + SCOPE_TOLERANCE) {
            stopActiveChild(TaskState.FAILED);
            fail("exploration movement left the bounded radius of " + r.maxDistance
                    + " blocks and was stopped", FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        return switch (stage) {
            case OBSERVE -> tickObservation();
            case TRAVEL_TARGET -> tickTravel(true);
            case VERIFY_TARGET -> tickVerification();
            case TRAVEL_WAYPOINT -> tickTravel(false);
        };
    }

    private boolean resolveTarget(ClientLevel level, String raw) {
        String target = raw.trim();
        if ("coast".equalsIgnoreCase(target)) {
            targetKind = TargetKind.COAST;
            canonicalTarget = "coast";
            return true;
        }
        var registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        if (target.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(target.substring(1));
            if (id == null) {
                inputFailure = "invalid biome tag: " + target;
                return false;
            }
            TagKey<Biome> tag = TagKey.create(Registries.BIOME, id);
            if (registry.get(tag).isEmpty()) {
                inputFailure = "unknown biome tag in the client registry: " + target;
                return false;
            }
            targetKind = TargetKind.BIOME;
            canonicalTarget = "#" + id;
            biomeMatch = holder -> holder.is(tag);
            return true;
        }
        ResourceLocation id = ResourceLocation.tryParse(target);
        ResourceKey<Biome> key = id == null ? null : ResourceKey.create(Registries.BIOME, id);
        if (key == null || registry.get(key).isEmpty()) {
            inputFailure = "unknown biome in the client registry: " + target
                    + " (use a namespaced biome id or #biome_tag)";
            return false;
        }
        targetKind = TargetKind.BIOME;
        canonicalTarget = id.toString();
        biomeMatch = holder -> holder.is(key);
        return true;
    }

    private void beginObservation() {
        observationCenter = player.blockPosition().immutable();
        observationColumn = 0;
        biomeYIndex = 0;
        observationCycles++;
        surfaceCache.clear();
        long centerKey = BlockPos.asLong(
                observationCenter.getX(), 0, observationCenter.getZ());
        if (exploredCenters.size() < r.maxWaypoints + 1
                && exploredCenterColumns.add(centerKey)) {
            exploredCenters.add(observationCenter);
        }
        stage = Stage.OBSERVE;
    }

    private TaskState tickObservation() {
        ClientLevel level = ClientRuntime.requireContext(player).level();
        List<ColumnOffset> offsets = targetKind == TargetKind.COAST
                ? COAST_OBSERVATION_OFFSETS : BIOME_OBSERVATION_OFFSETS;
        int budget = targetKind == TargetKind.COAST
                ? COAST_COLUMNS_PER_TICK : BIOME_SAMPLES_PER_TICK;
        while (budget-- > 0 && observationColumn < offsets.size()) {
            ColumnOffset offset = offsets.get(observationColumn);
            int x = observationCenter.getX() + offset.dx();
            int z = observationCenter.getZ() + offset.dz();
            if (!insideScope(x, z)) {
                advanceObservationColumn();
                continue;
            }
            if (!columnLoaded(level, x, z)) {
                noteUnloaded(x, z);
                advanceObservationColumn();
                continue;
            }
            if (biomeYIndex == 0) {
                loadedSampleCount++;
                observedLoadedColumns.add(BlockPos.asLong(x, 0, z));
                observedMinX = Math.min(observedMinX, x);
                observedMaxX = Math.max(observedMaxX, x);
                observedMinZ = Math.min(observedMinZ, z);
                observedMaxZ = Math.max(observedMaxZ, z);
                farthestObservedDistance = Math.max(farthestObservedDistance,
                        horizontalDistance(origin, new BlockPos(x, origin.getY(), z)));
            }

            TargetCandidate found;
            if (targetKind == TargetKind.COAST) {
                found = findCoastNear(level, x, z, COAST_LOCAL_RADIUS);
                advanceObservationColumn();
            } else {
                found = observeBiomeSample(level, x, z, biomeYIndex++);
                if (biomeYIndex >= biomeSampleCount(level)) {
                    advanceObservationColumn();
                }
            }
            if (found != null && !rejectedTargets.contains(found.approach().asLong())) {
                return startTargetTravel(found);
            }
        }
        if (observationColumn < offsets.size()) {
            return TaskState.RUNNING;
        }
        return startNextWaypoint(level);
    }

    private TargetCandidate observeBiomeSample(
            ClientLevel level, int x, int z, int sampleIndex) {
        if (sampleIndex == 0) {
            int currentY = Math.clamp(player.blockPosition().getY(),
                    level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
            BlockPos approach = travelCellNear(level, x, currentY, z, 10, biomeMatch);
            if (approach != null) {
                return new TargetCandidate(approach, approach,
                        "loaded stand/swim cell inside " + canonicalTarget);
            }
            return null;
        }
        if (sampleIndex == 1) {
            BlockPos surface = surfaceTravelCell(level, x, z);
            if (surface != null && biomeMatch.test(level.getBiome(surface))) {
                return new TargetCandidate(surface, surface,
                        "surface cell inside " + canonicalTarget);
            }
            return null;
        }
        int sampleY = level.getMinBuildHeight() + 8 + (sampleIndex - 2) * BIOME_Y_STEP;
        sampleY = Math.clamp(sampleY,
                level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        BlockPos sample = new BlockPos(x, sampleY, z);
        if (!biomeMatch.test(level.getBiome(sample))) {
            return null;
        }
        BlockPos approach = travelCellNear(level, x, sampleY, z, 10, biomeMatch);
        if (approach == null) {
            unapproachableMatchCount++;
            return null;
        }
        return new TargetCandidate(approach, sample,
                "loaded stand/swim cell inside " + canonicalTarget);
    }

    private int biomeSampleCount(ClientLevel level) {
        int height = level.getMaxBuildHeight() - level.getMinBuildHeight();
        return 2 + (height + BIOME_Y_STEP - 1) / BIOME_Y_STEP;
    }

    private void advanceObservationColumn() {
        observationColumn++;
        biomeYIndex = 0;
    }
