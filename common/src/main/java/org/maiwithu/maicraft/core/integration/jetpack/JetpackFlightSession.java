// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.pathing.transport.TransportSession;

/** Controlled short flight with native mode receipts, loaded 3-D route and retained landing on cancel. */
public final class JetpackFlightSession implements TransportSession {
    public record Probe(boolean available, String reason, int estimatedTicks) {}
    private enum Phase { PLAN, ACTIVE, HOVER, FLY, REPLAN, LAND, RESTORE, DONE }
    private final Vec3 target;
    private final java.util.List<Vec3> platform;
    private final LongSet forbidden;
    private Phase phase = Phase.PLAN;
    private JetpackRoute.Plan route;
    private JetpackRoute.Plan originalRoute;
    private JetpackRoute.Search search;
    private JetpackNativeAdapter.Snapshot power;
    private NativeActionReceipt receipt;
    private Vec3 landing;
    private double approachHeight;
    private Vec3 holdPoint;
    private Vec3 steeringTarget;
    private float requestedYaw;
    private boolean clearanceBraking;
    private JetpackFastDescent fastDescent;
    private Vec3 fastDescentTarget;
    private boolean repaired;
    private boolean stopping, exiting, effects, uncertain, originalActive, originalHover, changedActive, changedHover, grounded;
    private long epoch = -1, revision, lastTick = Long.MIN_VALUE, waypointTick;
    private int waypoint = 1;
    private double waypointDistance = Double.POSITIVE_INFINITY;
    private String failure = "", detail = "";
    private Result terminal;
    private Map<String, Object> nativeEvidence = Map.of();
    private final java.util.ArrayDeque<Map<String, Object>> trace = new java.util.ArrayDeque<>();
    private long traceTick = Long.MIN_VALUE;
    private Phase recordedPhase;
    private Map<String, Object> departure = Map.of(), lastObstacle = Map.of();
    private final java.util.ArrayDeque<Map<String, Object>> events = new java.util.ArrayDeque<>();

    public JetpackFlightSession(Vec3 target) { this(target, LongSets.emptySet()); }
    public JetpackFlightSession(Vec3 target, LongSet forbiddenBodyCells) {
        this(target, forbiddenBodyCells, java.util.List.of(target));
    }
    public JetpackFlightSession(Vec3 target, LongSet forbiddenBodyCells, java.util.List<Vec3> platform) {
        if (target == null || !Double.isFinite(target.x) || !Double.isFinite(target.y) || !Double.isFinite(target.z)) {
            throw new IllegalArgumentException("finite jetpack target required");
        }
        this.target = target;
        this.platform = java.util.List.copyOf(platform);
        this.forbidden = LongSets.unmodifiable(new LongOpenHashSet(forbiddenBodyCells));
        fastDescent = new JetpackFastDescent(this.forbidden);
    }
    public static Probe probe(LocalPlayerContext ctx, Vec3 target) { return probe(ctx, target, LongSets.emptySet()); }
    public static Probe probe(LocalPlayerContext ctx, Vec3 target, LongSet forbidden) {
        var power = JetpackNativeAdapter.inspect(ctx);
        if (!power.controllable()) return new Probe(false, power.known() ? "fuel or native flight settings cannot support controlled flight" : power.reason(), 0);
        if (!ctx.player().onGround() || ctx.player().isInWater() || ctx.player().isPassenger() || ctx.player().isFallFlying()) {
            return new Probe(false, "jetpack departure requires a dry grounded body", 0);
        }
        if (ctx.player().position().distanceTo(target) > 64) return new Probe(false, "outside the 64-block local flight search", 0);
        Vec3 landing = JetpackRoute.observed(ctx, forbidden).landingBelow(target.add(0, 0.1, 0));
        if (landing == null || Math.abs(landing.y - target.y) > 1.01) return new Probe(false, "target landing is not observed", 0);
        int lowerBound = (int) Math.ceil(140 + JetpackRoute.edgeTicks(ctx.player().position(), target, power));
        return new Probe(power.fuelTicks() >= lowerBound,
                "equipment and landing observed; full corridor and fuel cost require incremental planning", lowerBound);
    }

