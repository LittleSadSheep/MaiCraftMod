/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

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
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.path.PathExecutor;
import baritone.utils.PathRenderer;
import baritone.utils.PathingCommandContext;
import baritone.utils.pathing.Favoring;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.calc.PathPlannerPool;

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
    private BlockPos failedPlanAheadStart;

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
        toDispatch.clear();
        calcFailedLastTick = false;
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            secretInternalSegmentCancel();
            baritone.getPathingControlManager().cancelEverything();
            return;
        }

        expectedSegmentStart = pathStart();
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
        // A failed speculative route from this endpoint will have the same inputs while we are
        // still following this segment. Retry from the real position when the segment finishes.
        if (current.getPath().getDest().equals(failedPlanAheadStart)) return;
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
        AbstractNodeCostSearch pathfinder = createPathfinder(start, goal,
                current == null ? null : current.getPath(), searchContext);
        if (!Objects.equals(pathfinder.getGoal(), goal)) {
            logDebug("Simplifying " + goal.getClass() + " to GoalXZ due to distance");
        }
        if (talkAboutIt) logDebug("Searching from " + start + " to " + goal);
        if (current == null) failedPlanAheadStart = null;
        calculationStart = start.immutable();
        inProgress = pathfinder;
        pendingCalculation = PathPlannerPool.submit(() -> pathfinder.calculate(primaryTimeout, failureTimeout));
    }

    /** Poll without blocking; installation and executor creation happen on the client thread. */
    private void acceptCalculatedPath() {
        if (pendingCalculation == null || !pendingCalculation.isDone()) return;
        PathCalculationResult result;
        try {
            result = pendingCalculation.getNow(null);
        } catch (RuntimeException failure) {
            logDirect("Path calculation failed: " + failure);
            result = new PathCalculationResult(PathCalculationResult.Type.EXCEPTION);
        }
        BlockPos start = calculationStart;
        pendingCalculation = null;
        calculationStart = null;
        inProgress = null;
        if (result == null || result.getType() == PathCalculationResult.Type.CANCELLATION) return;

        Optional<IPath> path = result.getPath();
        if (current == null) {
            if (path.isPresent()) {
                if (path.get().positions().contains(expectedSegmentStart)) {
                    current = new PathExecutor(this, path.get());
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
                    next = new PathExecutor(this, path.get());
                    queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_FINISHED);
                } else {
                    logDebug("Discarding next path whose start no longer matches this segment");
                }
            } else {
                failedPlanAheadStart = start;
                queuePathEvent(PathEvent.NEXT_CALC_FAILED);
            }
        }
    }

    /** Detach a cancelled future immediately, so it can never install a path for a newer owner. */
    private void cancelCalculation() {
        if (inProgress != null) inProgress.cancel();
        if (pendingCalculation != null) pendingCalculation.cancel(false);
        inProgress = null;
        pendingCalculation = null;
        calculationStart = null;
        failedPlanAheadStart = null;
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
