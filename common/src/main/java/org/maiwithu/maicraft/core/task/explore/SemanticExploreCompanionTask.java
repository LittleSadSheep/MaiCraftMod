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
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;
import org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeight;
import org.maiwithu.maicraft.core.task.CompassUtil;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
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
    private record WaterEvidence(
            boolean oceanBiome, boolean largeConnectedWater,
            int forwardRun, int lateralWidth, int deepestColumn, int score) {
        boolean qualifiesAsCoast() {
            // "Coast" means dry land adjoining a sea, not merely the edge of a large lake.
            // The size evidence remains useful for ranking, but cannot replace observed ocean
            // biome evidence from loaded client terrain.
            return oceanBiome;
        }
    }
    private record ScoredCoast(
            TargetCandidate candidate, boolean preferredShore,
            double travelDistance, int evidenceScore) {}

    private static final int OBSERVATION_RADIUS = 112;
    private static final int BIOME_OBSERVATION_STEP = 4;
    private static final int COAST_OBSERVATION_STEP = 8;
    private static final int BIOME_Y_STEP = 32;
    private static final int BIOME_SAMPLES_PER_TICK = 192;
    private static final int COAST_COLUMNS_PER_TICK = 4;
    private static final int COAST_LOCAL_RADIUS = 4;
    /** Bounded connected-water evidence measured away from a candidate dry bank. */
    private static final int LARGE_WATER_FORWARD_PROBE = 12;
    private static final int LARGE_WATER_SIDE_PROBE = 6;
    private static final int LARGE_WATER_MIN_FORWARD_RUN = 8;
    private static final int LARGE_WATER_MIN_LATERAL_WIDTH = 7;
    private static final int WAYPOINT_GRID = 64;
    private static final int MAX_LEG_DISTANCE = 80;
    /** Initial leg lease. MoveTo renews its own record while verified route progress continues. */
    private static final int INITIAL_LEG_LEASE_TICKS = 90 * 20;
    private static final int SCOPE_TOLERANCE = 8;
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
    private MoveToTaskRecord moveRecord;
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

            if (targetKind == TargetKind.COAST) {
                ScoredCoast found = findScoredCoastNear(
                        level, x, z, COAST_LOCAL_RADIUS);
                advanceObservationColumn();
                if (found != null
                        && !rejectedTargets.contains(found.candidate().approach().asLong())) {
                    // Offsets are ordered nearest-first. The local probe has already compared
                    // nearby dry approaches (including shore-terrain preference), so leave now
                    // instead of standing still to scan the entire loaded view for a prettier
                    // but farther coast.
                    return startTargetTravel(found.candidate());
                }
            } else {
                TargetCandidate found = observeBiomeSample(level, x, z, biomeYIndex++);
                if (biomeYIndex >= biomeSampleCount(level)) {
                    advanceObservationColumn();
                }
                if (found != null && !rejectedTargets.contains(found.approach().asLong())) {
                    return startTargetTravel(found);
                }
            }
        }
        if (observationColumn < offsets.size()) {
            // Observation deliberately samples a finite local grid in bounded per-tick slices.
            // Preserve that CPU budget without charging the semantic liveness lease for queued
            // slices (especially when game ticks are accelerated).
            r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
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

    private TaskState startTargetTravel(TargetCandidate found) {
        candidate = found;
        targetAttempts++;
        startMove(found.approach(), true);
        stage = Stage.TRAVEL_TARGET;
        return TaskState.RUNNING;
    }

    private TaskState startNextWaypoint(ClientLevel level) {
        if (waypointAttempts >= r.maxWaypoints) {
            return exhausted();
        }
        BlockPos waypoint = nextWaypoint(level);
        if (waypoint == null) {
            return exhausted();
        }
        activeWaypoint = waypoint;
        waypointAttempts++;
        startMove(waypoint, false);
        stage = Stage.TRAVEL_WAYPOINT;
        return TaskState.RUNNING;
    }

    private void startMove(BlockPos target, boolean exact) {
        long now = player.level().getGameTime();
        String parentCall = r.getToolCallId() == null ? "explore" : r.getToolCallId();
        String childCall = parentCall + "-internal-leg-" + (++legSerial);
        // Coast promises an exact grounded dry stance to downstream tasks. Other biome targets
        // deliberately retain the original stand-or-swim movement contract and are confirmed
        // from the live body's biome in VERIFY_TARGET (an ocean cell cannot be onGround).
        moveRecord = exact && targetKind == TargetKind.COAST
                ? MoveToTaskRecord.strictStance(
                        childCall, now + INITIAL_LEG_LEASE_TICKS, target, r.mayAlterTerrain)
                : new MoveToTaskRecord(
                        childCall, now + INITIAL_LEG_LEASE_TICKS,
                        (double) target.getX(), exact ? (double) target.getY() : null,
                        (double) target.getZ(), null,
                        r.mayAlterTerrain);
        moveChild = new MoveToCompanionTask(player, moveRecord);
    }

    private TaskState tickTravel(boolean targetTravel) {
        TaskState terminal;
        if (player.level().getGameTime() >= moveRecord.getDeadlineGameTime()) {
            moveChild.stop(player, Task.StopReason.REPLACED);
            terminal = TaskState.TIMEOUT;
        } else {
            terminal = runChild(moveChild);
            if (terminal == null) {
                // A long healthy journey remains alive as long as its physical child continues
                // to renew a verified-progress lease. Semantic radius/waypoint bounds still cap
                // the search scope; elapsed wall-clock time does not redefine the goal.
                r.extendDeadlineTo(moveRecord.getDeadlineGameTime());
                return TaskState.RUNNING;
            }
        }
        TaskResult result = moveChild.result(terminal);
        moveChild = null;
        moveRecord = null;

        if (targetTravel) {
            if (terminal == TaskState.SUCCESS) {
                stage = Stage.VERIFY_TARGET;
                return TaskState.RUNNING;
            }
            rejectedTargets.add(candidate.approach().asLong());
            recordLegFailure("target_approach", candidate.approach(), result);
            candidate = null;
            beginObservation();
            return TaskState.RUNNING;
        }

        if (terminal == TaskState.SUCCESS) {
            waypointReached++;
        } else {
            waypointFailed++;
            recordLegFailure("exploration_waypoint", activeWaypoint, result);
        }
        activeWaypoint = null;
        beginObservation();
        return TaskState.RUNNING;
    }

    private TaskState tickVerification() {
        ClientLevel level = ClientRuntime.requireContext(player).level();
        TargetCandidate verified = null;
        if (targetKind == TargetKind.BIOME) {
            BlockPos feet = player.blockPosition();
            if (level.isLoaded(feet) && biomeMatch.test(level.getBiome(feet))) {
                verified = new TargetCandidate(feet, feet,
                        "body position is inside " + biomeId(level, feet));
            }
        } else {
            surfaceCache.clear();
            BlockPos body = BlockHelper.playerFeet(
                    level, player.getX(), player.getY(), player.getZ()).immutable();
            // MoveTo may legitimately report a bounded near-success when the exact path is
            // obstructed. That is useful for ordinary destinations, but a coast receipt promises
            // a dry land approach: being in adjacent shallow water is evidence of seeing the
            // coast, not evidence of having reached it.
            if (player.onGround() && BlockHelper.isDryStandable(level, body)) {
                TargetCandidate bodyCoast = findCoastNear(
                        level, body.getX(), body.getZ(), 0);
                if (bodyCoast != null && body.equals(bodyCoast.approach())) {
                    verified = bodyCoast;
                }
            }
        }
        if (verified != null) {
            verifiedPosition = targetKind == TargetKind.COAST
                    ? BlockHelper.playerFeet(
                            level, player.getX(), player.getY(), player.getZ()).immutable()
                    : player.blockPosition().immutable();
            verifiedDescription = verified.description();
            r.retainVerifiedPosition(new InternalPositionReceipt.Position(
                    verifiedPosition.getX(), verifiedPosition.getY(), verifiedPosition.getZ(),
                    player.level().dimension().location().toString()));
            return TaskState.SUCCESS;
        }

        rejectedTargets.add(candidate.approach().asLong());
        recordLegFailure("target_verification", candidate.approach(),
                TaskResult.fail("arrival did not re-confirm the semantic target in loaded facts"));
        candidate = null;
        beginObservation();
        return TaskState.RUNNING;
    }

    private TargetCandidate findCoastNear(
            ClientLevel level, int centerX, int centerZ, int radius) {
        ScoredCoast best = findScoredCoastNear(level, centerX, centerZ, radius);
        return best == null ? null : best.candidate();
    }

    private ScoredCoast findScoredCoastNear(
            ClientLevel level, int centerX, int centerZ, int radius) {
        ScoredCoast best = null;
        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int landX = centerX + dx;
                    int landZ = centerZ + dz;
                    if (!insideScope(landX, landZ)) continue;
                    SurfaceInfo land = surfaceInfo(level, landX, landZ);
                    if (land.kind() != SurfaceKind.LAND || land.approach() == null) continue;
                    if (rejectedTargets.contains(land.approach().asLong())) continue;
                    for (Direction direction : Direction.Plane.HORIZONTAL) {
                        int waterX = landX + direction.getStepX();
                        int waterZ = landZ + direction.getStepZ();
                        if (!insideScope(waterX, waterZ)) continue;
                        SurfaceInfo water = surfaceInfo(level, waterX, waterZ);
                        if (water.kind() == SurfaceKind.WATER) {
                            WaterEvidence evidence = waterEvidence(
                                    level, waterX, waterZ, direction);
                            if (!evidence.qualifiesAsCoast()) continue;
                            boolean preferredShore = preferredCoastTerrain(level, land.approach());
                            double travelDistance = horizontalDistance(
                                    player.blockPosition(), land.approach());
                            String description = "verified dry coast approach beside ocean-biome water";
                            if (preferredShore) description += " with shore terrain preferred";
                            ScoredCoast scored = new ScoredCoast(new TargetCandidate(
                                    land.approach(), water.evidence(), description),
                                    preferredShore, travelDistance, evidence.score());
                            if (betterCoast(scored, best)) {
                                best = scored;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private static boolean betterCoast(ScoredCoast candidate, ScoredCoast incumbent) {
        if (incumbent == null) return true;
        if (candidate.preferredShore() != incumbent.preferredShore()) {
            return candidate.preferredShore();
        }
        int travelOrder = Double.compare(candidate.travelDistance(), incumbent.travelDistance());
        if (travelOrder != 0) return travelOrder < 0;
        if (candidate.evidenceScore() != incumbent.evidenceScore()) {
            return candidate.evidenceScore() > incumbent.evidenceScore();
        }
        return candidate.candidate().approach().asLong()
                < incumbent.candidate().approach().asLong();
    }

    /**
     * Bounded semantic evidence for a sea. A loaded water sample in an ocean biome is required;
     * continuous outward run and lateral breadth increase confidence and ranking but never turn a
     * plains lake or river into a coast. Every probe is a loaded client column and the fixed budget
     * never discovers terrain.
     */
    private WaterEvidence waterEvidence(
            ClientLevel level, int waterX, int waterZ, Direction awayFromLand) {
        Direction side = awayFromLand.getClockWise();
        int forwardRun = 0;
        int deepest = 0;
        boolean ocean = false;
        for (int distance = 0; distance <= LARGE_WATER_FORWARD_PROBE; distance++) {
            int x = waterX + awayFromLand.getStepX() * distance;
            int z = waterZ + awayFromLand.getStepZ() * distance;
            if (!insideScope(x, z)) break;
            SurfaceInfo sample = surfaceInfo(level, x, z);
            if (sample.kind() != SurfaceKind.WATER) break;
            forwardRun++;
            deepest = Math.max(deepest, sample.waterDepth());
            ocean |= level.getBiome(sample.evidence()).is(BiomeTags.IS_OCEAN);
        }

        int crossDistance = Math.min(4, Math.max(0, forwardRun - 1));
        int crossX = waterX + awayFromLand.getStepX() * crossDistance;
        int crossZ = waterZ + awayFromLand.getStepZ() * crossDistance;
        int lateralWidth = forwardRun == 0 ? 0 : 1;
        for (int sign : new int[]{-1, 1}) {
            for (int distance = 1; distance <= LARGE_WATER_SIDE_PROBE; distance++) {
                int x = crossX + side.getStepX() * distance * sign;
                int z = crossZ + side.getStepZ() * distance * sign;
                if (!insideScope(x, z)) break;
                SurfaceInfo sample = surfaceInfo(level, x, z);
                if (sample.kind() != SurfaceKind.WATER) break;
                lateralWidth++;
                deepest = Math.max(deepest, sample.waterDepth());
            }
        }
        boolean large = forwardRun >= LARGE_WATER_MIN_FORWARD_RUN
                && lateralWidth >= LARGE_WATER_MIN_LATERAL_WIDTH;
        int score = (ocean ? 320 : 0) + (large ? 256 : 0)
                + forwardRun * 4 + lateralWidth * 3 + deepest * 2;
        return new WaterEvidence(ocean, large, forwardRun, lateralWidth, deepest, score);
    }

    private static boolean preferredCoastTerrain(ClientLevel level, BlockPos approach) {
        Holder<Biome> biome = level.getBiome(approach);
        return biome.is(BiomeTags.IS_BEACH) || biome.is(Biomes.STONY_SHORE);
    }

    private SurfaceInfo surfaceInfo(ClientLevel level, int x, int z) {
        long key = BlockPos.asLong(x, 0, z);
        SurfaceInfo cached = surfaceCache.get(key);
        if (cached != null) return cached;
        SurfaceInfo result;
        if (!columnLoaded(level, x, z)) {
            result = new SurfaceInfo(SurfaceKind.UNKNOWN, null, null, 0);
        } else {
            int height = Math.clamp(
                    ClientSurfaceHeight.motionBlockingNoLeaves(level, x, z),
                    level.getMinBuildHeight() + 1,
                    level.getMaxBuildHeight() - 1);
            BlockPos top = new BlockPos(x, height - 1, z);
            BlockState topState = level.getBlockState(top);
            if (topState.getFluidState().is(FluidTags.WATER)) {
                int depth = 0;
                for (int y = top.getY(); y >= level.getMinBuildHeight() && depth < 8; y--) {
                    BlockPos water = new BlockPos(x, y, z);
                    if (!level.getBlockState(water).getFluidState().is(FluidTags.WATER)) break;
                    depth++;
                }
                result = new SurfaceInfo(SurfaceKind.WATER, null, top, depth);
            } else {
                BlockPos approach = dryTravelCellNear(level, x, height, z, 3);
                result = approach == null
                        ? new SurfaceInfo(SurfaceKind.UNKNOWN, null, top, 0)
                        : new SurfaceInfo(SurfaceKind.LAND, approach, top, 0);
            }
        }
        surfaceCache.put(key, result);
        return result;
    }

    private BlockPos surfaceTravelCell(ClientLevel level, int x, int z) {
        SurfaceInfo surface = surfaceInfo(level, x, z);
        if (surface.kind() == SurfaceKind.LAND) return surface.approach();
        if (surface.kind() == SurfaceKind.WATER && isTravelCell(level, surface.evidence())) {
            return surface.evidence();
        }
        return null;
    }

    private BlockPos travelCellNear(
            ClientLevel level, int x, int y, int z, int verticalRadius,
            Predicate<Holder<Biome>> requiredBiome) {
        for (int distance = 0; distance <= verticalRadius; distance++) {
            int[] ys = distance == 0 ? new int[]{y} : new int[]{y + distance, y - distance};
            for (int candidateY : ys) {
                if (candidateY <= level.getMinBuildHeight()
                        || candidateY >= level.getMaxBuildHeight() - 1) continue;
                BlockPos candidate = new BlockPos(x, candidateY, z);
                if (isTravelCell(level, candidate)
                        && (requiredBiome == null
                                || requiredBiome.test(level.getBiome(candidate)))) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private BlockPos dryTravelCellNear(
            ClientLevel level, int x, int y, int z, int verticalRadius) {
        for (int distance = 0; distance <= verticalRadius; distance++) {
            int[] ys = distance == 0 ? new int[]{y} : new int[]{y + distance, y - distance};
            for (int candidateY : ys) {
                if (candidateY <= level.getMinBuildHeight()
                        || candidateY >= level.getMaxBuildHeight() - 1) continue;
                BlockPos candidate = new BlockPos(x, candidateY, z);
                if (level.isLoaded(candidate.below()) && level.isLoaded(candidate.above())
                        && BlockHelper.isDryStandable(level, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean isTravelCell(ClientLevel level, BlockPos feet) {
        if (!level.isLoaded(feet) || !level.isLoaded(feet.above())) return false;
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(feet.above());
        if (feetState.getFluidState().is(FluidTags.LAVA)
                || headState.getFluidState().is(FluidTags.LAVA)) return false;
        boolean headClear = headState.getCollisionShape(level, feet.above()).isEmpty();
        if (feetState.getFluidState().is(FluidTags.WATER)) return headClear;
        if (!feetState.getCollisionShape(level, feet).isEmpty() || !headClear) return false;
        BlockPos below = feet.below();
        return level.isLoaded(below)
                && !level.getBlockState(below).getCollisionShape(level, below).isEmpty()
                && !level.getBlockState(below).getFluidState().is(FluidTags.LAVA);
    }

    private BlockPos nextWaypoint(ClientLevel level) {
        for (int probe = 0; probe < MAX_SPIRAL_PROBES; probe++) {
            BlockPos desired = nextSpiralPoint();
            if (!insideScope(desired.getX(), desired.getZ())) continue;
            BlockPos frontier = loadedFrontierToward(level, desired);
            if (frontier != null && attemptedWaypoints.add(
                    BlockPos.asLong(frontier.getX(), 0, frontier.getZ()))) return frontier;
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
            return new BlockPos(x, current.getY(), z);
        }
        return null;
    }

    private TaskState exhausted() {
        fail("bounded exploration finished without verifying " + canonicalTarget
                        + "; searched only the initial client view and terrain loaded by real "
                        + "travel within "
                        + r.maxDistance + " blocks",
                FailureType.TARGET_LOST);
        return TaskState.FAILED;
    }

    private void noteUnloaded(int x, int z) {
        unloadedSampleCount++;
        if (unloadedFrontiers.size() >= MAX_REPORTED_FRONTIERS) return;
        BlockPos sample = new BlockPos(x, 0, z);
        for (BlockPos existing : unloadedFrontiers) {
            if (existing.distManhattan(sample) < 32) return;
        }
        unloadedFrontiers.add(sample);
    }

    private void recordLegFailure(String kind, BlockPos target, TaskResult result) {
        if (legFailures.size() >= MAX_REPORTED_FAILURES) return;
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("kind", kind);
        failure.put("x", target.getX());
        failure.put("y", target.getY());
        failure.put("z", target.getZ());
        failure.put("message", result == null ? "no child result" : result.message());
        legFailures.add(failure);
    }

    private void stopActiveChild(TaskState terminal) {
        if (moveChild == null) return;
        moveChild.stop(player, Task.StopReason.REPLACED);
        moveChild.result(terminal);
        moveChild = null;
        moveRecord = null;
    }

    private boolean insideScope(int x, int z) {
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

    private static String biomeId(ClientLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unregistered biome");
    }

    private static String shortPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static List<ColumnOffset> buildOffsets(int step) {
        List<ColumnOffset> offsets = new ArrayList<>();
        for (int dx = -OBSERVATION_RADIUS; dx <= OBSERVATION_RADIUS; dx += step) {
            for (int dz = -OBSERVATION_RADIUS; dz <= OBSERVATION_RADIUS; dz += step) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared <= OBSERVATION_RADIUS * OBSERVATION_RADIUS) {
                    offsets.add(new ColumnOffset(dx, dz, distanceSquared));
                }
            }
        }
        offsets.sort(Comparator.comparingInt(ColumnOffset::distanceSquared));
        return List.copyOf(offsets);
    }

    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("target", canonicalTarget == null ? r.target : canonicalTarget);
        data.put("verified", verifiedPosition != null);
        data.put("scope", "initial_client_view_and_first_person_loaded_terrain");
        data.put("max_distance", r.maxDistance);
        data.put("origin", Map.of("x", origin.getX(), "y", origin.getY(), "z", origin.getZ()));
        data.put("final_position", Map.of(
                "x", player.getX(), "y", player.getY(), "z", player.getZ()));
        data.put("farthest_body_distance", farthestBodyDistance);
        data.put("observation_cycles", observationCycles);
        data.put("loaded_columns_observed", observedLoadedColumns.size());
        data.put("loaded_column_samples", loadedSampleCount);
        data.put("farthest_loaded_observation", farthestObservedDistance);
        if (!observedLoadedColumns.isEmpty()) {
            data.put("observed_loaded_bounds", Map.of(
                    "min_x", observedMinX, "max_x", observedMaxX,
                    "min_z", observedMinZ, "max_z", observedMaxZ));
        }
        data.put("unloaded_column_samples", unloadedSampleCount);
        data.put("unapproachable_loaded_matches", unapproachableMatchCount);
        data.put("waypoints_attempted", waypointAttempts);
        data.put("waypoints_reached", waypointReached);
        data.put("waypoints_failed", waypointFailed);
        data.put("target_approaches_attempted", targetAttempts);
        data.put("failed_legs", List.copyOf(legFailures));

        List<Map<String, Object>> centers = exploredCenters.stream()
                .map(pos -> Map.<String, Object>of(
                        "x", pos.getX(), "y", pos.getY(), "z", pos.getZ(),
                        "distance_from_origin", horizontalDistance(origin, pos)))
                .toList();
        data.put("explored_centers", centers);

        List<Map<String, Object>> frontiers = unloadedFrontiers.stream()
                .map(pos -> Map.<String, Object>of(
                        "x", pos.getX(), "z", pos.getZ(),
                        "direction", CompassUtil.compass(
                                pos.getX() - origin.getX(), pos.getZ() - origin.getZ())))
                .toList();
        data.put("observed_unloaded_frontier_samples", frontiers);
        if (verifiedPosition != null) {
            data.put("verified_position", Map.of(
                    "x", verifiedPosition.getX(),
                    "y", verifiedPosition.getY(),
                    "z", verifiedPosition.getZ()));
            data.put("verification", verifiedDescription);
        } else {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("increase max_distance or choose another semantic landmark");
            suggestions.add("continue from the final position to search a different loaded frontier");
            if (!r.mayAlterTerrain && waypointFailed > 0) {
                suggestions.add("review failed_legs; enable may_alter_terrain only if those route changes are acceptable");
            }
            data.put("suggestions", suggestions);
        }
        return data;
    }

    @Override protected String successMessage() {
        return "verified " + canonicalTarget + " at " + shortPos(verifiedPosition)
                + " after real first-person exploration";
    }

    @Override protected String timeoutMessage() {
        return "semantic exploration stopped making verifiable progress before " + canonicalTarget
                + " was verified; the explored range and unloaded frontier are in data";
    }

    @Override protected String cancelledMessage() {
        return "semantic exploration was interrupted before verification";
    }

    @Override protected void cleanup() {
        stopActiveChild(TaskState.CANCELLED);
        super.cleanup();
    }
}
