// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** 已有安全前缀之后的最后小段潜行挪位；不寻路、不改地形、不把连续目标量化成空中格心。 */
public final class BuildEdgeMotion {
    public enum Status { RUNNING, ARRIVED, FAILED }
    private static final double MAX_SEGMENT = .7, ENTRY_RADIUS = .8, ARRIVAL_RADIUS = .035, STILL_SPEED = .012;
    private final Vec3 approach, target;
    private final LongSet forbidden;
    private final Predicate<BlockPos> permittedBody;
    private LocalPlayer owner;
    private Object level;
    private long bodyEpoch, controlRevision, startedTick, lastTick = Long.MIN_VALUE;
    private Vec3 entry, previous, observed, velocity = Vec3.ZERO, commanded = Vec3.ZERO;
    private float strength;
    private int stableTicks;
    private Status status = Status.RUNNING;
    private String failure = "";
    private boolean released, observedSneak;

    public BuildEdgeMotion(Vec3 approachFeet, Vec3 edgeFeet, LongSet forbidden, Predicate<BlockPos> permittedBody) {
        if (!finite(approachFeet) || !finite(edgeFeet) || Math.abs(approachFeet.y - edgeFeet.y) > 1e-5
                || approachFeet.distanceToSqr(edgeFeet) > MAX_SEGMENT * MAX_SEGMENT + 1e-8)
            throw new IllegalArgumentException("edge motion needs one proven same-height segment of at most 0.7 blocks");
        approach = approachFeet; target = edgeFeet;
        this.forbidden = Objects.requireNonNull(forbidden); this.permittedBody = Objects.requireNonNull(permittedBody);
    }

    public Status tick(LocalPlayer player) {
        if (released) return status;
        LocalPlayerContext context = context(player);
        if (context == null) return fail(null, "edge_control_unavailable");
        if (owner == null) {
            owner = player; level = player.level(); bodyEpoch = context.bodyEpoch(); controlRevision = context.controlRevision();
            startedTick = context.tickRevision(); entry = player.position();
            if (entry.distanceToSqr(approach) > ENTRY_RADIUS * ENTRY_RADIUS || entry.distanceToSqr(target) > 2.25)
                return fail(context, "edge_start_outside_proven_approach");
        }
        if (!owns(context)) return fail(null, "edge_body_or_control_changed");
        if (status == Status.FAILED) { command(context, Vec3.ZERO, 0); return status; }
        if (lastTick == context.tickRevision()) { command(context, commanded, strength); return status; }
        lastTick = context.tickRevision(); observed = player.position(); velocity = player.getDeltaMovement();
        observedSneak = player.isShiftKeyDown() && player.getPose() == Pose.CROUCHING;
        if (context.minecraft().screen != null || !player.onGround() || player.isInWater() || player.isPassenger()
                || player.getPose() != Pose.STANDING && player.getPose() != Pose.CROUCHING || !finite(velocity))
            return fail(context, "edge_body_not_grounded_dry_and_available");
        if (!inside(entry, target, observed)) return fail(context, "edge_left_proven_segment");
        if (status != Status.ARRIVED && context.tickRevision() - startedTick > 100) return fail(context, "edge_motion_timeout");
        Vec3 drift = observed.add(velocity.x * 3, 0, velocity.z * 3);
        if (!inside(entry, target, drift) || !safe(player, observed, target, drift)) return fail(context, "edge_support_or_sweep_changed");
        // 先等原生姿态真的蹲下；停止水平输入靠游戏摩擦自然减速，常规落地重力 vy=-0.0784 不算漂浮。
        if (!observedSneak || horizontal(velocity) > .08) {
            stableTicks = 0; previous = observed; command(context, Vec3.ZERO, 0); return status;
        }
        if (arrived(observed, target, velocity, previous)) {
            stableTicks++; command(context, Vec3.ZERO, 0);
            if (stableTicks >= 3) status = Status.ARRIVED;
        } else {
            stableTicks = 0;
            if (status == Status.ARRIVED) return fail(context, "edge_stance_moved_after_arrival");
            Vec3 toward = target.subtract(observed).multiply(1, 0, 1);
            // BotInput 已经是实际移动幅值，不会再走键盘潜行减速；末段在这里直接限定慢速。
            float input = toward.lengthSqr() <= ARRIVAL_RADIUS * ARRIVAL_RADIUS ? 0 : (float) Math.clamp(toward.length() * 2, .1, .2);
            command(context, toward.normalize(), input);
        }
        previous = observed;
        return status;
    }

