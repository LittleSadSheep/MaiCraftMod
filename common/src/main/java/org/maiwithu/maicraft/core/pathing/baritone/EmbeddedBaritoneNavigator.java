// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.event.events.PathEvent;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PathExecutor;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.execute.TerrainBill;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.entity.InputDriver;

/**
 * MaiCraft's stable {@link PlayerNav} contract backed by the embedded Baritone engine.
 *
 * <p>This object contains task-local meaning (goal supplier, arrival predicate, permissions,
 * progress and failure receipt). The shared runtime contains only the one physical pathing body.
 * No timeout or distance gate is added here: Baritone owns segment calculation and replanning;
 * the calling semantic task retains its existing progress lease.</p>
 */
public final class EmbeddedBaritoneNavigator {
    private static final int LIVE_GOAL_REPLAN_TICKS = 5;
    private static final double GOAL_MOVED_SQR = 4.0D;
    /** Baritone answers an unreachable goal with best-effort partial paths toward the
     * closest reachable point and replans forever — the body circles without converging
     * (CALC_FAILED never fires, and circling keeps the physical-progress lease alive).
     * No net progress toward the goal for this many executing ticks is reported as the
     * honest unreachable failure it is; self-developed pathing failed fast here. */
    private static final int UNREACHABLE_GRIND_TICKS = 900;
    /** Squared net improvement (3 blocks) that counts as real progress and resets the
     * grind clock; anything smaller is path jitter around the same frontier. */
    private static final double GOAL_PROGRESS_SQR = 9.0D;

    private final LocalPlayer player;
    private final Supplier<GoalCompiler.Compiled> compiledSupplier;
    private final BooleanSupplier reached;
    private final TerrainPermit permit;
    private final boolean sprintAllowed;
    private final boolean revalidateGoalEachTick;
    private final TerrainBill ledger = new TerrainBill();
    private final EnumMap<PathEvent, Integer> events = new EnumMap<>(PathEvent.class);

    private GoalCompiler.Compiled compiled;
    private NavGoal goal;
    private BlockPos plannedCenter;
    private BlockPos lastFeet;
    private int ticksSinceGoalRead;
    private int ticksSincePhysicalProgress;
    private int ticksWithoutGoalProgress;
    private double bestGoalDistanceSqr = Double.MAX_VALUE;
    private boolean started;
    private boolean driveRequested;
    private boolean arrivedLatched;
    private boolean calculationFailed;
    private boolean stopped;
    private boolean terminalFailure;
    private boolean terrainProbeRequested;
    private FailureType failureType = FailureType.NO_PATH;
    private String failureReason = "embedded pathing has not failed";

    public EmbeddedBaritoneNavigator(
            LocalPlayer player,
            Supplier<GoalCompiler.Compiled> compiledSupplier,
            BooleanSupplier reached,
            TerrainPermit permit,
            boolean sprintAllowed,
            boolean revalidateGoalEachTick) {
        this.player = player;
        this.compiledSupplier = compiledSupplier;
        this.reached = reached;
        this.permit = permit;
        this.sprintAllowed = sprintAllowed;
        this.revalidateGoalEachTick = revalidateGoalEachTick;
    }

    public static boolean enabled() {
        return true;
    }

    public EmbeddedBaritoneNavigator withTerrainProbe() {
        terrainProbeRequested = true;
        return this;
    }

