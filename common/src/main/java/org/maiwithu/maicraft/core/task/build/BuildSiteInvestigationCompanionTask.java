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
import net.minecraft.world.level.levelgen.Heightmap;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.util.BlockHelper;
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
    private static final int LEG_TIMEOUT_TICKS = 90 * 20;
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
    private long investigationDeadline;
    private Stage stage;

    private MoveToCompanionTask moveChild;
    private MoveToTaskRecord moveRecord;
    private long legDeadline;
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
        investigationDeadline = Math.min(r.getDeadlineGameTime(),
                player.level().getGameTime() + r.maxInvestigationTicks);
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
        if (player.level().getGameTime() >= investigationDeadline) {
            failureCode = "site_investigation_time_limit";
            return TaskState.TIMEOUT;
        }
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
        if (frontierAttempts >= r.maxFrontierLegs) {
            return exhausted("site_investigation_leg_limit");
        }
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
                Math.min(investigationDeadline, now + LEG_TIMEOUT_TICKS),
                (double) target.getX(), null, (double) target.getZ(), null,
                false);
        // MoveTo owns a progress lease and may extend its record deadline. This separate ceiling
        // keeps an investigation leg bounded even while the ordinary navigator is progressing.
        legDeadline = Math.min(investigationDeadline, now + LEG_TIMEOUT_TICKS);
        moveChild = new MoveToCompanionTask(player, moveRecord);
    }

    private TaskState tickMove() {
        TaskState terminal;
        if (player.level().getGameTime() >= legDeadline) {
            moveChild.stop(player, Task.StopReason.REPLACED);
            terminal = TaskState.TIMEOUT;
        } else {
            terminal = runChild(moveChild);
            if (terminal == null) return TaskState.RUNNING;
        }
        // Consume the child receipt locally. Move positions are deliberately not propagated.
        moveChild.result(terminal);
        moveChild = null;
        moveRecord = null;
        legDeadline = 0L;
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
            if (terminal == null) return TaskState.RUNNING;
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
