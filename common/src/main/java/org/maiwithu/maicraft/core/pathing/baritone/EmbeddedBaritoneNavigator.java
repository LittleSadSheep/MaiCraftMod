// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import baritone.api.event.events.PathEvent;
import baritone.api.utils.PathCalculationResult;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.execute.TerrainBill;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.entity.InputDriver;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.maiwithu.maicraft.core.pathing.execute.NavigationStep;
import org.maiwithu.maicraft.core.pathing.moves.movements.BuildPlacementRegistry;

/**
 * 保存一趟导航的任务含义：目标会不会变、到达与失败状态、地形许可、已经做过的改动和最近进展。
 * 具体寻路交给共享运行器；暂停、失败或到达时若还在跳跃／下落，要先等能安全交接再停止。
 */
public final class EmbeddedBaritoneNavigator {
    private final LocalPlayer player;
    private final ClientLevel playerWorld;
    private final Supplier<GoalCompiler.Compiled> compiledSupplier;
    private final BooleanSupplier reached;
    private final PlayerNav.ContextProvider contextProvider;
    private final TerrainPermit permit;
    private final boolean sprintAllowed;
    private final TerrainBill ledger = new TerrainBill();
    private final EnumMap<PathEvent, Integer> events = new EnumMap<>(PathEvent.class);
    private final LongOpenHashSet rejectedScaffolds = new LongOpenHashSet();
    private final LongOpenHashSet rejectedClearance = new LongOpenHashSet();

    private GoalCompiler.Compiled compiled;
    private GoalCompiler.CompiledFingerprint compiledFingerprint;
    private NavGoal goal;
    private BlockPos plannedCenter;
    private final NavigationProgress progress = new NavigationProgress();
    private boolean started;
    private boolean driveRequested;
    private boolean arrivedLatched;
    private boolean calculationFailed;
    private boolean stopped;
    private boolean terminalFailure;
    private boolean terrainProbeRequested;
    private boolean pendingArrival;
    private boolean pendingPause;
    private boolean rescueDetached;
    private FailureType pendingFailureType;
    private String pendingFailureReason;
    private EmbeddedBaritoneTerrainProbe.ProbeFuture terrainProbe;
    private GoalCompiler.CompiledFingerprint terrainProbeFingerprint;
    private EmbeddedBaritonePolicy.Snapshot terrainProbePolicy;
    private FailureType failureType = FailureType.NO_PATH;
    private String failureReason = "embedded pathing has not failed";

    public EmbeddedBaritoneNavigator(
            LocalPlayer player,
            Supplier<GoalCompiler.Compiled> compiledSupplier,
            BooleanSupplier reached,
            PlayerNav.ContextProvider contextProvider,
            boolean sprintAllowed) {
        this.player = player;
        this.playerWorld = player == null ? null : player.clientLevel;
        this.compiledSupplier = compiledSupplier;
        this.reached = reached;
        this.contextProvider = contextProvider;
        this.permit = contextProvider.permit();
        this.sprintAllowed = sprintAllowed;
    }

    LongSet protectedMutationCells() {
        var cells = new LongOpenHashSet(contextProvider.embeddedProtectedMutationCells());
        cells.addAll(rejectedScaffolds);
        cells.addAll(rejectedClearance);
        return cells;
    }

    LongSet forbiddenBodyCells() {
        return contextProvider.embeddedForbiddenBodyCells();
    }

    int minimumFeetY() { return contextProvider.minimumFeetY(); }

    TerrainPermit permit() {
        return permit;
    }

    /** 重生即使沿用UUID也已换身体；旧路线的落地等待与输入不能交给新角色继续执行。 */
    boolean belongsTo(LocalPlayer current) {
        return player == current && (current == null || playerWorld == current.clientLevel);
    }

    private boolean retireReplacedBody() {
        if (belongsTo(Minecraft.getInstance().player)) return false;
        preempted("the navigation's local-player body or world was replaced");
        EmbeddedBaritoneRuntime.abandon(this);
        return true;
    }

    HitResult objectMouseOver() {
        return EmbeddedBaritoneRuntime.objectMouseOver(this);
    }

    void recordConfirmedBreak(BlockPos pos, BlockState before) {
        ledger.addBreak(pos, before);
        if (contextProvider instanceof BuildPlacementRegistry.Provider provider)
            BuildPlacementRegistry.recordConfirmedScaffoldRemoval(provider, pos);
    }

    void recordConfirmedPlace(BlockPos pos, BlockState placed) {
        ledger.addPlace(pos, placed.getBlock());
        if (contextProvider instanceof BuildPlacementRegistry.Provider provider)
            BuildPlacementRegistry.recordConfirmedScaffold(provider, pos, placed);
    }

