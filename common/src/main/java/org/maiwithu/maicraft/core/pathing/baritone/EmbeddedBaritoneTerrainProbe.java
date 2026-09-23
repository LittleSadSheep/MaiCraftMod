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
 * 保持地形的导航失败后，另做一次允许在计算中考虑挖掘搭路的搜索，返回可能要改哪些格。它只生成建议，不创建执行路线。
 */
public final class EmbeddedBaritoneTerrainProbe {

    private EmbeddedBaritoneTerrainProbe() {}

    /**
     * 使用明确的目标、脚位格和冻结安全策略启动一次探测。
     *
     * @return 对此 future 调用 {@link CompletableFuture#cancel(boolean)} 时，也会请求底层 Baritone 搜索停止。
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

        // 与普通首段计算保持一致：不使用上一条路线的回溯偏好，但保留上游实体避让和所有不改变世界的移动成本。
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

        // 后台计算前后都检查是否取消；取消时也通知搜索器停止，不能只把等待结果的对象丢掉。
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

    /** 不可变诊断结果；路线仅作为证据，绝不会交给执行器运行。 */
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

    /** 普通 future，其取消操作会传递到底层一次性 Baritone A* 搜索。 */
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
