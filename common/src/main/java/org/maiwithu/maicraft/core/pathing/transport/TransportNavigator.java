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

/** Ground navigation plus native transport recovery, shared by travel and workstation approaches. */
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
            paused = false; failure = null; attempted = false; forceConsumed = false; triedOffers.clear();
            ground = newGround(); targets = null; offers = List.of();
        }
        if (reached.getAsBoolean()) {
            targets = null;
            return ground.tick(); // preserve its airborne boundary and native owner release
        }
        if (failureType == FailureType.UNKNOWN && failure != null) return PlayerNav.Status.FAILED;
        if (targets != null) {
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
    public String outcomeSummary() { return ground.outcomeSummary() + "; transport=" + attempts + (targets == null ? "" : targets.diagnostic()); }
    public Map<String, Object> diagnostics() {
        return Map.of("mode", mode.name().toLowerCase(), "attempts", List.copyOf(attempts), "unavailable", unavailable,
                "cleanup_pending", stopped && TransportRuntime.owns(this));
    }
    public void stop() { stopped = true; ground.stop(); TransportRuntime.cancel(this); }
    public void pause() {
        if (session != null) { paused = true; TransportRuntime.cancel(this); }
        else ground.pause();
    }
    public void abandon() { stopped = true; ground.abandon(); if (TransportRuntime.owns(this)) TransportRuntime.abandon(); }
    public boolean yieldForExternalAction() {
        if (session == null) return ground.yieldForExternalAction();
        pause(); return !TransportRuntime.owns(this) && ClientRuntime.requireContext(player).mutationAvailable();
    }
}
