// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * 附近没有合适地块时，让角色走到已加载的候选位置，重新观察；地块确认后把固定方案交给普通建筑任务。
 * 勘察移动不允许改地形；只把可站位置当作去看一看的候选，不因地图上看着合适就当成建造成功。
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
    /**
     * Frontier columns are sampled eight blocks apart below. Treat that sampling resolution as
     * one observation neighbourhood as well: an unreachable x/z and x+1/z are not two new facts.
     */
    private static final int FRONTIER_SAMPLE_STEP = 8;
    /** Keep the render-thread shore search incremental; this is a compute yield, not a task cap. */
    private static final int DRY_SHORE_COLUMNS_PER_TICK = 192;
    private static final int[][] DIRECTIONS = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1},
            {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    private enum Stage { OBSERVE, TRAVEL_FRONTIER, BUILD }

    private enum CandidateKind { DRY_SHORE_RETURN, SURVEY_FRONTIER }

    private record Candidate(
            BlockPos feet, int score, boolean shorelineEvidence, CandidateKind kind) {}
    private record ShoreOffset(int dx, int dz, int distanceSquared) {}

    /** Every column within one movement leg, ordered by true horizontal distance. */
    private static final List<ShoreOffset> DRY_SHORE_OFFSETS = dryShoreOffsets();

    private BlockPos origin;
    private Goal.WorldPosition focus;
    private boolean waterfront;
    private Stage stage;

    private MoveToCompanionTask moveChild;
    private MoveToTaskRecord moveRecord;
    private Task buildChild;
    private TaskRecord buildRecord;
    private TaskResult buildResult;
    private Candidate activeMoveCandidate;

    /** Coarse observation neighbourhoods already tried by ordinary survey movement. */
    private final List<BlockPos> attemptedFrontiers = new ArrayList<>();
    /** Exact dry cells whose own egress move failed; adjacent shore cells remain eligible. */
    private final Set<Long> failedDryShoreReturns = new HashSet<>();
    private BlockPos dryShoreSearchCenter;
    private int dryShoreSearchIndex;
    private int scanCycles;
    private int frontierAttempts;
    private int frontierReached;
    private int frontierFailed;
    private int shorelineWeightedCandidates;
    private int dryShoreSearches;
    private int dryShoreReturns;
    private String lastDryShoreFailure;
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
    // 记录开始勘察时的位置作为总活动范围中心；目标地点则另外用作探索方向和实际选址范围中心。
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
        // 勘察阶段离起点超过 384 格加 12 格容差就停止；已经交给建筑子任务后不再用这条移动范围判断。
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

    // 每次移动结束都重新观察：先回干地，再确认进入目标附近，最后问规划器这片已加载地形是否能建。
    private TaskState observeThenContinue() {
        scanCycles++;
        if (bodyInWater()) {
            lastProbeMessage = "the first-person body is in water; returning to the nearest "
                    + "loaded dry stance before surveying a construction site";
            return continueToDryShore();
        }
        resetDryShoreSearch();
        BlockPos surveyFeet = BlockHelper.playerFeet(
                player.clientLevel, player.getX(), player.getY(), player.getZ());
        if (!insideSemanticSurveyScope(surveyFeet)) {
            lastProbeMessage = "first-person survey has not yet reached the bounded semantic target area";
            return continueToFrontier();
        }
        SemanticBuildPlanner.LoadedBuildProbe probe = SemanticBuildPlanner.probeLoadedBuildAt(
                r.goal, player, IntentRuntime.get(), surveyFeet);
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

    /**
     * 角色还在水里时，先寻找周围已加载的干地。按水平距离从近到远逐列检查，不按实际路径长度或高度排序。
     * 每刻最多检查 192 列，保留进度；找到后用精确站位移动，走不到的坐标记下来再试其他候选。
     */
    private TaskState continueToDryShore() {
        ClientLevel level = player.clientLevel;
        BlockPos body = player.blockPosition();
        if (dryShoreSearchCenter == null) {
            dryShoreSearchCenter = body.immutable();
            dryShoreSearchIndex = 0;
            dryShoreSearches++;
        }

        int budget = DRY_SHORE_COLUMNS_PER_TICK;
        while (budget-- > 0 && dryShoreSearchIndex < DRY_SHORE_OFFSETS.size()) {
            ShoreOffset offset = DRY_SHORE_OFFSETS.get(dryShoreSearchIndex++);
            int x = dryShoreSearchCenter.getX() + offset.dx();
            int z = dryShoreSearchCenter.getZ() + offset.dz();
            if (!insideScope(x, z)) continue;
            BlockPos feet = safeDrySurfaceFeet(level, x, z);
            if (feet == null || failedDryShoreReturns.contains(feet.asLong())) continue;
            // The offset list is globally distance-sorted, so the first valid result is the
            // nearest loaded dry column. Shoreline is a fact about that dry stance, never
            // permission to substitute a water cell.
            Candidate selected = new Candidate(feet, -offset.distanceSquared(),
                    shorelineEvidence(level, feet), CandidateKind.DRY_SHORE_RETURN);
            resetDryShoreSearch();
            startMove(selected);
            stage = Stage.TRAVEL_FRONTIER;
            return TaskState.RUNNING;
        }
        if (dryShoreSearchIndex < DRY_SHORE_OFFSETS.size()) return TaskState.RUNNING;
        resetDryShoreSearch();
        boolean observedButUnreachable = !failedDryShoreReturns.isEmpty();
        String message = observedButUnreachable
                ? "loaded dry survey stances were observed, but first-person movement could not reach any of them"
                        + (lastDryShoreFailure == null
                                ? "" : "; last movement failure: " + lastDryShoreFailure)
                : "the body is in water and no loaded dry survey stance was observed";
        return stopInvestigation(
                observedButUnreachable
                        ? "loaded_dry_shore_stances_unreachable"
                        : "no_loaded_dry_shore_stance",
                message,
                observedButUnreachable ? FailureType.NO_PATH : FailureType.TARGET_LOST);
    }

    // 没有地块就选下一处观察位置；所有候选已试过或没有安全干地时，报告勘察耗尽。
    private TaskState continueToFrontier() {
        Candidate candidate = nextCandidate();
        if (candidate == null) return exhausted(
                frontierAttempts > 0 && frontierFailed == frontierAttempts
                        ? "site_investigation_frontiers_unreachable"
                        : "no_loaded_safe_exploration_frontier");
        if (candidate.shorelineEvidence()) shorelineWeightedCandidates++;
        frontierAttempts++;
        startMove(candidate);
        stage = Stage.TRAVEL_FRONTIER;
        return TaskState.RUNNING;
    }

    // 每段移动先给九十秒；移动任务若持续确认前进可延长自己的期限，父任务会跟着延长。
    private void startMove(Candidate candidate) {
        BlockPos target = candidate.feet();
        activeMoveCandidate = candidate;
        long now = player.level().getGameTime();
        String parent = r.getToolCallId() == null ? "build-site" : r.getToolCallId();
        moveRecord = MoveToTaskRecord.strictStance(
                parent + "-internal-site-frontier-" + (++moveSerial),
                now + INITIAL_LEG_LEASE_TICKS, target, false);
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
        TaskResult moveResult = moveChild.result(terminal);
        moveChild = null;
        moveRecord = null;
        Candidate completedCandidate = activeMoveCandidate;
        boolean verifiedArrival = terminal == TaskState.SUCCESS
                && verifyMoveArrival(completedCandidate);
        if (verifiedArrival) {
            if (completedCandidate.kind() == CandidateKind.DRY_SHORE_RETURN) {
                dryShoreReturns++;
            } else {
                frontierReached++;
            }
        } else {
            if (completedCandidate != null
                    && completedCandidate.kind() == CandidateKind.DRY_SHORE_RETURN) {
                failedDryShoreReturns.add(completedCandidate.feet().asLong());
                lastDryShoreFailure = moveResult == null || moveResult.message() == null
                        ? terminal.name().toLowerCase()
                        : moveResult.message();
            } else {
                frontierFailed++;
            }
            if (terminal == TaskState.SUCCESS) {
                lastProbeMessage = "the movement child stopped near its survey target, but the "
                        + "live body did not occupy the exact verified dry stance";
            }
        }
        activeMoveCandidate = null;
        stage = Stage.OBSERVE;
        return TaskState.RUNNING;
    }

    // 移动子任务说成功后，仍要检查身体真的在那格干地；沿岸候选还需重新看到岸边水陆证据。
    private boolean verifyMoveArrival(Candidate candidate) {
        if (candidate == null) return false;
        ClientLevel level = player.clientLevel;
        BlockPos actualFeet = BlockHelper.playerFeet(
                level, player.getX(), player.getY(), player.getZ());
        if (!actualFeet.equals(candidate.feet())
                || !BlockHelper.isDryStandable(level, actualFeet)) {
            return false;
        }
        // Returning from water promises only exact, dry occupancy. Shoreline evidence ranks the
        // egress candidate but must never reject a successful landing. It is re-confirmed only
        // when a waterfront survey frontier actually claimed that semantic property.
        if (candidate.kind() == CandidateKind.DRY_SHORE_RETURN) return true;
        return !waterfront || !candidate.shorelineEvidence()
                || shorelineEvidence(level, actualFeet);
    }

    // 复用 BuildTool 生成同样的建筑或供料任务，但捕获为当前任务的子任务，防止另派任务替换掉勘察流程。
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
        r.projectPlan(buildRecord instanceof BuildTaskRecord plan ? plan
                : buildRecord instanceof org.maiwithu.maicraft.core.task.supply.SemanticBuildSupplyTaskRecord supply
                ? supply.plan : null);
        r.extendDeadlineTo(buildRecord.getDeadlineGameTime());
        buildChild = TaskFactory.create(player, buildRecord);
        stage = Stage.BUILD;
        return TaskState.RUNNING;
    }

    // 把子任务的期限、成功或取消结果接回来；选址成功本身不代表房子已盖完。
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

    // 优先朝目标及其左右偏转方向探索，再加入八个常规方向；最后选分数最高且没在附近试过的位置。
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
                .filter(candidate -> !alreadyAttempted(candidate.feet()))
                .max(Comparator.comparingInt(Candidate::score))
                .map(candidate -> {
                    rememberAttempt(candidate.feet());
                    return candidate;
                })
                .orElse(null);
    }

    // 方向只决定去哪里取候选；靠近未加载区域、走得较远会加分，需要滨水建筑时岸边证据另加较高分。
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
        out.add(new Candidate(frontier, score, shore, CandidateKind.SURVEY_FRONTIER));
    }

    // 沿这个方向从 72 格远处向回每八格取样，只试至少 16 格外的已加载干地，并非逐格搜索整片范围。
    private BlockPos loadedFrontierToward(
            ClientLevel level, BlockPos current, int desiredX, int desiredZ) {
        double dx = desiredX - current.getX();
        double dz = desiredZ - current.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0) return null;
        for (double leg = Math.min(MAX_LEG_DISTANCE, distance);
             leg >= 16.0; leg -= FRONTIER_SAMPLE_STEP) {
            int x = (int) Math.round(current.getX() + dx / distance * leg);
            int z = (int) Math.round(current.getZ() + dz / distance * leg);
            if (!insideScope(x, z)) continue;
            BlockPos feet = safeDrySurfaceFeet(level, x, z);
            if (feet != null) return feet;
        }
        return null;
    }

    // 只围绕该列地表高度上方两格到下方八格找干燥落脚点，避开危险方块、农田、作物和方块实体。
    private BlockPos safeDrySurfaceFeet(ClientLevel level, int x, int z) {
        int aroundY = Math.clamp(player.getBlockY(),
                level.getMinBuildHeight() + 2, level.getMaxBuildHeight() - 3);
        BlockPos column = new BlockPos(x, aroundY, z);
        if (!level.hasChunkAt(column)) return null;
        int top = ClientSurfaceHeight.motionBlockingNoLeaves(level, x, z);
        for (int y = Math.min(top + 2, level.getMaxBuildHeight() - 2);
             y >= Math.max(level.getMinBuildHeight() + 1, top - 8); y--) {
            BlockPos feet = new BlockPos(x, y, z);
            if (!level.isLoaded(feet.below()) || !level.isLoaded(feet.above())) continue;
            if (!BlockHelper.isDryStandable(level, feet)
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

    // 在周围二十格内间隔五格抽样；至少看到三个水面样本和三个干地样本才加岸边分，不在此判断最终码头长度。
    private boolean shorelineEvidence(ClientLevel level, BlockPos landFeet) {
        if (!BlockHelper.isDryStandable(level, landFeet)) return false;
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
                else if (safeDrySurfaceFeet(level, x, z) != null) land++;
            }
        }
        return water >= 3 && land >= 3;
    }

    private boolean bodyInWater() {
        ClientLevel level = player.clientLevel;
        BlockPos feet = BlockHelper.playerFeet(level, player.getX(), player.getY(), player.getZ());
        return player.isInWater() || player.isEyeInFluid(FluidTags.WATER)
                || level.getFluidState(feet).is(FluidTags.WATER)
                || level.getFluidState(feet.above()).is(FluidTags.WATER);
    }

    // 预先排列 72 格圆内各列，按水平距离排序；排除当前同一列，寻找干地时不重新生成这张表。
    private static List<ShoreOffset> dryShoreOffsets() {
        List<ShoreOffset> offsets = new ArrayList<>();
        int radiusSquared = MAX_LEG_DISTANCE * MAX_LEG_DISTANCE;
        for (int dx = -MAX_LEG_DISTANCE; dx <= MAX_LEG_DISTANCE; dx++) {
            for (int dz = -MAX_LEG_DISTANCE; dz <= MAX_LEG_DISTANCE; dz++) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared == 0 || distanceSquared > radiusSquared) continue;
                offsets.add(new ShoreOffset(dx, dz, distanceSquared));
            }
        }
        offsets.sort(Comparator.comparingInt(ShoreOffset::distanceSquared));
        return List.copyOf(offsets);
    }

    // 离以前候选水平八格以内就算同一片已试区域，无论之前是到达还是失败，避免在相邻位置反复打转。
    private boolean alreadyAttempted(BlockPos candidate) {
        double radiusSquared = (double) FRONTIER_SAMPLE_STEP * FRONTIER_SAMPLE_STEP;
        for (BlockPos attempted : attemptedFrontiers) {
            double dx = candidate.getX() - attempted.getX();
            double dz = candidate.getZ() - attempted.getZ();
            if (dx * dx + dz * dz <= radiusSquared) return true;
        }
        return false;
    }

    private void rememberAttempt(BlockPos candidate) {
        attemptedFrontiers.add(candidate.immutable());
    }

    private void resetDryShoreSearch() {
        dryShoreSearchCenter = null;
        dryShoreSearchIndex = 0;
    }

    // 只查参考高度上四格到下十格：必须为水源，且上方流体为空；水面上方是否有实体方块不在这里判断。
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

    // 四个方向各看 16 格和 32 格外的区块，越多未加载区块说明越接近目前观察边缘。
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

    // 总探索圆以任务开始位置为圆心；不会因为一路前进就把允许范围跟着挪走。
    private boolean insideScope(double x, double z) {
        double dx = x - origin.getX();
        double dz = z - origin.getZ();
        return dx * dx + dz * dz <= (double) r.maxDistance * r.maxDistance;
    }

    // 实际选址另要求靠近语义目标，半径为 336 格；它与起点的 384 格范围同时生效。
    private boolean insideSemanticSurveyScope(BlockPos pos) {
        if (focus == null) return true;
        double dx = pos.getX() - focus.x();
        double dz = pos.getZ() - focus.z();
        double radius = Math.max(32, r.maxDistance - SEMANTIC_SCOPE_MARGIN);
        return dx * dx + dz * dz <= radius * radius;
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

    // 只从建筑成功结果里取已经核对过的场地坐标，不把路途中试探的观察位置作为建筑回执。
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
        activeMoveCandidate = null;
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
    // 报告勘察次数、走到哪里以及建筑子结果。site_verified 只表示地块通过检查，建筑是否成功另在 construction 中说明。
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
        data.put("dry_shore_searches", dryShoreSearches);
        data.put("dry_shore_returns", dryShoreReturns);
        if ("loaded_dry_shore_stances_unreachable".equals(failureCode)
                && lastDryShoreFailure != null) {
            data.put("last_dry_shore_failure", lastDryShoreFailure);
        }
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