    @Override public Result tick(LocalPlayerContext ctx) {
        ctx.requireCurrent();
        if (terminal != null) return terminal;
        if (lastTick == ctx.tickRevision()) return running();
        lastTick = ctx.tickRevision();
        if (epoch < 0) { epoch = ctx.bodyEpoch(); revision = ctx.controlRevision(); }
        if (epoch != ctx.bodyEpoch() || revision != ctx.controlRevision() || !ctx.body().automationOwnsControls()) {
            abandon(); return terminal;
        }
        grounded = ctx.player().onGround();
        ctx.body().applyMovement(BodyControlPort.Movement.STOPPED, ctx.tickRevision());
        power = JetpackNativeAdapter.inspect(ctx);
        nativeEvidence = JetpackNativeAdapter.activeEvidence(ctx.player());
        recordTrace(ctx, false);
        clearanceBraking = false;
        if (fastDescent.active()) {
            updateLook(ctx, fastDescentTarget, true);
            if (stopping) fastDescent.requestStop();
            boolean handled = fastDescent.tick(ctx, fastDescentTarget, power, false);
            if (fastDescent.hasEffects()) effects = changedActive = true;
            if (handled) return running();
        }
        if (receipt != null) {
            ctx.actions().poll(ctx, receipt);
            if (!receipt.terminal()) return running();
            boolean applied = receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED;
            if (!applied) { uncertain = true; failure = "jetpack_mode_unconfirmed"; detail = receipt.detail(); stopping = true; }
            receipt = null;
            if (!applied && phase == Phase.RESTORE) return finish(ctx, false);
        }
        if (phase == Phase.PLAN) {
            if (stopping) return finish(ctx, false);
            if (!power.controllable() || !grounded || ctx.player().isInWater()
                    || ctx.player().isPassenger() || ctx.player().isFallFlying()) {
                failure = "jetpack_unavailable"; detail = power.reason(); return finish(ctx, false);
            }
            if (search == null) search = new JetpackRoute.Search(ctx.player().position(), target, power);
            search.advance(JetpackRoute.observed(ctx, forbidden), 128, 1_000_000);
            if (!search.done()) return running();
            route = search.result();
            if (route == null || power.fuelTicks() < route.requiredTicks()) {
                failure = route == null ? "jetpack_no_corridor" : "jetpack_insufficient_air";
                detail = "departure refused before native effects; landing reserve included"; return finish(ctx, false);
            }
            originalActive = power.active(); originalHover = power.hover();
            originalRoute = route;
            landing = target; phase = Phase.ACTIVE;
        }
        if (stopping && grounded && phase != Phase.RESTORE) phase = Phase.RESTORE;
        if (phase == Phase.ACTIVE) {
            if (!power.active()) {
                if (!ctx.mutationAvailable()) return running();
                receipt = JetpackNativeAdapter.setMode(ctx, false, true); effects = changedActive = true; return running();
            }
            phase = Phase.HOVER;
        }
        if (phase == Phase.HOVER) {
            if (!power.hover()) {
                if (!ctx.mutationAvailable()) return running();
                receipt = JetpackNativeAdapter.setMode(ctx, true, true); effects = changedHover = true; return running();
            }
            if (!JetpackNativeAdapter.uprightActive(nativeEvidence)) {
                failure = "jetpack_native_flight_unavailable";
                detail = "native active upright context is unavailable after mode preparation: " + nativeEvidence;
                stopping = true; phase = grounded ? Phase.RESTORE : Phase.LAND;
                return grounded ? restore(ctx) : running();
            }
            phase = Phase.FLY; waypointTick = lastTick;
        }
        if (phase == Phase.REPLAN) repair(ctx);
        if (phase == Phase.FLY) fly(ctx);
        if (phase == Phase.LAND) land(ctx);
        if (phase == Phase.RESTORE) return restore(ctx);
        return running();
    }