    /** 到位后瞄准／点击期间由调用方每刻最后续潜行；不重新选目标或替后续导航挪动身体。 */
    public Status hold(LocalPlayer player) {
        LocalPlayerContext context = context(player);
        if (released || context == null || !owns(context)) return status;
        Vec3 at = player.position(), speed = player.getDeltaMovement();
        Vec3 drift = at.add(speed.x * 3, 0, speed.z * 3);
        if (!player.onGround() || player.isInWater() || player.isPassenger() || !finite(speed)
                || !inside(entry, target, at) || !inside(entry, target, drift) || !safe(player, at, at, drift))
            fail(context, "edge_hold_support_changed");
        command(context, Vec3.ZERO, 0);
        return status;
    }
    public void stop(LocalPlayer player) {
        LocalPlayerContext context = context(player);
        if (context != null && owns(context) && !released) fail(context, "edge_motion_stopped");
    }
    /** 只有调用方确认离开边缘或脚下已补成可靠平台时才显式放开；此后本对象不再续任何输入。 */
    public void release(LocalPlayer player) {
        LocalPlayerContext context = context(player);
        released = true;
        if (context != null && owns(context)) context.body().applyMovement(BodyControlPort.Movement.STOPPED, context.tickRevision());
        if (status == Status.RUNNING) { status = Status.FAILED; failure = "edge_motion_released"; }
    }
    public String failure() { return failure; }
    public Map<String, Object> evidence() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", status.name().toLowerCase(java.util.Locale.ROOT)); out.put("failure", failure);
        out.put("approach", coordinates(approach)); out.put("target", coordinates(target));
        if (observed != null) out.put("actual_feet", coordinates(observed));
        out.put("velocity", coordinates(velocity)); out.put("observed_sneak", observedSneak);
        out.put("stable_ticks", stableTicks); out.put("elapsed_ticks", owner == null ? 0 : Math.max(0, lastTick - startedTick));
        out.put("released", released); return Map.copyOf(out);
    }

    private boolean safe(LocalPlayer player, Vec3 from, Vec3 to, Vec3 drift) {
        try {
            var world = player.level(); double width = player.getBbWidth(), height = player.getBbHeight();
            if (!dimensions(width, height)) return false;
            var physical = PhysicalObstacleSnapshot.capture(player.clientLevel, from);
            if (!java.util.Set.of("not_installed", "ready", "ready_empty").contains(physical.state())) return false;
            var boxes = new ArrayList<>(physical.boxes());
            AABB sweep = body(from, width, height).minmax(body(to, width, height)).minmax(body(drift, width, height));
            for (var shape : world.getEntityCollisions(player, sweep)) {
                boxes.addAll(shape.toAabbs()); if (boxes.size() > 256) return false;
            }
            var dynamic = new PhysicalObstacleSnapshot(boxes, physical.blockReads(), physical.conservativeStructures(), physical.state());
            var hard = new LongOpenHashSet(forbidden); hard.addAll(NavigationSafetyContext.forbiddenBodyCells());
            Predicate<BlockPos> loaded = pos -> world.isLoaded(pos) && world.getWorldBorder().isWithinBounds(pos);
            return safeSweep(world, loaded, width, height, hard, permittedBody, dynamic, from, to)
                    && safeSweep(world, loaded, width, height, hard, permittedBody, dynamic, from, drift);
        } catch (RuntimeException | LinkageError unavailable) { return false; }
    }
    // GroundCorridor 按解析区间证明全程支撑，能接受真实的窄边接触，但任何中间缺口、撞身或危险格都拒绝。
    static boolean safeSweep(BlockGetter world, Predicate<BlockPos> loaded, double width, double height, LongSet forbidden,
                             Predicate<BlockPos> permitted, PhysicalObstacleSnapshot physical, Vec3 from, Vec3 to) {
        if (!finite(from) || !finite(to) || !dimensions(width, height) || from.distanceToSqr(to) > 2.25 || Math.abs(from.y - to.y) > 1e-5) return false;
        var blocked = new LongOpenHashSet(forbidden); AABB sweep = body(from, width, height).minmax(body(to, width, height));
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(sweep.minX, sweep.minY, sweep.minZ),
                BlockPos.containing(Math.nextDown(sweep.maxX), Math.nextDown(sweep.maxY), Math.nextDown(sweep.maxZ))))
            if (!permitted.test(pos)) blocked.add(pos.asLong());
        var corridor = new GroundCorridor(world, loaded, width, height, blocked, physical);
        return corridor.clear(from, from) && corridor.clear(from, to);
    }
    static boolean arrived(Vec3 at, Vec3 goal, Vec3 speed, Vec3 previous) {
        return previous != null && at.distanceToSqr(goal) <= ARRIVAL_RADIUS * ARRIVAL_RADIUS
                && horizontal(speed) <= STILL_SPEED && horizontal(at.subtract(previous)) <= STILL_SPEED;
    }
    static BodyControlPort.Movement steering(Vec3 direction, float strength, float yaw) {
        double angle = Math.toRadians(yaw);
        return new BodyControlPort.Movement((float) (-direction.x * Math.sin(angle) + direction.z * Math.cos(angle)) * strength,
                (float) (direction.x * Math.cos(angle) + direction.z * Math.sin(angle)) * strength, false, true, false);
    }
    private void command(LocalPlayerContext context, Vec3 direction, float input) {
        commanded = direction; strength = input;
        if (context.player().isInWater() || context.player().isPassenger()) {
            context.body().applyMovement(BodyControlPort.Movement.STOPPED, context.tickRevision()); return;
        }
        if (!Float.isFinite(context.player().getYRot())) {
            context.body().applyMovement(new BodyControlPort.Movement(0, 0, false, true, false), context.tickRevision()); return;
        }
        // 随真实平滑相机的 yaw 重算前后／侧移，不抢镜头，也不写入位置或速度来“贴”到目标。
        context.body().applySteering(yaw -> steering(direction, input, yaw), context.player().getYRot(), context.tickRevision());
    }
    private Status fail(LocalPlayerContext context, String reason) {
        status = Status.FAILED; if (failure.isEmpty()) failure = reason;
        if (context != null && owns(context)) command(context, Vec3.ZERO, 0); return status;
    }
    private boolean owns(LocalPlayerContext context) {
        return context.player() == owner && owner.level() == level && context.bodyEpoch() == bodyEpoch && context.controlRevision() == controlRevision;
    }
    private static LocalPlayerContext context(LocalPlayer player) {
        return ClientRuntime.actor().activeContext().filter(c -> c.player() == player && c.isCurrent() && c.body().automationOwnsControls()).orElse(null);
    }
    private static boolean inside(Vec3 from, Vec3 to, Vec3 at) {
        if (!finite(at) || Math.abs(at.y - from.y) > 1e-5) return false;
        Vec3 delta = to.subtract(from); double length = delta.length();
        if (length < 1e-7) return at.distanceToSqr(to) <= .08 * .08;
        double along = at.subtract(from).dot(delta) / length;
        Vec3 closest = from.add(delta.scale(Math.clamp(along / length, 0, 1)));
        return along >= -.05 && along <= length + .05 && at.distanceToSqr(closest) <= .08 * .08;
    }
    private static AABB body(Vec3 at, double width, double height) { return new AABB(at.x - width / 2, at.y, at.z - width / 2, at.x + width / 2, at.y + height, at.z + width / 2); }
    private static double horizontal(Vec3 v) { return Math.sqrt(v.x * v.x + v.z * v.z); }
    private static boolean dimensions(double width, double height) { return Double.isFinite(width + height) && width > 0 && width <= 2 && height > 0 && height <= 4; }
    private static boolean finite(Vec3 v) { return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }
    private static java.util.List<Double> coordinates(Vec3 v) { return java.util.List.of(v.x, v.y, v.z); }
}
