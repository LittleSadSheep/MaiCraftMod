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
        if (reached.getAsBoolean() || goal.isAt(feet())) return arrive();
        if (calculationFailed) {
            String qualifier = permit == TerrainPermit.PRESERVE
                    ? " under the no-terrain-alteration policy" : "";
            String advice = terrainProbeRequested && permit == TerrainPermit.PRESERVE
                    ? "; a terrain-changing route has not been executed or assumed"
                    : "";
            return fail(FailureType.NO_PATH,
                    "Baritone found no path to " + plannedCenter.toShortString()
                            + qualifier + advice);
        }

        driveRequested = true;
        return PlayerNav.Status.RUNNING;
    }

    private PlayerNav.Status arrive() {
        arrivedLatched = true;
        driveRequested = false;
        EmbeddedBaritoneRuntime.suspend(this);
        InputDriver.halt(player);
        return PlayerNav.Status.ARRIVED;
    }

    private PlayerNav.Status fail(FailureType type, String reason) {
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
        if (event == PathEvent.CALC_FAILED || event == PathEvent.NEXT_CALC_FAILED) {
            calculationFailed = true;
        }
    }

    void preempted(String reason) {
        failureType = FailureType.INTERRUPTED;
        failureReason = reason;
        terminalFailure = true;
        driveRequested = false;
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
        return EmbeddedBaritoneRuntime.hasConcretePath(this)
                ? ticksSincePhysicalProgress : 0;
    }

    public boolean hasRecentPhysicalProgress(int graceTicks) {
        return EmbeddedBaritoneRuntime.hasConcretePath(this)
                && ticksSincePhysicalProgress <= Math.max(0, graceTicks);
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
        driveRequested = false;
        EmbeddedBaritoneRuntime.release(this);
        InputDriver.halt(player);
    }

    public void pause() {
        driveRequested = false;
        EmbeddedBaritoneRuntime.suspend(this);
        InputDriver.halt(player);
    }

    public boolean yieldForExternalAction() {
        pause();
        if (!isSafeToCancel()) return false;
        var context = ClientRuntime.requireContext(player);
        return context.permitsNativeActions() && context.mutationAvailable();
    }
}