    private void fly(LocalPlayerContext ctx) {
        Vec3 position = ctx.player().position(), velocity = ctx.player().getDeltaMovement();
        var space = JetpackRoute.observed(ctx, forbidden);
        double remaining = 60;
        for (int i = waypoint; i < route.points().size(); i++) remaining += JetpackRoute.edgeTicks(
                i == waypoint ? position : route.points().get(i - 1), route.points().get(i), power);
        if (!exiting && (stopping || !power.controllable() || !power.active() || !power.hover()
                || !JetpackNativeAdapter.uprightActive(nativeEvidence)
                || power.fuelTicks() < remaining || lastTick - waypointTick > 200)) {
            if (!stopping) { failure = "jetpack_landing_required"; detail = "fuel, mode or flight progress no longer supports the remaining route"; }
            escape(ctx, space); return;
        }
        if (route.points().size() == 2) {
            landing = route.points().getLast(); approachHeight = Math.max(position.y, landing.y);
            phase = Phase.LAND; return;
        }
        int selected = JetpackRoute.nextWaypoint(space, route, position, waypoint, power);
        if (selected != waypoint) { waypoint = selected; waypointTick = lastTick; waypointDistance = Double.POSITIVE_INFINITY; }
        Vec3 next = route.points().get(waypoint);
        double distance = position.distanceToSqr(next);
        if (distance < waypointDistance - 0.01) { waypointDistance = distance; waypointTick = lastTick; }
        if (!space.clear(position, next)) { obstruction(ctx, space, next); return; }
        if (!stopping && !exiting && position.y > next.y + 3 && tryFastDescent(ctx, next, false)) return;
        if (waypoint == route.points().size() - 2 && JetpackRoute.atWaypointHeight(route, waypoint, position.y)
                && Math.hypot(position.x - next.x, position.z - next.z) < 0.4) {
            landing = route.points().getLast(); approachHeight = next.y;
            phase = Phase.LAND; return;
        }
        if (!steer(ctx, next, false)) obstruction(ctx, space,
                position.add(0, JetpackDynamics.riseEnvelope(velocity.y, true, power), 0));
    }

    private void obstruction(LocalPlayerContext ctx, JetpackRoute.Space space, Vec3 next) {
        lastObstacle = space.obstruction(ctx.player().position(), next);
        failure = "jetpack_corridor_changed"; detail = "verified flight segment changed: " + lastObstacle;
        recordTrace(ctx, true);
        if (!repaired && !stopping && !exiting && power.controllable() && JetpackNativeAdapter.uprightActive(nativeEvidence)
                && space.clear(ctx.player().position(), ctx.player().position())) {
            repaired = true; holdPoint = ctx.player().position();
            search = new JetpackRoute.Search(holdPoint, target, power); phase = Phase.REPLAN;
            brake(ctx);
        } else escape(ctx, space);
    }

    private void repair(LocalPlayerContext ctx) {
        var space = JetpackRoute.observed(ctx, forbidden);
        if (stopping || !power.controllable() || !JetpackNativeAdapter.uprightActive(nativeEvidence)
                || !space.clear(ctx.player().position(), holdPoint)) { escape(ctx, space); return; }
        if (!steer(ctx, holdPoint, false)) { escape(ctx, space); return; }
        search.advance(space, 128, 1_000_000);
        if (!search.done()) return;
        var replacement = search.result();
        if (replacement == null || replacement.requiredTicks() > power.fuelTicks()) { escape(ctx, space); return; }
        route = replacement; waypoint = 1; waypointTick = lastTick; waypointDistance = Double.POSITIVE_INFINITY;
        failure = ""; detail = "hovered and replanned the changed flight corridor"; phase = Phase.FLY;
    }

    private void escape(LocalPlayerContext ctx, JetpackRoute.Space space) {
        var escape = JetpackEscape.choose(space, ctx.player().position(), route, waypoint, power);
        if (originalRoute != route) {
            int nearest = 0;
            for (int i = 1; i < originalRoute.points().size(); i++) {
                if (ctx.player().position().distanceToSqr(originalRoute.points().get(i))
                        < ctx.player().position().distanceToSqr(originalRoute.points().get(nearest))) nearest = i;
            }
            var originalExit = JetpackEscape.choose(space, ctx.player().position(), originalRoute, nearest + 1, power);
            if (originalExit != null && (escape == null || originalExit.requiredTicks() < escape.requiredTicks())) escape = originalExit;
        }
        if (escape == null) { approachHeight = ctx.player().getY(); phase = Phase.LAND; uncertain = true; return; }
        route = escape; waypoint = 1; waypointTick = lastTick; waypointDistance = Double.POSITIVE_INFINITY; exiting = true; phase = Phase.FLY;
        landing = route.points().getLast();
        if (power.fuelTicks() < route.requiredTicks()) { uncertain = true; detail = "continuing toward the cheapest observed exit after fuel loss"; }
        brake(ctx); // the next tick validates the exit segment before producing its motion
    }

