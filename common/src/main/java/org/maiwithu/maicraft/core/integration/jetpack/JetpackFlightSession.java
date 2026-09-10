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

/**
 * 执行一趟飞行：分次找路、开启背包和悬停、按路飞、处理移动目标或障碍、落地后恢复原开关。
 * 开始操作前可以直接拒绝；已经飞起来就要继续找落点并收尾，不能把停止请求当成立刻松开所有控制。
 */
public final class JetpackFlightSession implements TransportSession {
    public record Probe(boolean available, String reason, int estimatedTicks) {}
    private enum Phase { PLAN, ACTIVE, HOVER, FLY, REPLAN, LAND, RESTORE, DONE }
    private Vec3 target;
    private final MovingFlightTarget movingTarget;
    private Vec3 planningTarget;
    private boolean planningLanding;
    private Vec3 observationHold;
    private boolean discoveringExit;
    private int departureNodes;
    private boolean searchCharged, searchBudgetExhausted;
    private final java.util.List<Map<String,Object>> preflightAttempts = new java.util.ArrayList<>();
    private java.util.List<Vec3> platform;
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
        this(target, forbiddenBodyCells, platform, null);
    }
    public JetpackFlightSession(MovingFlightTarget target, LongSet forbiddenBodyCells) {
        this(target.point(), forbiddenBodyCells, java.util.List.of(target.point()), target);
    }
    private JetpackFlightSession(Vec3 target, LongSet forbiddenBodyCells, java.util.List<Vec3> platform, MovingFlightTarget movingTarget) {
        this.movingTarget = movingTarget;
        if (target == null || !Double.isFinite(target.x) || !Double.isFinite(target.y) || !Double.isFinite(target.z)) {
            throw new IllegalArgumentException("finite jetpack target required");
        }
        this.target = target;
        this.platform = java.util.List.copyOf(platform);
        this.forbidden = LongSets.unmodifiable(new LongOpenHashSet(forbiddenBodyCells));
        fastDescent = new JetpackFastDescent(this.forbidden);
    }
    public static Probe probe(LocalPlayerContext ctx, Vec3 target) { return probe(ctx, target, LongSets.emptySet()); }
    // 这是地面出发候选的快速筛选，只看设备、附近落点和最低预计耗气；完整路线由会话继续验证。
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
        if (movingTarget != null && followsTarget()) {
            if (!movingTarget.update(ctx)) {
                failure = "jetpack_target_unavailable"; detail = movingTarget.diagnostics().toString();
                if(discoveringExit) discoveringExit=false;
                else requestStop();
                if (!effects) return finish(ctx, false);
            } else {
                target = movingTarget.point(); platform = java.util.List.of(target);
                if (phase==Phase.LAND && !movingTarget.landingSelected()) phase=Phase.FLY;
                if ((phase == Phase.FLY || phase == Phase.LAND) && grounded && movingTarget.contact()) phase = Phase.LAND;
            }
        }
        recordTrace(ctx, false);
        clearanceBraking = false;
        if (fastDescent.active()) {
            updateLook(ctx, fastDescentTarget, true);
            if (stopping || movingTarget!=null && !movingTarget.supportsFastDescent()) fastDescent.requestStop();
            boolean handled = fastDescent.tick(ctx, fastDescentTarget, power, false,true,space(ctx));
            if (fastDescent.hasEffects()) effects = true;
            if (fastDescent.hasModeChanges()) changedActive = true;
            if (handled) {
                double distance = ctx.player().position().distanceToSqr(fastDescentTarget);
                if (distance < waypointDistance - .01) { waypointDistance = distance; waypointTick = lastTick; }
                return running();
            }
        }
        if (receipt != null) {
            ctx.actions().poll(ctx, receipt);
            if (!receipt.terminal()) return running();
            boolean applied = receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED;
            if (!applied) { uncertain = true; failure = "jetpack_mode_unconfirmed"; detail = receipt.detail(); stopping = true; }
            receipt = null;
            if (!applied && phase == Phase.RESTORE) return finish(ctx, false);
        }
        // 规划阶段可换候选落点，但总离地搜索预算共享，不能换一个候选就重新取得全部额度。
        if (phase == Phase.PLAN) {
            if (stopping) return finish(ctx, false);
            if (!power.controllable() || !grounded && (!power.active() || !power.hover()
                    || !JetpackNativeAdapter.uprightActive(nativeEvidence)) || ctx.player().isInWater()
                    || ctx.player().isPassenger() || ctx.player().isFallFlying()) {
                failure = "jetpack_unavailable"; detail = power.reason(); return finish(ctx, false);
            }
            if (movingTarget != null && !movingTarget.ready()) return running();
            if (search == null || movingTarget != null && (planningTarget.distanceTo(target) > 1 || planningLanding != landingSelected())) {
                chargeDepartureSearch();
                planningTarget = target;
                planningLanding = landingSelected();
                search = new JetpackRoute.Search(ctx.player().position(), target, power, Math.max(0,6000-departureNodes),planningLanding);
                searchCharged = false;
            }
            search.advance(space(ctx), 128, 1_000_000);
            if (!search.done()) return running();
            chargeDepartureSearch();
            route = search.result();
            if (route == null || power.fuelTicks() < route.requiredTicks()) {
                String reason = route == null ? search.failureReason() : "insufficient_air";
                searchBudgetExhausted |= "budget_exhausted".equals(reason);
                preflightAttempts.add(Map.of("target",target.toString(),"reason",reason,"expanded",search.expanded()));
                if (movingTarget != null && movingTarget.nextLanding()) {
                    search = null; detail = "checking another observed deck face before takeoff"; return running();
                }
                failure = "jetpack_" + (searchBudgetExhausted ? "search_budget_exhausted" : reason);
                detail = searchBudgetExhausted ? "initial flight search budget exhausted; an unreachable corridor has not been proven"
                        : "departure refused before native flight effects: " + reason;
                return finish(ctx, false);
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
        if (phase == Phase.FLY && movingTarget != null && followsTarget()) {
            if (!movingTarget.ready()) {
                if (observationHold==null) observationHold=ctx.player().position();
                if (!power.controllable() || !power.active() || !power.hover()
                        || !JetpackNativeAdapter.uprightActive(nativeEvidence) || power.fuelTicks()<140) {
                    stopping=true; discoveringExit=false; escape(ctx,space(ctx));
                }
                else steer(ctx,observationHold,false);
                return running();
            }
            observationHold=null; retarget(ctx);
        }
        if (phase == Phase.REPLAN) repair(ctx);
        if (phase == Phase.FLY) fly(ctx);
        if (phase == Phase.LAND) land(ctx);
        if (phase == Phase.RESTORE) return restore(ctx);
        return running();
    }

    private JetpackRoute.Space space(LocalPlayerContext ctx) {
        return movingTarget != null && followsTarget()
                ? movingTarget.space(ctx, forbidden) : JetpackRoute.observed(ctx, forbidden);
    }

    private void chargeDepartureSearch() {
        if (search != null && !searchCharged) { departureNodes += search.expanded(); searchCharged = true; }
    }

    private boolean landingSelected() { return movingTarget==null || movingTarget.landingSelected(); }
    private boolean followsTarget() { return (!stopping || discoveringExit) && !exiting; }

    // 移动目标偏移后优先接一条新直线；无法直接接上时先悬停，再分次重新找路。
    private void retarget(LocalPlayerContext ctx) {
        if (route == null || route.points().getLast().distanceTo(target) < .5 && planningLanding==landingSelected()) return;
        var space = space(ctx);
        Vec3 position = ctx.player().position();
        planningTarget=target;
        if (!landingSelected()) {
            if (!JetpackRoute.flightClear(space,position,target,power)) { obstruction(ctx,space,target); return; }
            route=new JetpackRoute.Plan(java.util.List.of(position,target),route.emergencyLandings(),
                    (int)Math.ceil(140+JetpackRoute.edgeTicks(position,target,power)));
            planningLanding=false; waypoint=1; waypointDistance=Double.POSITIVE_INFINITY; waypointTick=lastTick; repaired=false;
            return;
        }
        planningLanding=true;
        double reserve = Math.max(1, route.points().get(route.points().size()-2).y - route.points().getLast().y);
        Vec3 approach = target.add(0,reserve,0);
        if (JetpackRoute.flightClear(space,position,approach,power) && space.clear(approach,target)) {
            route = new JetpackRoute.Plan(java.util.List.of(position,approach,target), route.emergencyLandings(),
                    (int)Math.ceil(140 + JetpackRoute.edgeTicks(position,target,power)));
            waypoint = 1; waypointDistance = Double.POSITIVE_INFINITY; waypointTick = lastTick;
        } else {
            holdPoint = position; planningTarget = target;
            search = new JetpackRoute.Search(position,target,power); phase = Phase.REPLAN;
        }
    }

    private void fly(LocalPlayerContext ctx) {
        Vec3 position = ctx.player().position(), velocity = ctx.player().getDeltaMovement();
        var space = space(ctx);
        double remaining = 60;
        for (int i = waypoint; i < route.points().size(); i++) remaining += JetpackRoute.edgeTicks(
                i == waypoint ? position : route.points().get(i - 1), route.points().get(i), power);
        if (!exiting && (stopping && !discoveringExit || !power.controllable() || !power.active() || !power.hover()
                || !JetpackNativeAdapter.uprightActive(nativeEvidence)
                || power.fuelTicks() < remaining || lastTick - waypointTick > 200)) {
            if (!stopping) { failure = "jetpack_landing_required"; detail = "fuel, mode or flight progress no longer supports the remaining route"; }
            escape(ctx, space); return;
        }
        if (route.points().size() == 2) {
            if (followsTarget() && !landingSelected()) {
                Vec3 next=route.points().getLast();
                double distance=position.distanceToSqr(next);
                if(distance<waypointDistance-.01) { waypointDistance=distance; waypointTick=lastTick; }
                if(!space.clear(position,next)) { obstruction(ctx,space,next); return; }
                if(!stopping && position.y>next.y+.1 && tryFastDescent(ctx,next,false)) return;
                if(!steer(ctx,next,false)) obstruction(ctx,space,next);
                return;
            }
            landing = route.points().getLast(); approachHeight = Math.max(position.y, landing.y);
            phase = Phase.LAND; return;
        }
        int selected = JetpackRoute.nextWaypoint(space, route, position, waypoint, power);
        if (selected != waypoint) { waypoint = selected; waypointTick = lastTick; waypointDistance = Double.POSITIVE_INFINITY; }
        Vec3 next = route.points().get(waypoint);
        double distance = position.distanceToSqr(next);
        if (distance < waypointDistance - 0.01) { waypointDistance = distance; waypointTick = lastTick; }
        if (!space.clear(position, next)) { obstruction(ctx, space, next); return; }
        if (!stopping && !exiting && position.y > next.y + .1 && tryFastDescent(ctx, next, false)) return;
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
        if (!repaired && followsTarget() && power.controllable() && JetpackNativeAdapter.uprightActive(nativeEvidence)
                && space.clear(ctx.player().position(), ctx.player().position())) {
            repaired = true; holdPoint = ctx.player().position();
            planningTarget=target;
            planningLanding=landingSelected();
            search = new JetpackRoute.Search(holdPoint, target, power,6000,planningLanding); phase = Phase.REPLAN;
            brake(ctx);
        } else escape(ctx, space);
    }

    // 重规划时继续守住当前悬停点；设备、空间或停止条件不再允许时，改找提前落地出口。
    private void repair(LocalPlayerContext ctx) {
        var space = space(ctx);
        if (stopping && !discoveringExit || !power.controllable() || !JetpackNativeAdapter.uprightActive(nativeEvidence)
                || !space.clear(ctx.player().position(), holdPoint)) { escape(ctx, space); return; }
        if (!steer(ctx, holdPoint, false)) { escape(ctx, space); return; }
        if (movingTarget!=null && !movingTarget.ready()) return;
        if (movingTarget != null && planningTarget != null && (planningTarget.distanceTo(target) > 1 || planningLanding!=landingSelected())) {
            planningTarget = target; planningLanding=landingSelected();
            search = new JetpackRoute.Search(ctx.player().position(),target,power,6000,planningLanding);
        }
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

    // 先对准平台并消掉相对横向速度，再下降；移动平台还要确认真实接触和连续站稳。
    private void land(LocalPlayerContext ctx) {
        if (grounded) {
            if (movingTarget != null && !stopping && !exiting && failure.isEmpty()) {
                if (movingTarget.contact() && !movingTarget.touchdown()) return;
                if (!movingTarget.touchdown()) { failure = "jetpack_wrong_structure"; detail = "grounded without native support on the requested vessel"; }
            }
            phase = Phase.RESTORE; return;
        }
        Vec3 position = ctx.player().position();
        var space = space(ctx);
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
        if (movingTarget != null && !stopping && !exiting) {
            double reserve = Math.max(1, route.points().get(route.points().size()-2).y - route.points().getLast().y);
            approachHeight = landing.y + reserve;
        }
        Vec3 deckMotion = movingTarget != null && !stopping && !exiting ? movingTarget.velocity() : Vec3.ZERO;
        boolean centered = Math.hypot(position.x - landing.x, position.z - landing.z) < 0.25
                && ctx.player().getDeltaMovement().subtract(deckMotion.x,0,deckMotion.z).horizontalDistance() < 0.08;
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
        if (movingTarget != null && !movingTarget.supportsFastDescent()) return false;
        if (fastDescent.finished() && fastDescentTarget != null && fastDescentTarget.distanceToSqr(target) > 0.01)
            fastDescent = new JetpackFastDescent(forbidden);
        if (fastDescent.finished()) return false;
        updateLook(ctx, target, true);
        if (!fastDescent.tick(ctx, target, power, true, touchdown,space(ctx))) return false;
        fastDescentTarget = target;
        waypointDistance = ctx.player().position().distanceToSqr(target); waypointTick = lastTick;
        if (fastDescent.hasEffects()) effects = true;
        if (fastDescent.hasModeChanges()) changedActive = true;
        return true;
    }

    private boolean steer(LocalPlayerContext ctx, Vec3 aim, boolean landingNow) {
        var player = ctx.player();
        updateLook(ctx, aim, landingNow);
        Vec3 position = player.position(), velocity = player.getDeltaMovement();
        var settings = power;
        clearanceBraking = phase == Phase.FLY && !grounded && !landingNow
                && !JetpackMotion.clearTrajectory(space(ctx), position, velocity,
                        aim, player.getYRot(), requestedYaw, settings);
        if (clearanceBraking) {
            // At a narrow aperture, looking around the next corner can quantize input toward its rim.
            // Face this leg and reassess before braking; otherwise a stopped body can never resume.
            lookAt(ctx, aim, landingNow);
            clearanceBraking = !JetpackMotion.clearTrajectory(space(ctx), position, velocity,
                    aim, player.getYRot(), requestedYaw, settings);
        }
        Vec3 motionAim = clearanceBraking ? new Vec3(position.x, aim.y, position.z) : aim;
        steeringTarget = motionAim;
        Vec3 controlVelocity = velocity;
        if (movingTarget != null && phase == Phase.LAND && !stopping && !exiting) {
            Vec3 deckMotion = movingTarget.velocity();
            controlVelocity = velocity.subtract(deckMotion.x, 0, deckMotion.z);
        }
        final Vec3 steeringVelocity = controlVelocity;
        var command = JetpackView.command(position, steeringVelocity, motionAim, player.getYRot(), landingNow, settings);
        if (command.jumping() && !JetpackMotion.canRise(space(ctx), position, velocity,
                motionAim, player.getYRot(), requestedYaw, grounded, settings)) {
            brake(ctx); return false;
        }
        ctx.body().applySteering(yaw -> JetpackView.command(position, steeringVelocity, motionAim, yaw, landingNow, settings),
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
            if (space(ctx).clear(ctx.player().position(), ahead))
                focus = JetpackView.focus(ctx.player().position(), aim, ahead);
        }
        lookAt(ctx, focus, landingNow);
    }

    private void lookAt(LocalPlayerContext ctx, Vec3 focus, boolean landingNow) {
        var look = JetpackView.toward(ctx.player().position(), ctx.player().getEyeHeight(), focus, ctx.player().getYRot(), landingNow);
        requestedYaw = look.yaw();
        ctx.body().requestLook(look.yaw(), look.pitch(), ctx.tickRevision());
    }

    // 站稳后按需恢复本趟改过的开关；仍在空中时先回到飞行处理，不能在这里直接宣布结束。
    private Result restore(LocalPlayerContext ctx) {
        if (!grounded) { phase = Phase.ACTIVE; return running(); }
        if (!power.known()) { uncertain = true; failure = "jetpack_restore_unknown"; return finish(ctx, false); }
        if (changedHover && power.hover() != originalHover) {
            if (!ctx.mutationAvailable()) return running();
            receipt = JetpackNativeAdapter.setMode(ctx, true, originalHover); changedHover = false; return running();
        }
        if (changedActive && power.active() != originalActive) {
            if (!ctx.mutationAvailable()) return running();
            receipt = JetpackNativeAdapter.setMode(ctx, false, originalActive); changedActive = false; return running();
        }
        boolean arrived = !stopping && failure.isEmpty() && (movingTarget != null ? movingTarget.touchdown()
                : platform.stream().anyMatch(p -> ctx.player().position().distanceTo(p) < 0.75));
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
    // 平时隔十刻记录一次，阶段变化或故障立即记录；保留有限的最近样本，避免诊断数据无限增长。
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
    @Override public void requestStop() {
        stopping=true;
        discoveringExit=movingTarget!=null && movingTarget.seekLandingOnStop();
        fastDescent.requestStop();
    }
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
        result.put("landing_selected",landingSelected());
        if (movingTarget != null) result.put("moving_target", movingTarget.diagnostics());
        result.put("target", target.toString()); result.put("landing", landing == null ? "unobserved" : landing.toString());
        result.put("effects_started", effects); result.put("uncertain", uncertain); result.put("detail", detail);
        result.put("grounded", grounded);
        result.put("landing_approach_height", approachHeight);
        result.put("native_client_evidence", nativeEvidence);
        result.put("recent_flight_trace", compactTrace(trace));
        result.put("departure", departure); result.put("flight_events", compactTrace(events));
        result.put("preflight_attempts",java.util.List.copyOf(preflightAttempts));
        result.put("last_obstruction", lastObstacle); result.put("platform_cells", platform.size());
        result.put("fast_descent", fastDescent.diagnostics());
        result.put("clearance_braking", clearanceBraking);
        result.put("search_radius", 64); result.put("search_node_limit", 6000);
        if (search != null) result.put("search_expanded", search.expanded());
        result.put("departure_search_nodes",departureNodes + (phase == Phase.PLAN && search != null && !searchCharged ? search.expanded() : 0));
        if (search != null && search.failureReason() != null) result.put("search_result",search.failureReason());
        if (route != null) { result.put("route_points", route.points().size()); result.put("estimated_ticks_with_reserve", route.requiredTicks()); }
        if (power != null) { result.put("native_state", power.reason()); result.put("active", power.active()); result.put("hover", power.hover());
            result.put("priority_tank_air", power.air()); result.put("conservative_fuel_ticks", power.fuelTicks()); }
        if (receipt != null) { result.put("mode_receipt", receipt.status().name()); result.put("mode_confirmation", "observed client mode; no server acknowledgement"); }
        return result;
    }
    /** Keep changes and their time spans; repeated per-tick native dictionaries do not fill MCP context. */
    static java.util.List<Map<String,Object>> compactTrace(java.util.Collection<Map<String,Object>> source) {
        var rows = new java.util.ArrayList<Map<String,Object>>();
        Map<String,Object> previous = null;
        for (var entry : source) {
            var state = new LinkedHashMap<>(entry); Object tick = state.remove("tick");
            Object nativeState = state.remove("native_client_evidence");
            if (nativeState instanceof Map<?,?> evidence) {
                var motion = new LinkedHashMap<String,Object>();
                for (String key : java.util.List.of("native_up","on_ground","native_pose","fall_distance","velocity_y"))
                    if (evidence.get(key) != null) motion.put(key,evidence.get(key));
                state.put("motion",Map.copyOf(motion));
            }
            if (state.equals(previous)) {
                var row = rows.getLast(); row.put("last_tick",tick); row.put("samples",((Number)row.get("samples")).intValue()+1);
            } else {
                var row = new LinkedHashMap<>(state); row.put("first_tick",tick); row.put("last_tick",tick); row.put("samples",1);
                rows.add(row); previous = state;
                if (rows.size()>8) rows.removeFirst();
            }
        }
        return rows.stream().map(Map::copyOf).toList();
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
