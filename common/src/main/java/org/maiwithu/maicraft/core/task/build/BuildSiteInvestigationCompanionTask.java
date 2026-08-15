// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;
import org.maiwithu.maicraft.core.pathing.util.ClientSurfaceHeight;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToCompanionTask;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.core.tools.work.BuildTool;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.SemanticBuildPlanner;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskDispatch;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/**
 * Bounded read-observe/move/reobserve loop for semantic construction.
 * Movement is always a normal first-person MoveTo child with terrain alteration disabled.
 */
public final class BuildSiteInvestigationCompanionTask
        extends AbstractCompanionTask<BuildSiteInvestigationTaskRecord> {
    private static final int MAX_LEG_DISTANCE = 72;
    /** Initial leg lease. MoveTo renews its own record while verified route progress continues. */
    private static final int INITIAL_LEG_LEASE_TICKS = 90 * 20;
    private static final int SCOPE_TOLERANCE = 12;
    /** Reserve room for planner footprint/boundary scans beyond the body survey stance. */
    private static final int SEMANTIC_SCOPE_MARGIN = 48;
    private static final int WATER_SAMPLE_RADIUS = 20;
    private static final int[][] DIRECTIONS = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1},
            {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    private enum Stage { OBSERVE, TRAVEL_FRONTIER, BUILD }

    private record Candidate(BlockPos feet, int score, boolean shorelineEvidence) {}

    private BlockPos origin;
    private Goal.WorldPosition focus;
    private boolean waterfront;
    private Stage stage;

    private MoveToCompanionTask moveChild;
    private MoveToTaskRecord moveRecord;
    private Task buildChild;
    private TaskRecord buildRecord;
    private TaskResult buildResult;

    private final Set<Long> attemptedFrontiers = new LinkedHashSet<>();
    private int scanCycles;
    private int frontierAttempts;
    private int frontierReached;
    private int frontierFailed;
    private int shorelineWeightedCandidates;
    private int moveSerial;
    private double farthestBodyDistance;
    private boolean siteVerified;
    private String failureCode;
    private String lastProbeMessage;

    public BuildSiteInvestigationCompanionTask(
            LocalPlayer player, BuildSiteInvestigationTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        origin = player.blockPosition().immutable();
        focus = SemanticBuildPlanner.investigationAnchor(r.goal, player, IntentRuntime.get());
        waterfront = SemanticBuildPlanner.requiresWaterfront(r.goal);
        stage = Stage.OBSERVE;
    }

    @Override
    protected TaskState onTick() {
        double bodyDistance = horizontalDistance(origin, player.blockPosition());
        farthestBodyDistance = Math.max(farthestBodyDistance, bodyDistance);
        if (stage != Stage.BUILD && bodyDistance > r.maxDistance + SCOPE_TOLERANCE) {
            stopMove(TaskState.FAILED);
            return stopInvestigation("site_investigation_left_bound",
                    "first-person site investigation left its bounded radius and was stopped",
                    FailureType.NO_PATH);
        }
        return switch (stage) {
            case OBSERVE -> observeThenContinue();
            case TRAVEL_FRONTIER -> tickMove();
            case BUILD -> tickBuild();
        };
    }

    private TaskState observeThenContinue() {
        scanCycles++;
        if (!insideSemanticSurveyScope(player.blockPosition())) {
            lastProbeMessage = "first-person survey has not yet reached the bounded semantic target area";
            return continueToFrontier();
        }
        SemanticBuildPlanner.LoadedBuildProbe probe = SemanticBuildPlanner.probeLoadedBuildAt(
                r.goal, player, IntentRuntime.get(), player.blockPosition());
        lastProbeMessage = probe.message();
        if (probe.status() == SemanticBuildPlanner.LoadedBuildProbe.Status.INVALID) {
            return stopInvestigation(probe.failureCode(), probe.message(), FailureType.TARGET_LOST);
        }
        if (probe.status() == SemanticBuildPlanner.LoadedBuildProbe.Status.READY) {
            siteVerified = true;
            return startFrozenBuild(probe.buildArguments());
        }
        return continueToFrontier();
    }

    private TaskState continueToFrontier() {
        Candidate candidate = nextCandidate();
        if (candidate == null) return exhausted(
                frontierAttempts > 0 && frontierFailed == frontierAttempts
                        ? "site_investigation_frontiers_unreachable"
                        : "no_loaded_safe_exploration_frontier");
        if (candidate.shorelineEvidence()) shorelineWeightedCandidates++;
        frontierAttempts++;
        startMove(candidate.feet());
        stage = Stage.TRAVEL_FRONTIER;
        return TaskState.RUNNING;
    }

    private void startMove(BlockPos target) {
        long now = player.level().getGameTime();
        String parent = r.getToolCallId() == null ? "build-site" : r.getToolCallId();
        moveRecord = new MoveToTaskRecord(
                parent + "-internal-site-frontier-" + (++moveSerial),
                now + INITIAL_LEG_LEASE_TICKS,
                (double) target.getX(), null, (double) target.getZ(), null,
                false);
        moveChild = new MoveToCompanionTask(player, moveRecord);
    }

    private TaskState tickMove() {
        TaskState terminal;
        if (player.level().getGameTime() >= moveRecord.getDeadlineGameTime()) {
            moveChild.stop(player, Task.StopReason.REPLACED);
            terminal = TaskState.TIMEOUT;
        } else {
            terminal = runChild(moveChild);
            if (terminal == null) {
                r.extendDeadlineTo(moveRecord.getDeadlineGameTime());
                return TaskState.RUNNING;
            }
        }
        // Consume the child receipt locally. Move positions are deliberately not propagated.
        moveChild.result(terminal);
        moveChild = null;
        moveRecord = null;
        if (terminal == TaskState.SUCCESS) frontierReached++; else frontierFailed++;
        stage = Stage.OBSERVE;
        return TaskState.RUNNING;
    }

    private TaskState startFrozenBuild(JsonObject arguments) {
        AtomicReference<TaskRecord> captured = new AtomicReference<>();
        AtomicReference<String> immediate = new AtomicReference<>();
        String childId = (r.getToolCallId() == null ? "build-site" : r.getToolCallId())
                + "-frozen-build";
        try {
            TaskDispatch.captureNext(captured::set, () -> new BuildTool().onGameCall(
                    childId, arguments.deepCopy(), player, immediate::set));
        } catch (RuntimeException exception) {
            return stopInvestigation("frozen_build_dispatch_failed",
                    "the verified build plan could not start: " + safeMessage(exception),
                    FailureType.INTERNAL);
        }
        if (captured.get() == null) {
            return stopInvestigation("frozen_build_not_captured",
                    immediate.get() == null
                            ? "the verified build plan produced no child task"
                            : "the verified build plan ended before construction: " + immediate.get(),
                    FailureType.INTERNAL);
        }
        buildRecord = captured.get();
        r.extendDeadlineTo(buildRecord.getDeadlineGameTime());
        buildChild = TaskFactory.create(player, buildRecord);
        stage = Stage.BUILD;
        return TaskState.RUNNING;
    }

    private TaskState tickBuild() {
        TaskState terminal;
        if (player.level().getGameTime() >= buildRecord.getDeadlineGameTime()) {
            buildChild.stop(player, Task.StopReason.REPLACED);
            terminal = TaskState.TIMEOUT;
        } else {
            terminal = runChild(buildChild);
            if (terminal == null) {
                // A large build inherits the child's live deadline. Once the construction task
                // renews on verified cell progress, the semantic wrapper cannot cut it off early.
                r.extendDeadlineTo(buildRecord.getDeadlineGameTime());
                return TaskState.RUNNING;
            }
        }
        buildResult = buildChild.result(terminal);
        buildChild = null;
        buildRecord = null;
        if (terminal == TaskState.SUCCESS && buildResult.success()) {
            retainVerifiedPosition();
            return TaskState.SUCCESS;
        }
        failureCode = childString("failure_code", "frozen_build_failed");
        if (terminal == TaskState.TIMEOUT || buildResult.timedOut()) return TaskState.TIMEOUT;
        if (terminal == TaskState.CANCELLED || buildResult.interrupted()) return TaskState.CANCELLED;
        FailureType type = lastFailure() == FailureType.UNKNOWN ? FailureType.UNKNOWN : lastFailure();
        fail("construction at the verified site stopped: " + buildResult.message(), type);
        return TaskState.FAILED;
    }

    private Candidate nextCandidate() {
        ClientLevel level = player.clientLevel;
        BlockPos current = player.blockPosition();
        List<Candidate> candidates = new ArrayList<>();

        double focusDx = focus == null ? 0.0 : focus.x() - current.getX();
        double focusDz = focus == null ? 0.0 : focus.z() - current.getZ();
        if (focusDx * focusDx + focusDz * focusDz > 16.0 * 16.0) {
            addCandidateToward(level, current, focusDx, focusDz, 600, candidates);
            addCandidateToward(level, current, focusDx - focusDz, focusDz + focusDx,
                    240, candidates);
            addCandidateToward(level, current, focusDx + focusDz, focusDz - focusDx,
                    240, candidates);
        }

        int rotation = (scanCycles + frontierAttempts) & 7;
        for (int i = 0; i < DIRECTIONS.length; i++) {
            int[] direction = DIRECTIONS[(i + rotation) & 7];
            addCandidateToward(level, current, direction[0], direction[1], 0, candidates);
        }
        return candidates.stream()
                .filter(candidate -> !attemptedFrontiers.contains(columnKey(candidate.feet())))
                .max(Comparator.comparingInt(Candidate::score))
                .map(candidate -> {
                    attemptedFrontiers.add(columnKey(candidate.feet()));
                    return candidate;
                })
                .orElse(null);
    }

    private void addCandidateToward(
            ClientLevel level, BlockPos current, double dx, double dz,
            int semanticBias, List<Candidate> out) {
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.5) return;
        int desiredX = (int) Math.round(current.getX() + dx / length * MAX_LEG_DISTANCE);
        int desiredZ = (int) Math.round(current.getZ() + dz / length * MAX_LEG_DISTANCE);
        BlockPos frontier = loadedFrontierToward(level, current, desiredX, desiredZ);
        if (frontier == null) return;
        boolean shore = shorelineEvidence(level, frontier);
        int score = semanticBias + boundaryScore(level, frontier) * 30
                + (int) horizontalDistance(current, frontier);
        if (waterfront) score += shore ? 1_200 : 0;
        out.add(new Candidate(frontier, score, shore));
    }

    private BlockPos loadedFrontierToward(
            ClientLevel level, BlockPos current, int desiredX, int desiredZ) {
        double dx = desiredX - current.getX();
        double dz = desiredZ - current.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0) return null;
        for (double leg = Math.min(MAX_LEG_DISTANCE, distance); leg >= 16.0; leg -= 8.0) {
            int x = (int) Math.round(current.getX() + dx / distance * leg);
            int z = (int) Math.round(current.getZ() + dz / distance * leg);
            if (!insideScope(x, z)) continue;
            BlockPos feet = safeSurfaceFeet(level, x, z);
            if (feet != null) return feet;
        }
        return null;
    }

    private BlockPos safeSurfaceFeet(ClientLevel level, int x, int z) {
        int aroundY = Math.clamp(player.getBlockY(),
                level.getMinBuildHeight() + 2, level.getMaxBuildHeight() - 3);
        BlockPos column = new BlockPos(x, aroundY, z);
        if (!level.hasChunkAt(column)) return null;
        int top = ClientSurfaceHeight.motionBlockingNoLeaves(level, x, z);
        for (int y = Math.min(top + 2, level.getMaxBuildHeight() - 2);
             y >= Math.max(level.getMinBuildHeight() + 1, top - 8); y--) {
            BlockPos feet = new BlockPos(x, y, z);
            if (!level.isLoaded(feet.below()) || !level.isLoaded(feet.above())) continue;
            if (!BlockHelper.isStandable(level, feet)
                    || BlockHelper.isHazard(level, feet)
                    || BlockHelper.isHazard(level, feet.below())) continue;
            if (sensitiveForTravel(level, feet) || sensitiveForTravel(level, feet.below())) continue;
            return feet;
        }
        return null;
    }

    private static boolean sensitiveForTravel(ClientLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        var block = state.getBlock();
        return level.getBlockEntity(pos) != null || state.is(Blocks.FARMLAND)
                || block instanceof CropBlock || block instanceof StemBlock
                || block instanceof AttachedStemBlock || block instanceof CocoaBlock
                || state.is(Blocks.NETHER_WART) || state.is(Blocks.SWEET_BERRY_BUSH);
    }

    private boolean shorelineEvidence(ClientLevel level, BlockPos landFeet) {
        int water = 0;
        int land = 0;
        for (int dx = -WATER_SAMPLE_RADIUS; dx <= WATER_SAMPLE_RADIUS; dx += 5) {
            for (int dz = -WATER_SAMPLE_RADIUS; dz <= WATER_SAMPLE_RADIUS; dz += 5) {
                if (dx * dx + dz * dz > WATER_SAMPLE_RADIUS * WATER_SAMPLE_RADIUS) continue;
                int x = landFeet.getX() + dx;
                int z = landFeet.getZ() + dz;
                BlockPos column = new BlockPos(x, landFeet.getY(), z);
                if (!level.hasChunkAt(column)) continue;
                if (sourceWaterSurface(level, x, z, landFeet.getY())) water++;
                else if (safeSurfaceFeet(level, x, z) != null) land++;
            }
        }
        return water >= 3 && land >= 3;
    }

    private static boolean sourceWaterSurface(
            ClientLevel level, int x, int z, int aroundY) {
        for (int y = aroundY + 4; y >= aroundY - 10; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isLoaded(pos) || !level.isLoaded(pos.above())) return false;
            var fluid = level.getFluidState(pos);
            if (fluid.is(FluidTags.WATER) && fluid.isSource()
                    && level.getFluidState(pos.above()).isEmpty()) return true;
        }
        return false;
    }

    private static int boundaryScore(ClientLevel level, BlockPos pos) {
        int score = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int distance : new int[]{16, 32}) {
                BlockPos probe = pos.relative(direction, distance);
                if (!level.hasChunkAt(probe)) score++;
            }
        }
        return score;
    }

    private TaskState exhausted(String code) {
        return stopInvestigation(code,
                waterfront
                        ? "bounded first-person investigation found no safe loaded site with verified shoreline"
                        : "bounded first-person investigation found no safe loaded site",
                frontierAttempts > 0 && frontierFailed == frontierAttempts
                        ? FailureType.NO_PATH : FailureType.TARGET_LOST);
    }

    private TaskState stopInvestigation(String code, String message, FailureType type) {
        failureCode = code;
        fail(message, type);
        return TaskState.FAILED;
    }

    private boolean insideScope(double x, double z) {
        double dx = x - origin.getX();
        double dz = z - origin.getZ();
        return dx * dx + dz * dz <= (double) r.maxDistance * r.maxDistance;
    }

    private boolean insideSemanticSurveyScope(BlockPos pos) {
        if (focus == null) return true;
        double dx = pos.getX() - focus.x();
        double dz = pos.getZ() - focus.z();
        double radius = Math.max(32, r.maxDistance - SEMANTIC_SCOPE_MARGIN);
        return dx * dx + dz * dz <= radius * radius;
    }

    private static long columnKey(BlockPos pos) {
        return BlockPos.asLong(pos.getX(), 0, pos.getZ());
    }

    private static double horizontalDistance(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private String childString(String key, String fallback) {
        if (buildResult == null || buildResult.data() == null) return fallback;
        Object value = buildResult.data().get(key);
        return value == null ? fallback : value.toString();
    }

    private void retainVerifiedPosition() {
        if (buildResult == null || buildResult.data() == null) return;
        Object raw = buildResult.data().get("verified_position");
        if (!(raw instanceof Map<?, ?> position)) return;
        Object x = position.get("x"), y = position.get("y"), z = position.get("z");
        if (!(x instanceof Number nx) || !(y instanceof Number ny) || !(z instanceof Number nz)) {
            return;
        }
        Object dimension = position.get("dimension");
        String dimensionId = dimension == null
                ? player.level().dimension().location().toString() : dimension.toString();
        r.retainVerifiedPosition(new InternalPositionReceipt.Position(
                nx.intValue(), ny.intValue(), nz.intValue(), dimensionId));
    }

    private static String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage();
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
        if (buildChild != null) {
            buildChild.stop(player, Task.StopReason.REPLACED);
            buildChild.result(TaskState.CANCELLED);
            buildChild = null;
            buildRecord = null;
        }
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", "loaded_client_observation_and_first_person_frontiers");
        data.put("waterfront_required", waterfront);
        data.put("site_verified", siteVerified);
        data.put("max_distance", r.maxDistance);
        data.put("scan_cycles", scanCycles);
        data.put("frontier_legs_attempted", frontierAttempts);
        data.put("frontier_legs_reached", frontierReached);
        data.put("frontier_legs_failed", frontierFailed);
        data.put("shoreline_weighted_candidates_used", shorelineWeightedCandidates);
        data.put("farthest_body_distance", farthestBodyDistance);
        if (lastProbeMessage != null) data.put("last_probe", lastProbeMessage);
        if (buildResult != null) {
            Map<String, Object> construction = new LinkedHashMap<>();
            construction.put("success", buildResult.success());
            construction.put("message", buildResult.message());
            if (buildResult.data() != null) {
                copyAggregate(buildResult.data(), construction,
                        "requested", "completed", "placed", "cleared",
                        "required_materials", "missing_materials", "failure_code",
                        "world_change_uncertain", "safe_to_retry_without_observation",
                        "material_policy", "complete_supply_prepared_before_construction",
                        "total_material_ledger", "initial_remaining_material_ledger",
                        "remaining_material_ledger", "remaining_cells", "goal_satisfied",
                        "issues", "requires_decision", "recovery_options");
            }
            data.put("construction", construction);
        }
        if (failureCode != null || !siteVerified) {
            data.put("failure_code", failureCode == null
                    ? "site_investigation_interrupted" : failureCode);
            data.put("recoverable", true);
            data.put("requires_narration", true);
            data.put("requires_decision", true);
            data.put("recovery_options", List.of(
                    "continue the same semantic build from a different suitable area",
                    "reduce or change the footprint, terrain fit, or waterfront features",
                    "authorize replace_existing only after reviewing terrain removal",
                    "stop and leave the world unchanged"));
        }
        return data;
    }

    private static void copyAggregate(
            Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) target.put(key, source.get(key));
        }
    }

    @Override
    protected String successMessage() {
        return "verified a safe site through bounded first-person investigation and completed the frozen build plan";
    }

    @Override
    protected String timeoutMessage() {
        return siteVerified
                ? "construction at the verified site stopped making verifiable progress"
                : "first-person site investigation stopped making verifiable progress before a site was verified";
    }

    @Override
    protected String cancelledMessage() {
        return siteVerified
                ? "construction at the verified site was interrupted"
                : "site investigation was interrupted before any build plan was frozen";
    }
}
