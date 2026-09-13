package org.maiwithu.maicraft.core.pathing.transport;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneNavigator;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.execute.TerrainBill;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;

/**
 * 决定这一段用走路、背包飞行还是电梯；普通走路交给 Baritone，交通完成后再检查原来的目的地。
 * 旅行和走近工作台都用它，所以到达条件仍由调用方提供，不能把“下了电梯”一概当作完成。
 */
public final class TransportNavigator {
    private final LocalPlayer player;
    private final Supplier<GoalCompiler.Compiled> goals;
    private final BooleanSupplier reached;
    private final PlayerNav.ContextProvider policy;
    private final boolean sprint;
    private final TerrainBill journey = new TerrainBill();
    private EmbeddedBaritoneNavigator ground;
    private TransportMode mode = TransportMode.AUTO;
    private TransportTargets targets;
    private LongSet forbidden = LongSets.emptySet();
    private List<TransportPlan.Offer> offers = List.of();
    private List<String> unavailable = List.of();
    private final List<Map<String, Object>> attempts = new ArrayList<>();
    private int offerIndex;
    private TransportSession session;
    private TransportSession.Result transportResult;
    private boolean attempted, probeRequested, stopped, paused, offersPrepared, forceConsumed;
    private final Set<String> triedOffers = new HashSet<>();
    private Vec3 legOrigin;
    private BlockPos activeDestination;
    private GoalCompiler.CompiledFingerprint targetFingerprint;
    private boolean replanning;
    private String failure;
    private FailureType failureType = FailureType.NO_PATH;
    private long progressTick = Long.MIN_VALUE;
    private Vec3 lastPosition;

    public TransportNavigator(LocalPlayer player, Supplier<GoalCompiler.Compiled> goals,
                              BooleanSupplier reached, PlayerNav.ContextProvider policy, boolean sprint) {
        this.player = player; this.goals = goals; this.reached = reached; this.policy = policy; this.sprint = sprint;
        this.ground = newGround();
    }

    private EmbeddedBaritoneNavigator newGround() { return new EmbeddedBaritoneNavigator(player, goals, reached, policy, sprint); }

    public void mode(TransportMode mode) { this.mode = mode; if (mode == TransportMode.GROUND && probeRequested) ground.withTerrainProbe(); }
    public void withTerrainProbe() { probeRequested = true; if (mode == TransportMode.GROUND) ground.withTerrainProbe(); }