    boolean permitsScaffoldSupport(BlockPos clicked, BlockPos placeAt, BlockState state) {
        return contextProvider instanceof BuildPlacementRegistry.Provider provider
                && provider.permitsScaffoldSupport(clicked, placeAt, state);
    }

    boolean permitsTemporaryScaffold(BlockPos placeAt) {
        return !rejectedScaffolds.contains(placeAt.asLong())
                && (!(contextProvider instanceof BuildPlacementRegistry.Provider provider)
                || provider.permitsTemporaryScaffold(placeAt));
    }

    void rejectedTemporaryScaffold(BlockPos pos) {
        if (rejectedScaffolds.size() < 64) rejectedScaffolds.add(pos.asLong());
        else if (!rejectedScaffolds.contains(pos.asLong()))
            failWhenSafe(FailureType.NO_PATH, "temporary scaffold placement exclusions exhausted the bounded navigation alternatives");
    }

    // 原生准星发现不可清除的遮挡后更新保护策略；下一刻 refreshPolicy 会重新规划可绕过它的路线。
    void rejectedClearance(BlockPos pos) {
        if (rejectedClearance.size() < 64) rejectedClearance.add(pos.asLong());
        else if (!rejectedClearance.contains(pos.asLong()))
            failWhenSafe(FailureType.NO_PATH, "clearance whitelist exclusions exhausted navigation alternatives");
    }

    void recordConfirmedNativeAction() {
        progress.confirm(player.level().getGameTime());
    }

    public EmbeddedBaritoneNavigator withTerrainProbe() {
        terrainProbeRequested = true;
        return this;
    }

    public PlayerNav.Status tick() {
        rescueDetached = false;
        if (terminalFailure) return PlayerNav.Status.FAILED;
        if (retireReplacedBody()) return PlayerNav.Status.FAILED;
        if (pendingFailureType != null) return finishPendingFailureWhenSafe();
        if (stopped) {
            return failWhenSafe(FailureType.TARGET_LOST, "navigation was stopped");
        }
        if (pendingPause) {
            if (!isSafeToCancel()) return continueToSafeBoundary();
            completePause();
        }

        if (pendingArrival) {
            if (!isSafeToCancel()) return continueToSafeBoundary();
            pendingArrival = false;
            if (reached.getAsBoolean()) return arrive();
            // 当前移动到达时，更严格的谓词目标已发生变化；继续执行下方逻辑以重新编译目标，再检查实时供应器提供的图成员关系。
        }

        if (reached.getAsBoolean()) return arriveWhenSafe();
        if (player.isPassenger()) player.stopRiding();

        // 每次重新取目标要求；位置、半径或保护条件变化时，需要更新路线，不能只比较对象是不是同一个。
        GoalCompiler.Compiled fresh = compiledSupplier.get();
        if (fresh == null) return failWhenSafe(FailureType.TARGET_LOST, "target lost");
        NavGoal freshGoal = fresh.goal();
        BlockPos freshCenter = freshGoal.center();
        GoalCompiler.CompiledFingerprint freshFingerprint = fresh.semanticFingerprint();

        boolean semanticsChanged = compiledFingerprint != null
                && !compiledFingerprint.equals(freshFingerprint);
        if (!started || semanticsChanged
                || (arrivedLatched && !freshGoal.isAt(feet()))) {
            if (semanticsChanged) cancelTerrainProbe();
            compiled = fresh;
            compiledFingerprint = freshFingerprint;
            goal = freshGoal;
            plannedCenter = freshCenter.immutable();
            arrivedLatched = false;
            calculationFailed = false;
            EmbeddedBaritoneRuntime.startOrUpdate(this, compiled, permit, sprintAllowed);
            started = true;
        } else {
            // 保护区域 ThreadLocal 属于任务作用域，即使目标固定也可能变化；在之后计算路线段前刷新策略，但不强制立即重规划。
            if (EmbeddedBaritoneRuntime.refreshPolicy(this, compiled)) {
                cancelTerrainProbe();
                calculationFailed = false;
            }
        }

        updatePhysicalProgress();
        if (reached.getAsBoolean() || hasStableSearchMembership()) return arriveWhenSafe();
        if (calculationFailed
                && !EmbeddedBaritoneRuntime.hasConcretePath(this)
                && !EmbeddedBaritoneRuntime.planningInFlight(this)) {
            return diagnoseNoPath();
        }
        driveRequested = true;
        return PlayerNav.Status.RUNNING;
    }