    private void land(LocalPlayerContext ctx) {
        if (grounded) { phase = Phase.RESTORE; return; }
        Vec3 position = ctx.player().position();
        var space = JetpackRoute.observed(ctx, forbidden);
        if (!exiting && !stopping && failure.isEmpty()) {
            landing = platform.stream().filter(p -> Math.abs(p.y - target.y) < 0.01)
                    .filter(p -> JetpackRoute.supportsLanding(space, p))
                    .filter(p -> space.clear(new Vec3(p.x, approachHeight, p.z), p))
                    .min(java.util.Comparator.comparingDouble(p -> Math.hypot(p.x - position.x, p.z - position.z))).orElse(null);
        }
        if (landing == null || !JetpackRoute.supportsLanding(space, landing)) {
            // Retain native hover and braking while searching next tick, instead of releasing an airborne body.
            failure = "jetpack_landing_support_changed"; detail = "the selected platform no longer has its observed support";
            if (power.controllable()) { escape(ctx, space); if (phase == Phase.FLY) return; }
            steer(ctx, new Vec3(position.x, approachHeight, position.z), false); return;
        }
        boolean centered = Math.hypot(position.x - landing.x, position.z - landing.z) < 0.18
                && ctx.player().getDeltaMovement().horizontalDistance() < 0.08;
        // Hold the verified staging height while braking and crossing the platform edge.
        // Using position.y here would continually lower the target as native hover sinks.
        Vec3 approach = landingAim(position, landing, approachHeight, centered);
        if (!space.clear(position, approach)) {
            obstruction(ctx, space, approach); return;
        }
        if (!stopping && !exiting && centered && tryFastDescent(ctx, landing, true)) return;
        if (!steer(ctx, approach, centered)) obstruction(ctx, space,
                position.add(0, JetpackDynamics.riseEnvelope(ctx.player().getDeltaMovement().y, true, power), 0));
    }

    static Vec3 landingAim(Vec3 position, Vec3 landing, double approachHeight, boolean centered) {
        if (centered) return landing;
        return position.y < approachHeight - 0.1 ? new Vec3(position.x, approachHeight, position.z)
                : new Vec3(landing.x, approachHeight, landing.z);
    }

    private boolean tryFastDescent(LocalPlayerContext ctx, Vec3 target, boolean touchdown) {
        if (fastDescent.finished() && fastDescentTarget != null && fastDescentTarget.distanceToSqr(target) > 0.01)
            fastDescent = new JetpackFastDescent(forbidden);
        if (fastDescent.finished()) return false;
        updateLook(ctx, target, true);
        if (!fastDescent.tick(ctx, target, power, true, touchdown)) return false;
        fastDescentTarget = target;
        if (fastDescent.hasEffects()) effects = changedActive = true;
        return true;
    }