    public PlayerNav.Status tick() {
        // 目标或禁止进入的区域变了，先让正在进行的交通安全停下，再按新要求规划，不能直接换终点。
        if (stopped) return PlayerNav.Status.FAILED;
        var context = ClientRuntime.requireContext(player);
        var currentGoal = goals.get();
        LongSet currentForbidden = policy.embeddedForbiddenBodyCells();
        if (session != null && !compatibleGoal(currentGoal, activeDestination, targetFingerprint, forbidden, currentForbidden)) {
            replanning = true;
            TransportRuntime.cancel(this); // finish the old leg's safe exit before adopting new intent
        }
        if (lastPosition == null || player.position().distanceToSqr(lastPosition) > 0.01) {
            lastPosition = player.position(); progressTick = player.level().getGameTime();
        }
        if (session != null) {
            // 旧交通还没结束时先等它，不同时开另一种移动方式争抢玩家按键。
            if (TransportRuntime.owns(this)) TransportRuntime.drive(this, context);
            if (transportResult == null) return PlayerNav.Status.RUNNING;
            var result = transportResult;
            attempts.add(Map.of("mode", offers.get(offerIndex - 1).mode(), "success", result.state() == TransportSession.State.SUCCEEDED,
                    "code", result.code(), "detail", result.detail(), "uncertain", result.uncertain()));
            session = null; transportResult = null;
            if (result.uncertain() && !(paused && "transport_control_transferred".equals(result.code()))) {
                failure = result.detail(); failureType = FailureType.UNKNOWN;
                return PlayerNav.Status.FAILED;
            }
            if (needsInspectionAfterFailure(result) && !paused && !replanning) {
                failure = result.code() + ": " + result.detail() + "; transport changed the body or equipment, inspect before retrying";
                failureType = FailureType.UNKNOWN;
                return PlayerNav.Status.FAILED;
            }
            if (replanning) {
                replanning = false; paused = true;
            }
            if (result.state() == TransportSession.State.SUCCEEDED) {
                // 乘梯或飞行这一段结束，不代表已满足最终站位。下一 tick 交回地面导航继续检查和收尾。
                if (!reached.getAsBoolean() && player.position().distanceToSqr(legOrigin) < 0.0625) {
                    failure = "transport ended without moving toward the requested destination";
                    return PlayerNav.Status.FAILED;
                }
                ground = newGround(); progressTick = player.level().getGameTime();
                offers = List.of(); targets = null; attempted = false;
                return PlayerNav.Status.RUNNING; // hand native receipts/input back on the next actor tick
            }
            failure = result.detail();
        }
        if (paused) {
            // 暂停后重新开始时，清掉旧候选和失败状态，让导航按当前身体位置再选路线。
            paused = false; failure = null; attempted = false; forceConsumed = false; triedOffers.clear();
            ground = newGround(); targets = null; offers = List.of();
        }
        if (reached.getAsBoolean()) {
            targets = null;
            return ground.tick(); // preserve its airborne boundary and native owner release
        }
        if (failureType == FailureType.UNKNOWN && failure != null) return PlayerNav.Status.FAILED;
        if (targets != null) {
            // 查找交通落点分多刻完成；目标改变时重查，查完才生成可尝试的飞行／电梯方案。
            if (currentGoal == null) { failure = "navigation destination disappeared"; return PlayerNav.Status.FAILED; }
            if (!targetFingerprint.equals(currentGoal.semanticFingerprint()) || !forbidden.equals(currentForbidden)) {
                forbidden = LongSets.unmodifiable(new LongOpenHashSet(currentForbidden));
                targets = new TransportTargets(currentGoal, context, forbidden);
                targetFingerprint = currentGoal.semanticFingerprint(); offersPrepared = false;
            }
            if (!offersPrepared) {
                targets.tick(context);
                if (!targets.complete()) return PlayerNav.Status.RUNNING;
                var compiled = goals.get();
                if (compiled == null) { failure = "navigation destination disappeared"; return PlayerNav.Status.FAILED; }
                var options = TransportPlan.prepare(context, compiled.goal(), targets, mode, forbidden);
                offers = options.offers(); unavailable = options.unavailable(); offerIndex = 0;
                offersPrepared = true;
            }
            while (offerIndex < offers.size() && triedOffers.contains(offerKey(offers.get(offerIndex)))) offerIndex++;
            if (offerIndex < offers.size()) {
                // 一次只试一个方案，并记住已尝试的出发点与终点，避免在同一处来回重复失败。
                if (TransportRuntime.occupied()) return PlayerNav.Status.RUNNING;
                var offer = offers.get(offerIndex);
                var candidate = offer.create().get();
                if (!TransportRuntime.acquire(this, offer.mode(), candidate, context, result -> transportResult = result)) return PlayerNav.Status.RUNNING;
                triedOffers.add(offerKey(offer)); legOrigin = player.position();
                activeDestination = offer.destination();
                offerIndex++; session = candidate;
                TransportRuntime.drive(this, context);
                return PlayerNav.Status.RUNNING;
            }
            targets = null;
            if (mode != TransportMode.AUTO || !probeRequested) {
                failure = exhaustedReason(attempts, unavailable);
                return PlayerNav.Status.FAILED;
            }
            ground = newGround(); ground.withTerrainProbe();
        }
        // 指定飞行或电梯就先用它；auto 先试走路，遇到无路、地形阻挡或缺搭路材料等情况再找交通。
        if (!forceConsumed && mode != TransportMode.AUTO && mode != TransportMode.GROUND) {
            forceConsumed = true; return beginTransport(context);
        }
        var status = ground.tick();
        if (status == PlayerNav.Status.FAILED && !attempted && mode != TransportMode.GROUND
                && (ground.failType() == FailureType.NO_PATH || ground.failType() == FailureType.TERRAIN_BLOCKED
                    || ground.failType() == FailureType.BOXED_IN || ground.failType() == FailureType.NO_MATERIAL)) {
            return beginTransport(context);
        }
        if (status == PlayerNav.Status.FAILED) { failure = ground.failReason(); failureType = ground.failType(); }
        return status;
    }

