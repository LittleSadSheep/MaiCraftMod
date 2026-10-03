/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 only.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;
import org.maiwithu.maicraft.core.pathing.calc.PlanningWorkProgress;

import baritone.Baritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.event.events.*;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.PathingCommand;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.PathCalculationResult;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.calc.LoadedFrontier;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.DescentAdmissionLog;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.path.PathExecutor;
import baritone.utils.PathRenderer;
import baritone.utils.PathingCommandContext;
import baritone.utils.pathing.Favoring;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.core.pathing.calc.PathPlannerPool;
import org.maiwithu.maicraft.core.pathing.baritone.SwimTravelControl;
import org.maiwithu.maicraft.core.Constants;

public final class PathingBehavior extends Behavior implements IPathingBehavior, Helper {

    private PathExecutor current;
    private PathExecutor next;

    private Goal goal;
    private CalculationContext context;
    private CalculationContext customCalculationContext;

    /*eta*/
    private int ticksElapsedSoFar;
    private BetterBlockPos startPosition;

    private boolean safeToCancel;
    private boolean pauseRequestedLastTick;
    private boolean unpausedLastTick;
    private boolean pausedThisTick;
    private boolean cancelRequested;
    private boolean calcFailedLastTick;

    private volatile AbstractNodeCostSearch inProgress;
    // Only the client thread owns these handles; detached futures cannot affect a later owner.
    private CompletableFuture<PathCalculationResult> pendingCalculation;
    private BlockPos calculationStart;
    private Goal calculationGoal;
    private Level calculationWorld;
    private long calculationTimeoutMillis;
    private PathCalculationResult.Type lastCalculationResult;
    private boolean calculationStalled;
    private int searchRecoveries;
    private BlockPos recoveryOrigin;
    private Goal recoveryGoal;
    private Level recoveryWorld;
    private Map<String, Object> lastRecovery = Map.of();
    private long drivenTicks;
    private PlanningWorkProgress planningWork;
    private BlockPos workOrigin;
    private Goal workGoal;
    private Level workWorld;
    private LoadedFrontier workFrontier;
    private BlockPos failedPlanAheadStart;
    private LoadedFrontier calculationFrontier, failedPlanAheadFrontier;
    private final SwimTravelControl.BodyState swimBodyState = new SwimTravelControl.BodyState();

    private boolean lastAutoJump;

    private BetterBlockPos expectedSegmentStart;

    private final LinkedBlockingQueue<PathEvent> toDispatch = new LinkedBlockingQueue<>();

    public PathingBehavior(Baritone baritone) {
        super(baritone);
    }

    private void queuePathEvent(PathEvent event) {
        toDispatch.add(event);
    }

    private void dispatchEvents() {
        ArrayList<PathEvent> curr = new ArrayList<>();
        toDispatch.drainTo(curr);
        calcFailedLastTick = curr.contains(PathEvent.CALC_FAILED);
        for (PathEvent event : curr) {
            baritone.getGameEventHandler().onPathEvent(event);
        }
    }

    /**
     * Drop events belonging to a navigation owner that has just been replaced.
     *
     * <p>Path calculation is asynchronous, while path events are dispatched on the following
     * client tick. The embedded integration has one shared Baritone instance for a succession of
     * semantic tasks, so an already queued {@code CALC_FAILED} must not be delivered to the next
     * owner. {@link #forceCancel()} detaches the cancelled calculation; this method clears the
     * remaining client-thread event mailbox and its one-tick failure latch.</p>
     */
    public void discardPendingPathEvents() {
        discardPendingPathEvents(true);
    }

    /** 自动重发同一导航只清掉旧事件，不能顺带清掉检查量高水位来刷新无进展预算。 */
    public void discardPendingPathEvents(boolean resetWork) {
        toDispatch.clear();
        calcFailedLastTick = false;
        if (resetWork) { planningWork = null; workOrigin = null; workGoal = null; workWorld = null; workFrontier = null; }
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            swimBodyState.clear();
            secretInternalSegmentCancel();
            baritone.getPathingControlManager().cancelEverything();
            return;
        }

