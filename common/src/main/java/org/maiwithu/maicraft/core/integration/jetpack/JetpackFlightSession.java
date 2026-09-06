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
    private enum Phase { PLAN, ACTIVE, HOVER, FLY, LAND, RESTORE, DONE }
    private final Vec3 target;
    private final LongSet forbidden;
    private Phase phase = Phase.PLAN;
    private JetpackRoute.Plan route;
    private JetpackRoute.Plan originalRoute;
    private JetpackRoute.Search search;
    private JetpackNativeAdapter.Snapshot power;
    private NativeActionReceipt receipt;
    private Vec3 landing;
    private boolean stopping, exiting, effects, uncertain, originalActive, originalHover, changedActive, changedHover, grounded;
    private long epoch = -1, revision, lastTick = Long.MIN_VALUE, waypointTick;
    private int waypoint = 1;
    private String failure = "", detail = "";
    private Result terminal;

    public JetpackFlightSession(Vec3 target) { this(target, LongSets.emptySet()); }
    public JetpackFlightSession(Vec3 target, LongSet forbiddenBodyCells) {
        if (target == null || !Double.isFinite(target.x) || !Double.isFinite(target.y) || !Double.isFinite(target.z)) {
            throw new IllegalArgumentException("finite jetpack target required");
        }
        this.target = target;
        this.forbidden = LongSets.unmodifiable(new LongOpenHashSet(forbiddenBodyCells));
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
            landing = route.emergencyLandings().getFirst(); phase = Phase.ACTIVE;
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
            phase = Phase.FLY; waypointTick = lastTick;
        }
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
        Vec3 exit = space.landingBelow(position.add(0, 0.1, 0));
        if (exit != null) landing = exit;
        if (!exiting && (stopping || !power.controllable() || !power.active() || !power.hover()
                || power.fuelTicks() < remaining || lastTick - waypointTick > 200)) {
            if (!stopping) { failure = "jetpack_landing_required"; detail = "fuel, mode or flight progress no longer supports the remaining route"; }
            escape(ctx, space); return;
        }
        Vec3 next = route.points().get(waypoint);
        boolean routeClear = space.clear(position, next);
        boolean momentumClear = space.clear(position, JetpackRoute.projectedPosition(position, velocity, grounded));
        if (!routeClear || !momentumClear) {
            failure = "jetpack_corridor_changed";
            detail = routeClear ? "projected body momentum enters an obstruction" : "route segment is no longer clear";
            escape(ctx, space); return;
        }
        if (waypoint == route.points().size() - 1) { landing = next; phase = Phase.LAND; return; }
        if (Math.hypot(position.x - next.x, position.z - next.z) < 0.2
                && Math.abs(position.y - next.y) < 0.3 && velocity.horizontalDistance() < 0.08) {
            waypoint++; waypointTick = lastTick; next = route.points().get(waypoint);
        }
        steer(ctx, next, false);
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
        if (escape == null) { phase = Phase.LAND; uncertain = true; return; }
        route = escape; waypoint = 1; waypointTick = lastTick; exiting = true; phase = Phase.FLY;
        landing = route.points().getLast();
        if (power.fuelTicks() < route.requiredTicks()) { uncertain = true; detail = "continuing toward the cheapest observed exit after fuel loss"; }
        steer(ctx, route.points().get(waypoint), false);
    }

    private void land(LocalPlayerContext ctx) {
        if (grounded) { phase = Phase.RESTORE; return; }
        Vec3 position = ctx.player().position();
        var space = JetpackRoute.observed(ctx, forbidden);
        Vec3 below = space.landingBelow(position.add(0, 0.1, 0));
        if (below != null && (stopping || !failure.isEmpty())) landing = below;
        if (landing == null || !space.clear(position, landing)) {
            // Retain native hover and braking while searching next tick, instead of releasing an airborne body.
            uncertain = true; detail = "awaiting a newly observed clear landing column";
            if (power.controllable()) { escape(ctx, space); if (phase == Phase.FLY) return; }
            steer(ctx, position, false); return;
        }
        boolean centered = Math.hypot(position.x - landing.x, position.z - landing.z) < 0.18
                && ctx.player().getDeltaMovement().horizontalDistance() < 0.08;
        steer(ctx, centered ? landing : new Vec3(landing.x, position.y, landing.z), centered);
    }

    private void steer(LocalPlayerContext ctx, Vec3 aim, boolean landingNow) {
        var player = ctx.player();
        Vec3 delta = aim.subtract(player.position());
        if (delta.horizontalDistance() > 0.3) ctx.body().requestLook(
                (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90), 8, ctx.tickRevision());
        ctx.body().applyMovement(JetpackSteering.toward(player.position(), player.getDeltaMovement(), aim,
                player.getYRot(), landingNow), ctx.tickRevision());
        effects = true;
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
        boolean arrived = !stopping && failure.isEmpty() && ctx.player().position().distanceTo(target) < 0.75;
        return finish(ctx, arrived);
    }
    private Result finish(LocalPlayerContext ctx, boolean success) {
        ctx.body().releaseAll(); phase = Phase.DONE;
        terminal = success ? Result.success("native jetpack flight landed; observed original modes restored")
                : Result.failed(failure.isEmpty() ? "jetpack_cancelled" : failure,
                detail.isEmpty() ? "jetpack session settled on ground" : detail, effects, uncertain);
        return terminal;
    }
    private Result running() { return new Result(State.RUNNING, phase(), detail, effects, uncertain); }
    @Override public void requestStop() { stopping = true; }
    @Override public void abandon() {
        ClientRuntime.actor().body().releaseAll(); phase = Phase.DONE;
        terminal = Result.failed("jetpack_abandoned", "body ownership ended; no further mode or flight actions", effects, effects);
    }
    @Override public boolean safeToInterrupt() { return terminal != null || grounded && !effects; }
    @Override public boolean livenessActive() { return terminal == null && (receipt != null || effects || search != null && !search.done()); }
    @Override public String phase() { return "jetpack_" + phase.name().toLowerCase(java.util.Locale.ROOT); }
    @Override public Map<String, Object> diagnostics() {
        var result = new LinkedHashMap<String, Object>();
        result.put("phase", phase()); result.put("stopping", stopping); result.put("waypoint", waypoint);
        result.put("target", target.toString()); result.put("landing", landing == null ? "unobserved" : landing.toString());
        result.put("effects_started", effects); result.put("uncertain", uncertain); result.put("detail", detail);
        result.put("grounded", grounded);
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