    private PlayerNav.Status beginTransport(org.maiwithu.maicraft.client.actor.LocalPlayerContext context) {
        // 停止旧步行并保存已改地形记录，重新开始找符合总目标且不侵入保护区的交通落点。
        var compiled = goals.get();
        if (compiled == null) { failure = "navigation destination is unavailable"; return PlayerNav.Status.FAILED; }
        journey.addAll(ground.ledger()); ground.stop(); ground = newGround();
        attempted = true; failure = null; failureType = FailureType.NO_PATH;
        forbidden = LongSets.unmodifiable(new LongOpenHashSet(policy.embeddedForbiddenBodyCells()));
        targets = new TransportTargets(compiled, context, forbidden);
        targetFingerprint = compiled.semanticFingerprint();
        offersPrepared = false;
        return PlayerNav.Status.RUNNING;
    }

    static boolean compatibleGoal(GoalCompiler.Compiled current, BlockPos destination,
                                  GoalCompiler.CompiledFingerprint original,
                                   LongSet previousForbidden, LongSet currentForbidden) {
        // 原目标没变，或新目标仍接受这次交通终点，才可继续这一段；禁止进入的格子变化也要重新规划。
        // Elevator hints name an intermediate floor, including an occupied workstation cell.
        // The original goal remains valid while the final ground approach is still pending.
        return current != null && destination != null
                && (current.semanticFingerprint().equals(original) || current.goal().isAt(destination))
                && previousForbidden.equals(currentForbidden);
    }

    static String exhaustedReason(List<Map<String, Object>> attempts, List<String> unavailable) {
        List<String> reasons = new ArrayList<>();
        for (var attempt : attempts) {
            if (!Boolean.TRUE.equals(attempt.get("success"))) reasons.add(attempt.get("mode") + " ["
                    + attempt.get("code") + "]: " + attempt.get("detail"));
        }
        reasons.addAll(unavailable);
        return "no available native transport reached the goal"
                + (reasons.isEmpty() ? "; no transport endpoint was verified" : "; " + String.join("; ", reasons));
    }

    static boolean needsInspectionAfterFailure(TransportSession.Result result) {
        return result.state() == TransportSession.State.FAILED && result.effectsStarted();
    }

    private String offerKey(TransportPlan.Offer offer) {
        return offer.mode() + ":" + PlayerNav.playerFeet(player).asLong() + ":" + offer.destination().asLong();
    }

    public boolean isSafeToCancel() {
        return session != null && TransportRuntime.owns(this)
                ? TransportRuntime.canSafelySuspendActive() : ground.isSafeToCancel();
    }
    public BlockPos pathStart() { return session != null ? PlayerNav.playerFeet(player) : ground.pathStart(); }
    public TerrainBill ledger() { var result = new TerrainBill(); result.addAll(journey); result.addAll(ground.ledger()); return result; }
    public String failReason() { return failure == null ? ground.failReason() : failure; }
    public FailureType failType() { return failure == null ? ground.failType() : failureType; }
    public int stallTicks() { return progressTick == Long.MIN_VALUE ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.max(0, player.level().getGameTime() - progressTick)); }
    public boolean hasRecentPhysicalProgress(int ticks) {
        return targets != null || session != null && session.livenessActive()
                || player.level().getGameTime() - progressTick <= ticks || ground.hasRecentPhysicalProgress(ticks);
    }
    public boolean planningInFlight() { return targets != null || session != null && session.phase().contains("plan") || ground.planningInFlight(); }
    public org.maiwithu.maicraft.core.pathing.execute.NavigationStep executionStep(long clientRevision) {
        return session == null && targets == null ? ground.executionStep(clientRevision) : null;
    }
    public String outcomeSummary() { return ground.outcomeSummary() + "; transport=" + attempts + (targets == null ? "" : targets.diagnostic()); }
    public Map<String, Object> diagnostics() {
        return Map.of("mode", mode.name().toLowerCase(), "attempts", List.copyOf(attempts), "unavailable", unavailable,
                "cleanup_pending", stopped && TransportRuntime.owns(this));
    }
    public void stop() { stopped = true; ground.stop(); TransportRuntime.cancel(this); }
    public void pause() {
        // 步行保留原路线暂停；正在乘坐／飞行时先结束这一段，并用 paused 记住恢复时需要重新选路。
        if (session != null) { paused = true; TransportRuntime.cancel(this); }
        else ground.pause();
    }
    public void abandon() { stopped = true; ground.abandon(); if (TransportRuntime.owns(this)) TransportRuntime.abandon(); }
    public boolean yieldForExternalAction() {
        if (session == null) return ground.yieldForExternalAction();
        pause(); return !TransportRuntime.owns(this) && ClientRuntime.requireContext(player).mutationAvailable();
    }
}