    // 保持地形时找不到路，可额外只计算“假如允许改地形会怎样”；这份计算不会真的挖掘或放置。
    private PlayerNav.Status diagnoseNoPath() {
        String detail = "Baritone found no path to " + plannedCenter.toShortString();
        if (!rejectedScaffolds.isEmpty()) detail += "; temporary scaffold safety excluded " + rejectedScaffolds.size() + " placement cells";
        if (!terrainProbeRequested || permit != TerrainPermit.PRESERVE) {
            return failWhenSafe(FailureType.NO_PATH, detail);
        }
        if (terrainProbe == null) {
            terrainProbeFingerprint = compiledFingerprint;
            terrainProbePolicy = EmbeddedBaritoneRuntime.policySnapshot(this);
            terrainProbe = terrainProbePolicy == null ? null
                    : EmbeddedBaritoneRuntime.submitTerrainProbe(this, feet(), goal);
            if (terrainProbe == null) {
                clearTerrainProbeState();
                return failWhenSafe(FailureType.NO_PATH,
                        detail + "; the read-only terrain probe could not acquire the current "
                                + "navigation evidence, and no terrain change was executed");
            }
        }
        if (!terrainProbe.isDone()) {
            driveRequested = false;
            return PlayerNav.Status.RUNNING;
        }

        EmbeddedBaritoneTerrainProbe.Result result;
        try {
            result = terrainProbe.join();
        } catch (CancellationException cancelled) {
            clearTerrainProbeState();
            return failWhenSafe(FailureType.INTERRUPTED,
                    "the read-only terrain probe was cancelled before it produced evidence; "
                            + "no terrain change was executed");
        } catch (CompletionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            clearTerrainProbeState();
            return failWhenSafe(FailureType.INTERNAL,
                    "the read-only terrain probe failed internally: "
                            + cause.getClass().getSimpleName()
                            + "; no terrain change was executed");
        }

        GoalCompiler.CompiledFingerprint probedFingerprint = terrainProbeFingerprint;
        EmbeddedBaritonePolicy.Snapshot probedPolicy = terrainProbePolicy;
        clearTerrainProbeState();
        if (!compiledFingerprint.equals(probedFingerprint)
                || !EmbeddedBaritonePolicy.snapshot().equals(probedPolicy)) {
            calculationFailed = false;
            EmbeddedBaritoneRuntime.startOrUpdate(this, compiled, permit, sprintAllowed);
            return PlayerNav.Status.RUNNING;
        }
        if (reached.getAsBoolean()) return arriveWhenSafe();

        PathCalculationResult.Type type = result.calculation().getType();
        TerrainBill bill = result.terrainBill();
        if (type == PathCalculationResult.Type.EXCEPTION) {
            return failWhenSafe(FailureType.INTERNAL,
                    "the read-only terrain probe ended with an internal search error; no terrain "
                            + "change was executed");
        }
        if (type == PathCalculationResult.Type.CANCELLATION) {
            return failWhenSafe(FailureType.INTERRUPTED,
                    "the read-only terrain probe was cancelled before a conclusion; no terrain "
                            + "change was executed");
        }
        if (type != PathCalculationResult.Type.SUCCESS_TO_GOAL) {
            String partial = bill.isEmpty() ? "" : "; its incomplete segment would "
                    + bill.describe();
            return failWhenSafe(FailureType.NO_PATH,
                    detail + " even when a read-only probe was allowed to consider digging, "
                            + "bridging, pillaring and water placement" + partial
                            + "; the probe did not establish a complete route and executed nothing");
        }
        // 第二次计算找到无需改动的路时，当前仍返回本次失败并建议重试，没有把第二次路线直接接着执行。
        if (bill.isEmpty()) {
            return failWhenSafe(FailureType.NO_PATH,
                    "the preserve calculation failed, but a read-only second calculation reached "
                            + "the goal without needing terrain changes; treat the original result "
                            + "as transient and retry the same intent; nothing was executed");
        }
        return failWhenSafe(FailureType.TERRAIN_BLOCKED,
                "no complete route was found without altering terrain; a read-only full-route "
                        + "probe from " + feet().toShortString() + " toward "
                        + plannedCenter.toShortString() + " would " + bill.describe()
                        + ". This is a proposed terrain budget only: nothing was executed. "
                        + "Retry with explicit terrain permission, choose another destination, "
                        + "or ask the audience/player.");
    }

    private void cancelTerrainProbe() {
        if (terrainProbe != null) terrainProbe.cancel(true);
        clearTerrainProbeState();
    }

    private void clearTerrainProbeState() {
        terrainProbe = null;
        terrainProbeFingerprint = null;
        terrainProbePolicy = null;
    }

    /** 谓词完成时，若角色仍在坠落或刚启动跑酷，不得清除转向输入。 */
    private PlayerNav.Status arriveWhenSafe() {
        if (!isSafeToCancel()) {
            pendingArrival = true;
            return continueToSafeBoundary();
        }
        return arrive();
    }