    public PlayerNav.Status tick() {
        if (reached.getAsBoolean()) return arrive();
        if (terminalFailure) return PlayerNav.Status.FAILED;
        if (stopped) return fail(FailureType.TARGET_LOST, "navigation was stopped");
        if (player.isPassenger()) player.stopRiding();

        GoalCompiler.Compiled fresh = compiledSupplier.get();
        if (fresh == null) return fail(FailureType.TARGET_LOST, "target lost");
        NavGoal freshGoal = fresh.goal();
        BlockPos freshCenter = freshGoal.center();

        boolean moved = plannedCenter != null
                && freshCenter.distSqr(plannedCenter) > GOAL_MOVED_SQR;
        boolean periodic = revalidateGoalEachTick
                && ++ticksSinceGoalRead >= LIVE_GOAL_REPLAN_TICKS;
        if (!started || moved || periodic || (arrivedLatched && !freshGoal.isAt(feet()))) {
            // The grind clock follows the target, not the replan cadence: a periodic
            // replan of an unmoved goal must not reset unreachability accounting, or
            // live-goal navigations could circle forever.
            if (moved || plannedCenter == null) {
                ticksWithoutGoalProgress = 0;
                bestGoalDistanceSqr = Double.MAX_VALUE;
            }
            ticksSinceGoalRead = 0;
            compiled = fresh;
            goal = freshGoal;
            plannedCenter = freshCenter.immutable();
            arrivedLatched = false;
            calculationFailed = false;
            EmbeddedBaritoneRuntime.startOrUpdate(this, compiled, permit, sprintAllowed);
            started = true;
        } else {
            // Protected-area ThreadLocals are task-scoped and may change even for a fixed target.
            // Refresh the policy before a later segment calculation without forcing a replan.
            EmbeddedBaritoneRuntime.refreshPolicy(this, compiled);
        }

        updatePhysicalProgress();
        if (reached.getAsBoolean() || hasStableSearchMembership()) return arrive();
        if (calculationFailed) {
            return failNoPath("Baritone found no path to " + plannedCenter.toShortString());
        }
        if (EmbeddedBaritoneRuntime.hasConcretePath(this) && !updateGoalProgress()) {
            return failNoPath("no net progress toward " + plannedCenter.toShortString()
                    + " for 45s of pathing; the target appears unreachable");
        }

        driveRequested = true;
        return PlayerNav.Status.RUNNING;
    }

    /** Runtime continuation for callers that released their last PlayerNav reference mid-air. */
    boolean requiresOrphanContinuation() {
        return pendingFailureType != null || pendingPause;
    }

    /** Finish a latched failure once the movement itself declares hand-off safe. */
    void settlePendingFailureAtSafeBoundary() {
        if (pendingFailureType != null && isSafeToCancel()) {
            finishPendingFailureWhenSafe();
        } else if (pendingPause && isSafeToCancel()) {
            completePause();
        }
    }

    private PlayerNav.Status arrive() {
        cancelTerrainProbe();
        pendingPause = false;
        arrivedLatched = true;
        driveRequested = false;
        EmbeddedBaritoneRuntime.suspend(this);
        InputDriver.halt(player);
        return PlayerNav.Status.ARRIVED;
    }

    private PlayerNav.Status fail(FailureType type, String reason) {
        cancelTerrainProbe();
        failureType = type;
        failureReason = reason;
        terminalFailure = true;
        driveRequested = false;
        EmbeddedBaritoneRuntime.release(this);
        InputDriver.halt(player);
        return PlayerNav.Status.FAILED;
    }

    private void updatePhysicalProgress() {
        BlockPos now = feet();
        if (lastFeet == null || !lastFeet.equals(now)) {
            lastFeet = now.immutable();
            ticksSincePhysicalProgress = 0;
        } else if (EmbeddedBaritoneRuntime.hasConcretePath(this)) {
            ticksSincePhysicalProgress++;
        }
    }

    private BlockPos feet() {
        return PathExecutor.playerFeet(player);
    }

    void onPathEvent(PathEvent event) {
        events.merge(event, 1, Integer::sum);
        // A plan-ahead miss does not invalidate the segment currently carrying the body.
        // Upstream will retry from its real end; treating it as terminal made healthy walks stop
        // halfway whenever a speculative next segment encountered unloaded or changing terrain.
        if (event == PathEvent.CALC_FAILED) {
            calculationFailed = true;
        }
    }

    /**
     * Preserve PlayerNav's historical "search satisfied" handoff without accepting a transient
     * mid-jump/mid-fall node. The caller's stronger predicate still wins whenever it is true.
     */
    private boolean hasStableSearchMembership() {
        BlockPos now = feet();
        return player.onGround()
                && goal.isAt(now)
                && goal.isAt(EmbeddedBaritoneRuntime.pathStart(this, now));
    }

