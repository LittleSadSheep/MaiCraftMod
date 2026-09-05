// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.calc.PathPlannerPool;
import org.maiwithu.maicraft.core.pathing.execute.TerrainBill;

/**
 * A read-only second opinion for a preserve-mode path failure.
 *
 * <p>The probe runs the embedded Baritone A* once more with break, placement, downward digging,
 * and water-bucket falling enabled in a private {@link CalculationContext}. It never installs or
 * executes the returned path. Its only output is Baritone's calculation result plus a terrain
 * bill evaluated against the exact frozen block snapshot used during search.</p>
 *
 * <p>{@link #submit} must be called from the Minecraft client thread because constructing a
 * thread-safe {@code BlockStateInterface} copies the loaded chunk view there. The expensive A*
 * itself runs on the bounded path worker pool with the ordinary primary/failure budgets.</p>
 */
public final class EmbeddedBaritoneTerrainProbe {

    private EmbeddedBaritoneTerrainProbe() {}

    /**
     * Start a probe using an explicit goal, feet cell, and frozen safety policy.
     *
     * @return a future whose {@link CompletableFuture#cancel(boolean)} also asks the underlying
     *         Baritone search to stop
     */
    public static ProbeFuture submit(
            IBaritone baritone,
            BlockPos start,
            NavGoal goal,
            EmbeddedBaritonePolicy.Snapshot frozenPolicy) {
        Objects.requireNonNull(baritone, "baritone");
        BetterBlockPos frozenStart = new BetterBlockPos(
                Objects.requireNonNull(start, "start").immutable());
        MaiCraftGoalAdapter frozenGoal = new MaiCraftGoalAdapter(
                Objects.requireNonNull(goal, "goal"));
        CalculationContext context = CalculationContext.forTerrainProbe(
                baritone,
                Objects.requireNonNull(frozenPolicy, "frozenPolicy"));

        // Match an ordinary first-segment calculation: no previous-path backtracking preference,
        // but retain upstream entity avoidance and all non-mutation movement costs.
        Favoring favoring = new Favoring(baritone.getPlayerContext(), null, context);
        AbstractNodeCostSearch search = new AStarPathFinder(
                frozenStart,
                frozenStart.getX(), frozenStart.getY(), frozenStart.getZ(),
                frozenGoal,
                favoring,
                context);
        long primaryTimeout = Baritone.settings().primaryTimeoutMS.value;
        long failureTimeout = Baritone.settings().failureTimeoutMS.value;
        ProbeFuture future = new ProbeFuture(search);

        PathPlannerPool.submit(() -> {
            if (future.isCancelled()) return null;
            PathCalculationResult calculation = search.calculate(primaryTimeout, failureTimeout);
            if (future.isCancelled()) return null;
            TerrainBill bill = calculation.getPath()
                    .map(path -> TerrainBill.planned(path, context.bsi))
                    .orElseGet(TerrainBill::new);
            return new Result(calculation, bill);
        }).whenComplete((result, failure) -> {
            if (failure != null) future.completeExceptionally(failure);
            else if (result != null) future.complete(result);
        });
        return future;
    }

    /** Immutable diagnostic result; the path remains evidence and is never handed to an executor. */
    public record Result(PathCalculationResult calculation, TerrainBill terrainBill) {
        public Result {
            Objects.requireNonNull(calculation, "calculation");
            Objects.requireNonNull(terrainBill, "terrainBill");
        }

        public Optional<IPath> path() {
            return calculation.getPath();
        }

        public boolean reachesGoal() {
            return calculation.getType() == PathCalculationResult.Type.SUCCESS_TO_GOAL;
        }
    }

    /** A normal future with cancellation wired through to Baritone's one-shot A* instance. */
    public static final class ProbeFuture extends CompletableFuture<Result> {
        private final AbstractNodeCostSearch search;

        private ProbeFuture(AbstractNodeCostSearch search) {
            this.search = search;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (isDone()) {
                return false;
            }
            search.cancel();
            return super.cancel(mayInterruptIfRunning);
        }
    }
}