        expectedSegmentStart = pathStart();
        drivenTicks++;
        acceptCalculatedPath();
        dispatchEvents();
        baritone.getPathingControlManager().preTick();
        tickPath();
        ticksElapsedSoFar++;
        dispatchEvents();
    }

    @Override
    public void onPlayerSprintState(SprintStateEvent event) {
        if (isPathing()) {
            event.setState(current.isSprinting());
        }
    }

    private void tickPath() {
        pausedThisTick = false;
        if (pauseRequestedLastTick && safeToCancel) {
            pauseRequestedLastTick = false;
            if (unpausedLastTick) {
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
            }
            unpausedLastTick = false;
            pausedThisTick = true;
            return;
        }
        unpausedLastTick = true;
        if (cancelRequested) {
            cancelRequested = false;
            baritone.getInputOverrideHandler().clearAllKeys();
        }
        if (inProgress != null) {
            // we are calculating
            // are we calculating the right thing though? 🤔
            BetterBlockPos calcFrom = inProgress.getStart();
            Optional<IPath> currentBest = inProgress.bestPathSoFar();
            if ((current == null || !current.getPath().getDest().equals(calcFrom)) // if current ends in inProgress's start, then we're ok
                    && !calcFrom.equals(ctx.playerFeet()) && !calcFrom.equals(expectedSegmentStart) // if current starts in our playerFeet or pathStart, then we're ok
                    && (!currentBest.isPresent() || (!currentBest.get().positions().contains(ctx.playerFeet()) && !currentBest.get().positions().contains(expectedSegmentStart))) // if
            ) {
                // when it was *just* started, currentBest will be empty so we need to also check calcFrom since that's always present
                cancelCalculation();
            }
        }
        if (current == null) {
            return;
        }
        safeToCancel = current.onTick();
        if (current.failed() || current.finished()) {
            current = null;
            if (goal == null || goal.isInGoal(ctx.playerFeet())) {
                logDebug("All done. At " + goal);
                queuePathEvent(PathEvent.AT_GOAL);
                next = null;
                if (Baritone.settings().disconnectOnArrival.value) {
                    ctx.world().disconnect();
                }
                return;
            }
            if (next != null && !next.getPath().positions().contains(ctx.playerFeet()) && !next.getPath().positions().contains(expectedSegmentStart)) { // can contain either one
                // if the current path failed, we may not actually be on the next one, so make sure
                logDebug("Discarding next path as it does not contain current position");
                // for example if we had a nicely planned ahead path that starts where current ends
                // that's all fine and good
                // but if we fail in the middle of current
                // we're nowhere close to our planned ahead path
                // so need to discard it sadly.
                queuePathEvent(PathEvent.DISCARD_NEXT);
                next = null;
            }
            if (next != null) {
                logDebug("Continuing on to planned next path");
                queuePathEvent(PathEvent.CONTINUING_ONTO_PLANNED_NEXT);
                current = next;
                next = null;
                current.onTick(); // don't waste a tick doing nothing, get started right away
                return;
            }
            // at this point, current just ended, but we aren't in the goal and have no plan for the future
            if (inProgress != null) {
                queuePathEvent(PathEvent.PATH_FINISHED_NEXT_STILL_CALCULATING);
                return;
            }
            // we aren't calculating
            queuePathEvent(PathEvent.CALC_STARTED);
            findPathInNewThread(expectedSegmentStart, true);
            return;
        }
        // at this point, we know current is in progress
        if (safeToCancel && next != null && next.snipsnapifpossible()) {
            // a movement just ended; jump directly onto the next path
            logDebug("Splicing into planned next path early...");
            queuePathEvent(PathEvent.SPLICING_ONTO_NEXT_EARLY);
            current = next;
            next = null;
            current.onTick();
            return;
        }
        if (Baritone.settings().splicePath.value) {
            current = current.trySplice(next);
        }
        if (next != null && current.getPath().getDest().equals(next.getPath().getDest())) {
            next = null;
        }
        if (inProgress != null) {
            // if we aren't calculating right now
            return;
        }
        if (next != null) {
            // and we have no plan for what to do next
            return;
        }
        if (goal == null || goal.isInGoal(current.getPath().getDest())) {
            // and this path doesn't get us all the way there
            return;
        }
        // 提前续路失败时先继续走；接续口附近一旦加载新地形，就可再次后台计算，不必等旧路走完才重新起步。
        if (current.getPath().getDest().equals(failedPlanAheadStart)
                && (failedPlanAheadFrontier == null || !failedPlanAheadFrontier.hasNewTerrain(ctx.world()::isLoaded))) return;
        if (ticksRemainingInSegment(false).get() < Baritone.settings().planningTickLookahead.value) {
            // and this path has 7.5 seconds or less left
            // don't include the current movement so a very long last movement (e.g. descend) doesn't trip it up
            // if we actually included current, it wouldn't start planning ahead until the last movement was done, if the last movement took more than 7.5 seconds on its own
            logDebug("Path almost over. Planning ahead...");
            queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_STARTED);
            findPathInNewThread(current.getPath().getDest(), false);
        }
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (current != null) {
            switch (event.getState()) {
                case PRE:
                    lastAutoJump = ctx.minecraft().options.autoJump().get();
                    ctx.minecraft().options.autoJump().set(false);
                    break;
                case POST:
                    ctx.minecraft().options.autoJump().set(lastAutoJump);
                    break;
                default:
                    break;
            }
        }
    }

    public void secretInternalSetGoal(Goal goal) {
        this.goal = goal;
    }

    public boolean secretInternalSetGoalAndPath(PathingCommand command) {
        secretInternalSetGoal(command.goal);
        if (command instanceof PathingCommandContext) {
            customCalculationContext = ((PathingCommandContext) command).desiredCalcContext;
            context = customCalculationContext;
        } else {
            customCalculationContext = null;
            // Execution needs today's world, not a new copied ClientChunkCache every tick.
            // A separate worker context is captured only when an actual search is dispatched.
            context = new CalculationContext(baritone, false);
        }
        if (goal == null) {
            return false;
        }
        if (goal.isInGoal(ctx.playerFeet())) {
            return false;
        }
        if (current != null) {
            return false;
        }
        if (inProgress != null) {
            return false;
        }
        queuePathEvent(PathEvent.CALC_STARTED);
        DescentAdmissionLog.beginSearch();
        findPathInNewThread(expectedSegmentStart, true);
        return true;
    }

    @Override
    public Goal getGoal() {
        return goal;
    }

    @Override
    public boolean isPathing() {
        return hasPath() && !pausedThisTick;
    }

    @Override
    public PathExecutor getCurrent() {
        return current;
    }

    @Override
    public PathExecutor getNext() {
        return next;
    }

    /** Physical air recovery survives replans and plan-ahead segment construction. */
    public SwimTravelControl swimTravelControl() {
        return swimBodyState.bind(ctx.player(), ctx.world());
    }

    @Override
    public Optional<AbstractNodeCostSearch> getInProgress() {
        return Optional.ofNullable(inProgress);
    }

    public boolean isSafeToCancel() {
        if (current == null) {
            return !baritone.getElytraProcess().isActive() || baritone.getElytraProcess().isSafeToCancel();
        }
        return safeToCancel;
    }

    public void requestPause() {
        pauseRequestedLastTick = true;
    }

    public boolean cancelSegmentIfSafe() {
        if (isSafeToCancel()) {
            secretInternalSegmentCancel();
            return true;
        }
        return false;
    }

    @Override
    public boolean cancelEverything() {
        boolean doIt = isSafeToCancel();
        if (doIt) {
            secretInternalSegmentCancel();
        }
        baritone.getPathingControlManager().cancelEverything(); // regardless of if we can stop the current segment, we can still stop the processes
        return doIt;
    }

    public boolean calcFailedLastTick() { // NOT exposed on public api
        return calcFailedLastTick;
    }

    public void softCancelIfSafe() {
        cancelCalculation();
        if (!isSafeToCancel()) {
            return;
        }
        current = null;
        next = null;
        cancelRequested = true;
        // do everything BUT clear keys
    }

    // just cancel the current path
    public void secretInternalSegmentCancel() {
        queuePathEvent(PathEvent.CANCELED);
        cancelCalculation();
        next = null;
        if (current != null) {
            current = null;
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
        }
    }

    @Override
    public void forceCancel() { // exposed on public api because :sob:
        cancelEverything();
        secretInternalSegmentCancel();
        // 新导航不能继承旧进程的暂停、失败标签或执行上下文；实际旧失败已由任务在退役前冻结。
        pauseRequestedLastTick = false; pausedThisTick = false; unpausedLastTick = false;
        context = null; customCalculationContext = null; lastCalculationResult = null; calculationStalled = false;
    }

    public CalculationContext secretInternalGetCalculationContext() {
        return context;
    }

    public Optional<Double> estimatedTicksToGoal() {
        BetterBlockPos currentPos = ctx.playerFeet();
        if (goal == null || currentPos == null || startPosition == null) {
            return Optional.empty();
        }
        if (goal.isInGoal(ctx.playerFeet())) {
            resetEstimatedTicksToGoal();
            return Optional.of(0.0);
        }
        if (ticksElapsedSoFar == 0) {
            return Optional.empty();
        }
        double current = goal.heuristic(currentPos.x, currentPos.y, currentPos.z);
        double start = goal.heuristic(startPosition.x, startPosition.y, startPosition.z);
        if (current == start) {// can't check above because current and start can be equal even if currentPos and startPosition are not
            return Optional.empty();
        }
        double eta = Math.abs(current - goal.heuristic()) * ticksElapsedSoFar / Math.abs(start - current);
        return Optional.of(eta);
    }

    private void resetEstimatedTicksToGoal() {
        resetEstimatedTicksToGoal(expectedSegmentStart);
    }

    private void resetEstimatedTicksToGoal(BlockPos start) {
        resetEstimatedTicksToGoal(new BetterBlockPos(start));
    }

    private void resetEstimatedTicksToGoal(BetterBlockPos start) {
        ticksElapsedSoFar = 0;
        startPosition = start;
    }

    /**
     * See issue #209
     *
     * @return The starting {@link BlockPos} for a new path
     */
    public BetterBlockPos pathStart() { // TODO move to a helper or util class
        BetterBlockPos feet = ctx.playerFeet();
        if (!MovementHelper.canWalkOn(ctx, feet.below())) {
            if (ctx.player().onGround()) {
                double playerX = ctx.player().position().x;
                double playerZ = ctx.player().position().z;
                ArrayList<BetterBlockPos> closest = new ArrayList<>();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        closest.add(new BetterBlockPos(feet.x + dx, feet.y, feet.z + dz));
                    }
                }
                closest.sort(Comparator.comparingDouble(pos -> ((pos.x + 0.5D) - playerX) * ((pos.x + 0.5D) - playerX) + ((pos.z + 0.5D) - playerZ) * ((pos.z + 0.5D) - playerZ)));
                for (int i = 0; i < 4; i++) {
                    BetterBlockPos possibleSupport = closest.get(i);
                    double xDist = Math.abs((possibleSupport.x + 0.5D) - playerX);
                    double zDist = Math.abs((possibleSupport.z + 0.5D) - playerZ);
                    if (xDist > 0.8 && zDist > 0.8) {
                        // can't possibly be sneaking off of this one, we're too far away
                        continue;
                    }
                    if (MovementHelper.canWalkOn(ctx, possibleSupport.below()) && MovementHelper.canWalkThrough(ctx, possibleSupport) && MovementHelper.canWalkThrough(ctx, possibleSupport.above())) {
                        // this is plausible
                        //logDebug("Faking path start assuming player is standing off the edge of a block");
                        return possibleSupport;
                    }
                }

            } else {
                // !onGround
                // we're in the middle of a jump
                if (MovementHelper.canWalkOn(ctx, feet.below().below())) {
                    //logDebug("Faking path start assuming player is midair and falling");
                    return feet.below();
                }
            }
        }
        return feet;
    }

    /** Start a worker calculation; client-owned state is never written from that worker. */
    private void findPathInNewThread(BlockPos start, boolean talkAboutIt) {
        if (inProgress != null) {
            throw new IllegalStateException("Already calculating a path");
        }
        Goal goal = this.goal;
        if (goal == null) return;
        CalculationContext searchContext = customCalculationContext == null
                ? new CalculationContext(baritone, true) : customCalculationContext;
        if (!searchContext.safeForThreadedUse) {
            throw new IllegalStateException("Improper context thread safety level");
        }
        long primaryTimeout = current == null ? Baritone.settings().primaryTimeoutMS.value
                : Baritone.settings().planAheadPrimaryTimeoutMS.value;
        long failureTimeout = current == null ? Baritone.settings().failureTimeoutMS.value
                : Baritone.settings().planAheadFailureTimeoutMS.value;
        // 原目标与起点未变时只允许一次搜索线程恢复；真正移动或换世界后才开始新的恢复预算。
        if (recoveryOrigin == null || recoveryOrigin.distSqr(start) > 4 || !Objects.equals(recoveryGoal, goal) || recoveryWorld != ctx.world()) {
            searchRecoveries = 0; recoveryOrigin = start.immutable(); recoveryGoal = goal; recoveryWorld = ctx.world(); lastRecovery = Map.of();
        }
        calculationStalled = false; lastCalculationResult = null;
        calculationTimeoutMillis = Math.max(10_000L, (Baritone.settings().slowPath.value
                ? Baritone.settings().slowPathTimeoutMS.value : failureTimeout) + 5_000L);
        AbstractNodeCostSearch pathfinder = createPathfinder(start, goal,
                current == null ? null : current.getPath(), searchContext);
        if (!Objects.equals(pathfinder.getGoal(), goal)) {
            logDebug("Simplifying " + goal.getClass() + " to GoalXZ due to distance");
        }
        if (talkAboutIt) logDebug("Searching from " + start + " to " + goal);
        if (current == null) failedPlanAheadStart = null;
        calculationStart = start.immutable();
        // 请求发出时冻结归属；角色被传送、换世界或改目标后，旧失败不能裁决新现场是否有路。
        calculationGoal = goal;
        calculationWorld = ctx.world();
        // 记搜索开始时的边界，避免计算期间加载的区块被失败回执吞掉而失去重试机会。
        calculationFrontier = LoadedFrontier.capture(start, ctx.world()::isLoaded);
        // 重试同一问题保留检查量高水位；实际换起点、目标或加载新地形后才接受新一轮计算进展。
        if (!Objects.equals(workOrigin, start) || !Objects.equals(workGoal, goal) || workWorld != ctx.world()
                || workFrontier == null || workFrontier.hasNewTerrain(ctx.world()::isLoaded)) workProgress().newScope();
        workOrigin = start.immutable(); workGoal = goal; workWorld = ctx.world(); workFrontier = calculationFrontier;
        inProgress = pathfinder;
        pendingCalculation = PathPlannerPool.submit(() -> pathfinder.calculate(primaryTimeout, failureTimeout));
    }

    /** Poll without blocking; installation and executor creation happen on the client thread. */
    private void acceptCalculatedPath() {
        if (pendingCalculation == null) return;
        planningProgressRevision();
        if (!pendingCalculation.isDone()) {
            // 节点展开或路径核查每次推进都补满预算；只在排队或内部读取持续无进展时恢复线程。
            if (PathPlannerPool.noProgressMillis(pendingCalculation) > calculationTimeoutMillis) recoverStalledCalculation();
            return;
        }
        PathCalculationResult result;
        try {
            result = pendingCalculation.getNow(null);
        } catch (RuntimeException failure) {
            logDirect("Path calculation failed: " + failure);
            result = new PathCalculationResult(PathCalculationResult.Type.EXCEPTION);
        }
        BlockPos start = calculationStart;
        Goal requestedGoal = calculationGoal;
        Level requestedWorld = calculationWorld;
        pendingCalculation = null;
        calculationStart = null;
        calculationGoal = null;
        calculationWorld = null;
        inProgress = null;
        if (result == null || result.getType() == PathCalculationResult.Type.CANCELLATION) return;
        // 旧世界的方块证据不能用于当前身体，即使传送前后坐标恰巧相同也要重新搜索。
        if (requestedWorld != ctx.world()) return;

        Optional<IPath> path = result.getPath();
        // 成功路径可按下方已有成员检查接续；无路结果没有可复用路段，只能归于原目标和原起点。
        // 丢弃过期失败后保持目标进程活动，下一刻从真实脚位搜索，不要求模型重发任务。
        if (path.isEmpty() && (!Objects.equals(requestedGoal, goal)
                || !Objects.equals(start, current == null ? expectedSegmentStart : current.getPath().getDest()))) return;
        lastCalculationResult = result.getType();
        if (current == null) {
            if (path.isPresent()) {
                if (path.get().positions().contains(expectedSegmentStart)) {
                    current = new PathExecutor(this, org.maiwithu.maicraft.core.pathing.baritone.GroundPathSmoothing.apply(baritone, path.get()));
                    resetEstimatedTicksToGoal(start);
                    queuePathEvent(PathEvent.CALC_FINISHED_NOW_EXECUTING);
                } else {
                    logDebug("Discarding path whose start no longer matches the player");
                }
            } else {
                // Exceptions also need to settle the owner instead of starting the same broken
                // calculation every tick forever. The original exception is logged by A*.
                queuePathEvent(PathEvent.CALC_FAILED);
            }
        } else if (next == null) {
            if (path.isPresent()) {
                if (path.get().getSrc().equals(current.getPath().getDest())) {
                    next = new PathExecutor(this, org.maiwithu.maicraft.core.pathing.baritone.GroundPathSmoothing.apply(baritone, path.get()));
                    queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_FINISHED);
                } else {
                    logDebug("Discarding next path whose start no longer matches this segment");
                }
            } else {
                failedPlanAheadStart = start;
                failedPlanAheadFrontier = calculationFrontier;
                queuePathEvent(PathEvent.NEXT_CALC_FAILED);
            }
        }
    }

    /** 只替换无实体动作的计算通道；旧图、前瞻缓存和结果句柄作废，新计算仍遵守原目标与地形许可。 */
    private void recoverStalledCalculation() {
        lastRecovery = PathPlannerPool.describe(pendingCalculation, true);
        boolean recovered = searchRecoveries == 0 && PathPlannerPool.recover(pendingCalculation);
        // 工作线程可能恰好在取证期间完成；保留已返回的真实结论，下刻正常接收，不制造竞态式停滞失败。
        if (!recovered && pendingCalculation.isDone() && !pendingCalculation.isCancelled()) return;
        cancelCalculation();
        if (recovered) {
            searchRecoveries++;
            if (current == null) context = null; // 前瞻卡住时仍保留正在落地的执行上下文。
            Constants.LOG.warn("[maicraft-path] planning_stall: isolated search generation and retrying the same route; {}", lastRecovery);
        } else {
            calculationStalled = true;
            Constants.LOG.warn("[maicraft-path] planning_stall: bounded recovery unavailable or exhausted; {}", lastRecovery);
            queuePathEvent(PathEvent.CALC_FAILED);
        }
    }

    public boolean calculationStalled() { return calculationStalled; }
    /** 客户端只读工作线程发布的计数，不遍历仍在修改的路径节点。 */
    public long planningProgressRevision() {
        return workProgress().observe(PathPlannerPool.completedUnits(pendingCalculation));
    }
    // 只在客户端首次计算或读取诊断时建立计数器，空闲导航同样可以给出零进展事实。
    private PlanningWorkProgress workProgress() {
        if (planningWork == null) planningWork = new PlanningWorkProgress();
        return planningWork;
    }
    public long drivenTicks() { return drivenTicks; }
    public boolean calculationErrored() { return lastCalculationResult == PathCalculationResult.Type.EXCEPTION; }

    /** 默认回执分别呈现真正无解、搜索停滞与已有执行路线，不能仅以“没有位移”反推搜索结论。 */
    public Map<String, Object> searchDiagnostics() {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("classification", calculationStalled ? "planning_stall" : pendingCalculation != null ? "planning"
                : current != null ? "executing" : lastCalculationResult == PathCalculationResult.Type.FAILURE
                ? "planning_no_solution" : calculationErrored() ? "planning_error" : "idle");
        facts.put("component", "baritone_search"); facts.put("pathing_ticks", drivenTicks);
        facts.put("last_result", lastCalculationResult == null ? "none" : lastCalculationResult.name().toLowerCase());
        facts.put("search", PathPlannerPool.describe(pendingCalculation, false));
        facts.put("planning_progress_revision", planningProgressRevision());
        facts.put("work_high_water", workProgress().highWater());
        facts.put("idle_budget_ms", calculationTimeoutMillis);
        facts.put("recovery_count", searchRecoveries); facts.put("last_recovery", lastRecovery == null ? Map.of() : lastRecovery);
        facts.put("rejected_descents", DescentAdmissionLog.snapshot());
        return Map.copyOf(facts);
    }

    /** Detach a cancelled future immediately, so it can never install a path for a newer owner. */
    private void cancelCalculation() {
        if (inProgress != null) inProgress.cancel();
        if (pendingCalculation != null) pendingCalculation.cancel(false);
        inProgress = null;
        pendingCalculation = null;
        calculationStart = null;
        calculationGoal = null;
        calculationWorld = null;
        failedPlanAheadStart = null;
        calculationFrontier = null; failedPlanAheadFrontier = null;
    }

    private AbstractNodeCostSearch createPathfinder(BlockPos start, Goal goal, IPath previous, CalculationContext context) {
        Goal transformed = goal;
        if (Baritone.settings().simplifyUnloadedYCoord.value && goal instanceof IGoalRenderPos) {
            BlockPos pos = ((IGoalRenderPos) goal).getGoalPos();
            if (!context.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
                transformed = new GoalXZ(pos.getX(), pos.getZ());
            }
        }
        Favoring favoring = new Favoring(context.getBaritone().getPlayerContext(), previous, context);
        BetterBlockPos feet = ctx.playerFeet();
        var realStart = new BetterBlockPos(start);
        var sub = feet.subtract(realStart);
        if (feet.getY() == realStart.getY() && Math.abs(sub.getX()) <= 1 && Math.abs(sub.getZ()) <= 1) {
            realStart = feet;
        }
        return new AStarPathFinder(realStart, start.getX(), start.getY(), start.getZ(), transformed, favoring, context);

    }

    @Override
    public void onRenderPass(RenderEvent event) {
        PathRenderer.render(event, this);
    }
}