    private boolean steer(LocalPlayerContext ctx, Vec3 aim, boolean landingNow) {
        var player = ctx.player();
        updateLook(ctx, aim, landingNow);
        Vec3 position = player.position(), velocity = player.getDeltaMovement();
        var settings = power;
        clearanceBraking = phase == Phase.FLY && !grounded && !landingNow
                && !JetpackMotion.clearTrajectory(JetpackRoute.observed(ctx, forbidden), position, velocity,
                        aim, player.getYRot(), requestedYaw, settings);
        if (clearanceBraking) {
            // At a narrow aperture, looking around the next corner can quantize input toward its rim.
            // Face this leg and reassess before braking; otherwise a stopped body can never resume.
            lookAt(ctx, aim, landingNow);
            clearanceBraking = !JetpackMotion.clearTrajectory(JetpackRoute.observed(ctx, forbidden), position, velocity,
                    aim, player.getYRot(), requestedYaw, settings);
        }
        Vec3 motionAim = clearanceBraking ? new Vec3(position.x, aim.y, position.z) : aim;
        steeringTarget = motionAim;
        var command = JetpackView.command(position, velocity, motionAim, player.getYRot(), landingNow, settings);
        if (command.jumping()) {
            double rise = JetpackDynamics.riseEnvelope(player.getDeltaMovement().y, true, power);
            if (!JetpackRoute.observed(ctx, forbidden).clear(player.position(), player.position().add(0, rise, 0))) {
                brake(ctx); return false;
            }
        }
        ctx.body().applySteering(yaw -> JetpackView.command(position, velocity, motionAim, yaw, landingNow, settings),
                player.getYRot(), ctx.tickRevision());
        effects = true;
        return true;
    }

    private void brake(LocalPlayerContext ctx) {
        var player = ctx.player();
        Vec3 position = player.position(), velocity = player.getDeltaMovement();
        var settings = power;
        ctx.body().applySteering(yaw -> JetpackSteering.toward(position, velocity, position, yaw, true, settings),
                player.getYRot(), ctx.tickRevision());
        effects = true;
    }

    private void updateLook(LocalPlayerContext ctx, Vec3 aim, boolean landingNow) {
        if (aim == null) return;
        steeringTarget = aim;
        Vec3 focus = aim;
        if (phase == Phase.FLY && route != null && !landingNow) {
            Vec3 ahead = JetpackView.lookAhead(route.points(), waypoint, ctx.player().position(), 6);
            if (JetpackRoute.observed(ctx, forbidden).clear(ctx.player().position(), ahead))
                focus = JetpackView.focus(ctx.player().position(), aim, ahead);
        }
        lookAt(ctx, focus, landingNow);
    }

    private void lookAt(LocalPlayerContext ctx, Vec3 focus, boolean landingNow) {
        var look = JetpackView.toward(ctx.player().position(), ctx.player().getEyeHeight(), focus, ctx.player().getYRot(), landingNow);
        requestedYaw = look.yaw();
        ctx.body().requestLook(look.yaw(), look.pitch(), ctx.tickRevision());
    }