    void preemptWhenSafe(String reason) {
        failWhenSafe(FailureType.INTERRUPTED, reason);
    }

    void preempted(String reason) {
        abort(FailureType.INTERRUPTED, reason);
    }

    void internalFailure(String reason) {
        abort(FailureType.INTERNAL, reason);
    }

    private void abort(FailureType fallbackType, String fallbackReason) {
        if (terminalFailure) return;
        cancelTerrainProbe();
        // A hard body/world/runtime boundary cannot be drained, but it still must not erase a
        // more specific result that was already waiting for an airborne movement to land.
        failureType = pendingFailureType == null ? fallbackType : pendingFailureType;
        failureReason = pendingFailureType == null ? fallbackReason : pendingFailureReason;
        pendingFailureType = null;
        pendingFailureReason = null;
        pendingArrival = false;
        pendingPause = false;
        terminalFailure = true;
        driveRequested = false;
    }

    boolean canAcquireRuntimeOwnership() {
        return !terminalFailure && !stopped && pendingFailureType == null;
    }

    boolean consumeDriveRequest() {
        boolean requested = driveRequested;
        driveRequested = false;
        return requested;
    }

    public boolean isSafeToCancel() {
        return EmbeddedBaritoneRuntime.isSafeToCancel(this);
    }

    public BlockPos pathStart() {
        return EmbeddedBaritoneRuntime.pathStart(this, feet());
    }

    public TerrainBill ledger() {
        return ledger;
    }

    public String failReason() {
        return failureReason;
    }

    public FailureType failType() {
        return failureType;
    }

    public int stallTicks() {
        if (EmbeddedBaritoneRuntime.hasActiveAction(this)) return 0;
        if (!EmbeddedBaritoneRuntime.hasConcretePath(this)) return 0;
        return Math.min(ticksSincePhysicalProgress, nativeActionProgressAge());
    }

    public boolean hasRecentPhysicalProgress(int graceTicks) {
        int grace = Math.max(0, graceTicks);
        return EmbeddedBaritoneRuntime.hasActiveAction(this)
                || (EmbeddedBaritoneRuntime.hasConcretePath(this)
                        && ticksSincePhysicalProgress <= grace)
                || nativeActionProgressAge() <= grace;
    }

    private int nativeActionProgressAge() {
        if (lastNativeActionProgressGameTime == Long.MIN_VALUE) return Integer.MAX_VALUE;
        long age = player.level().getGameTime() - lastNativeActionProgressGameTime;
        if (age < 0L || age > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) age;
    }

    public String outcomeSummary() {
        if (events.isEmpty()) return "baritone_events={}";
        StringBuilder out = new StringBuilder("baritone_events={");
        boolean first = true;
        for (Map.Entry<PathEvent, Integer> entry : events.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append(entry.getKey().name().toLowerCase()).append(':').append(entry.getValue());
        }
        return out.append('}').toString();
    }

    public boolean planningInFlight() {
        return EmbeddedBaritoneRuntime.planningInFlight(this);
    }

    public void stop() {
        if (stopped) return;
        stopped = true;
        if (terminalFailure) return;
        failWhenSafe(FailureType.TARGET_LOST, "navigation was stopped");
    }

    public void pause() {
        driveRequested = false;
        if (!isSafeToCancel()) {
            pendingPause = true;
            driveRequested = true;
            return;
        }
        completePause();
    }

    private void completePause() {
        // Another first-person winner may change the world while this task is suspended. A
        // diagnostic A* is tied to the failed route's frozen chunk view, so discard it and take a
        // fresh second opinion after resumption instead of reporting stale terrain evidence.
        cancelTerrainProbe();
        pendingPause = false;
        driveRequested = false;
        EmbeddedBaritoneRuntime.suspend(this);
        InputDriver.halt(player);
    }

    public boolean yieldForExternalAction() {
        if (!isSafeToCancel()) return false;
        pause();
        var context = ClientRuntime.requireContext(player);
        return context.permitsNativeActions() && context.mutationAvailable();
    }
}