    // 先记住失败原因；动作尚不适合停下时继续完成必要的落地收尾，期间不把原因覆盖成别的错误。
    private PlayerNav.Status failWhenSafe(FailureType type, String reason) {
        pendingArrival = false;
        pendingPause = false;
        // 保留最先确认的终止原因。后续清理或停止请求不能覆盖仍在结算的 TARGET_LOST、CALC_FAILED 或策略证据。
        if (pendingFailureType == null) {
            pendingFailureType = type;
            pendingFailureReason = reason;
        }
        return finishPendingFailureWhenSafe();
    }

    private PlayerNav.Status finishPendingFailureWhenSafe() {
        if (!isSafeToCancel()) return continueToSafeBoundary();
        FailureType type = pendingFailureType;
        String reason = pendingFailureReason;
        pendingFailureType = null;
        pendingFailureReason = null;
        return fail(type, reason);
    }

    private PlayerNav.Status continueToSafeBoundary() {
        driveRequested = true;
        return PlayerNav.Status.RUNNING;
    }

    /** 当调用方在空中释放最后一个 PlayerNav 引用时，由运行时继续推进。 */
    boolean requiresOrphanContinuation() {
        return !rescueDetached && (pendingFailureType != null || pendingPause);
    }

    /** 保留任务语义，丢弃未命中的路线，并防止帧末遗留无人负责的转向输入。 */
    void detachForLandingRescue() {
        cancelTerrainProbe();
        started = false;
        driveRequested = false;
        pendingPause = false;
        pendingArrival = false;
        rescueDetached = true;
    }

    /** 移动本身确认可安全交接后，完成已锁存的失败状态。 */
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
        progress.observe(player.getX(), player.getY(), player.getZ(), player.level().getGameTime());
    }

    private BlockPos feet() {
        return PlayerNav.playerFeet(player);
    }

    void onPathEvent(PathEvent event) {
        events.merge(event, 1, Integer::sum);
        // 前瞻路线失败不代表当前正在执行的路线段失效；上游会从真实终点重试。
        // 若把前瞻失败当成终态，下一段遇到未加载或变化地形时，正常行走也会半途停止。
        if (event == PathEvent.CALC_FAILED) {
            calculationFailed = true;
        }
    }

    /**
     * 当前还允许一种到达方式：玩家已站稳，实际脚位和搜索起点都在目标格范围内。
     * 因此即使任务提供的 reached 回调尚未通过，也可能返回 ARRIVED；任务仍需检查距离、视线等自己的完成条件。
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
        // 身体、世界或运行时的硬边界无法等待结算，但仍不能覆盖已经等待空中移动着陆的具体结果。
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
        return progress.stalledTicks(player.level().getGameTime());
    }

    public boolean hasRecentPhysicalProgress(int graceTicks) {
        return progress.recent(player.level().getGameTime(), graceTicks);
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

    public NavigationStep executionStep(long clientRevision) {
        return EmbeddedBaritoneRuntime.executionStep(this, clientRevision);
    }

    public void stop() {
        if (stopped) return;
        stopped = true;
        if (terminalFailure) return;
        if (retireReplacedBody()) return;
        failWhenSafe(FailureType.TARGET_LOST, "navigation was stopped");
    }

    /** 玩家手动接管时丢弃本地规划，但不提交原生世界动作。 */
    public void abandon() {
        cancelTerrainProbe();
        stopped = true; terminalFailure = true; driveRequested = false;
        pendingPause = false; pendingFailureType = null;
        EmbeddedBaritoneRuntime.abandon(this);
    }

    // 暂停并不总是立即松开全部控制；空中需要保持动作才能安全落地时，先记下暂停请求。
    public void pause() {
        if (terminalFailure || retireReplacedBody()) return;
        driveRequested = false;
        if (!isSafeToCancel()) {
            pendingPause = true;
            driveRequested = true;
            return;
        }
        completePause();
    }

    private void completePause() {
        // 此任务挂起期间，另一个第一人称行为可能改变世界。诊断 A* 绑定失败路线的冻结区块视图，
        // 因此恢复时应丢弃旧结果并重新检查，不能报告过时地形证据。
        cancelTerrainProbe();
        pendingPause = false;
        driveRequested = false;
        EmbeddedBaritoneRuntime.suspend(this);
        InputDriver.halt(player);
    }

    public boolean yieldForExternalAction() {
        if (retireReplacedBody()) return false;
        // 调用方会持续轮询，直到可以挖掘或使用物品。先登记待处理暂停，使不安全的移动继续获得 tick，直到能够安全让出控制。
        pause();
        if (!isSafeToCancel()) return false;
        var context = ClientRuntime.requireContext(player);
        return context.permitsNativeActions() && context.mutationAvailable();
    }
}