    private Result restore(LocalPlayerContext ctx) {
        if (!grounded) { phase = Phase.LAND; return running(); }
        if (!power.known()) { uncertain = true; failure = "jetpack_restore_unknown"; return finish(ctx, false); }
        if (changedHover && power.hover() != originalHover) {
            if (!ctx.mutationAvailable()) return running();
            receipt = JetpackNativeAdapter.setMode(ctx, true, originalHover); changedHover = false; return running();
        }
        if (changedActive && power.active() != originalActive) {
            if (!ctx.mutationAvailable()) return running();
            receipt = JetpackNativeAdapter.setMode(ctx, false, originalActive); changedActive = false; return running();
        }
        boolean arrived = !stopping && failure.isEmpty() && platform.stream().anyMatch(p -> ctx.player().position().distanceTo(p) < 0.75);
        return finish(ctx, arrived);
    }
    private Result finish(LocalPlayerContext ctx, boolean success) {
        recordTrace(ctx, true);
        ctx.body().releaseAll(); phase = Phase.DONE;
        terminal = success ? Result.success("native jetpack flight landed; observed original modes restored")
                : Result.failed(failure.isEmpty() ? "jetpack_cancelled" : failure,
                detail.isEmpty() ? "jetpack session settled on ground" : detail, effects, uncertain);
        return terminal;
    }
    private Result running() { return new Result(State.RUNNING, phase(), detail, effects, uncertain); }
    private void recordTrace(LocalPlayerContext ctx, boolean force) {
        boolean transition = recordedPhase != phase;
        if (!force && !transition && traceTick != Long.MIN_VALUE && lastTick - traceTick < 10) return;
        traceTick = lastTick;
        var sample = new LinkedHashMap<String, Object>();
        sample.put("tick", lastTick); sample.put("phase", phase()); sample.put("position", ctx.player().position().toString());
        sample.put("health", ctx.player().getHealth()); sample.put("absorption", ctx.player().getAbsorptionAmount());
        sample.put("native_client_evidence", nativeEvidence);
        sample.put("waypoint", waypoint); sample.put("detail", detail);
        sample.put("clearance_braking", clearanceBraking);
        if (route != null && waypoint < route.points().size())
            sample.put("waypoint_target", route.points().get(waypoint).toString());
        if (!lastObstacle.isEmpty()) sample.put("obstruction", lastObstacle);
        if (departure.isEmpty()) departure = Map.copyOf(sample);
        if (force || transition) { events.addLast(Map.copyOf(sample)); while (events.size() > 24) events.removeFirst(); }
        recordedPhase = phase;
        trace.addLast(Map.copyOf(sample));
        while (trace.size() > 48) trace.removeFirst();
    }
    @Override public void requestStop() { stopping = true; fastDescent.requestStop(); }
    @Override public void abandon() {
        fastDescent.abandon();
        ClientRuntime.actor().body().releaseAll(); phase = Phase.DONE;
        terminal = Result.failed("jetpack_abandoned", "body ownership ended; no further mode or flight actions", effects, effects);
    }
    @Override public boolean safeToInterrupt() { return terminal != null || grounded && !effects; }
    @Override public boolean livenessActive() { return terminal == null && (receipt != null || effects || search != null && !search.done()); }
    @Override public String phase() { return fastDescent.active() ? "jetpack_drop_" + fastDescent.phase()
            : "jetpack_" + phase.name().toLowerCase(java.util.Locale.ROOT); }
    @Override public org.maiwithu.maicraft.core.pathing.debug.NavigationPathSnapshot debugPath() {
        return route == null ? null : new org.maiwithu.maicraft.core.pathing.debug.NavigationPathSnapshot(
                route.points(), waypoint, landing, steeringTarget);
    }
    @Override public Map<String, Object> diagnostics() {
        var result = new LinkedHashMap<String, Object>();
        result.put("phase", phase()); result.put("stopping", stopping); result.put("waypoint", waypoint);
        result.put("target", target.toString()); result.put("landing", landing == null ? "unobserved" : landing.toString());
        result.put("effects_started", effects); result.put("uncertain", uncertain); result.put("detail", detail);
        result.put("grounded", grounded);
        result.put("landing_approach_height", approachHeight);
        result.put("native_client_evidence", nativeEvidence);
        result.put("recent_flight_trace", java.util.List.copyOf(trace));
        result.put("departure", departure); result.put("flight_events", java.util.List.copyOf(events));
        result.put("last_obstruction", lastObstacle); result.put("platform_cells", platform.size());
        result.put("fast_descent", fastDescent.diagnostics());
        result.put("clearance_braking", clearanceBraking);
        result.put("search_radius", 64); result.put("search_node_limit", 6000);
        if (search != null) result.put("search_expanded", search.expanded());
        if (route != null) { result.put("route_points", route.points().size()); result.put("estimated_ticks_with_reserve", route.requiredTicks()); }
        if (power != null) { result.put("native_state", power.reason()); result.put("active", power.active()); result.put("hover", power.hover());
            result.put("priority_tank_air", power.air()); result.put("conservative_fuel_ticks", power.fuelTicks()); }
        if (receipt != null) { result.put("mode_receipt", receipt.status().name()); result.put("mode_confirmation", "observed client mode; no server acknowledgement"); }
        return result;
    }
    public static Map<String, Object> inspect(net.minecraft.client.player.LocalPlayer player) {
        var power = JetpackNativeAdapter.observe(player);
        var result = new LinkedHashMap<String, Object>();
        result.put("known", power.known()); result.put("reason", power.reason()); result.put("item", power.item());
        if (power.known()) { result.put("active", power.active()); result.put("hover", power.hover());
            result.put("priority_tank_air", power.air()); result.put("conservative_fuel_ticks", power.fuelTicks());
            result.put("controllable", power.controllable()); result.put("mode_evidence", "native client attachment; no server acknowledgement"); }
        return result;
    }
}
