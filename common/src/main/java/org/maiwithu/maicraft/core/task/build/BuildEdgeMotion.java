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
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 已有安全前缀之后的小段原生挪位：完整支撑优先站立，真实临边或低顶才潜行；不寻路、不改地形或身体位置。 */
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
    private boolean released, observedSneak, alignment, requestedSneak = true;
    private String postureReason = "partial_support_edge";
    private Map<String, Object> geometryCheck = Map.of();

    public BuildEdgeMotion(Vec3 approachFeet, Vec3 edgeFeet, LongSet forbidden, Predicate<BlockPos> permittedBody) {
        if (!finite(approachFeet) || !finite(edgeFeet) || Math.abs(approachFeet.y - edgeFeet.y) > 1e-5
                || approachFeet.distanceToSqr(edgeFeet) > MAX_SEGMENT * MAX_SEGMENT + 1e-8)
            throw new IllegalArgumentException("edge motion needs one proven same-height segment of at most 0.7 blocks");
        approach = approachFeet; target = edgeFeet;
        this.forbidden = Objects.requireNonNull(forbidden); this.permittedBody = Objects.requireNonNull(permittedBody);
    }

    /** 图节点已到而身体还在楼梯矮半阶时，只用原生慢走补齐至真实锚点，不继承水平檐边的高度假定。 */
    public static BuildEdgeMotion alignAt(Vec3 anchor, LongSet forbidden, Predicate<BlockPos> permittedBody) {
        var result = new BuildEdgeMotion(anchor, anchor, forbidden, permittedBody);
        result.alignment = true; return result;
    }

    static boolean canStandAt(LocalPlayer player, LongSet forbidden, Predicate<BlockPos> permittedBody) {
        // 复用移动原语的完整保护格、原生实体与动态结构检查；只验证，不申请输入或启动一次新的运动。
        Vec3 at = player.position();
        return BuildFootprintSupport.complete(player.level(), player.level()::isLoaded, player.getBbWidth(), at, at, at)
                && alignAt(at, forbidden, permittedBody).safe(player, at, at, at, player.getDimensions(Pose.STANDING).height());
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
        if (!bounded(observed)) return fail(context, "edge_left_proven_segment");
        if (status != Status.ARRIVED && context.tickRevision() - startedTick > 100) return fail(context, "edge_motion_timeout");
        Vec3 drift = observed.add(velocity.x * 3, 0, velocity.z * 3);
        // 普通锚点先证明完整脚底和站立净空；确有窄边或低顶才申请潜行，不能把所有精确对齐都变成蹲走。
        boolean fullSupport = alignment && BuildFootprintSupport.complete(player.level(), player.level()::isLoaded,
                player.getBbWidth(), observed, target, drift);
        boolean standingSafe = fullSupport && safe(player, observed, target, drift, player.getDimensions(Pose.STANDING).height());
        requestedSneak = !standingSafe;
        postureReason = !alignment || !fullSupport ? "partial_support_edge" : standingSafe ? "full_support_standing" : "low_clearance_crouch";
        double requiredHeight = player.getDimensions(requestedSneak ? Pose.CROUCHING : Pose.STANDING).height();
        if (!bounded(drift)) {
            // 惯性会把身体带出已证明的短段时保留这项具体原因，不误报成需要再垫一层方块。
            geometryCheck = Map.of("reason", "projected_drift_outside_proven_segment");
            return fail(context, "edge_support_or_sweep_changed");
        }
        if (!standingSafe && !safe(player, observed, target, drift, requiredHeight)) return fail(context, "edge_support_or_sweep_changed");
        // 姿态必须由原生玩家刻兑现后才移动；普通重力 vy=-0.0784 不算漂浮，停止输入仍靠真实摩擦减速。
        boolean postureReady = requestedSneak ? observedSneak : !player.isShiftKeyDown() && player.getPose() == Pose.STANDING;
        if (!postureReady || horizontal(velocity) > .08) {
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
                || !bounded(at) || !bounded(drift) || !safe(player, at, at, drift, player.getBbHeight()))
            fail(context, "edge_hold_support_changed");
        command(context, Vec3.ZERO, 0);
        return status;
    }
    public void stop(LocalPlayer player) {
        LocalPlayerContext context = context(player);
        // 主人暂停或取消后本控制器不再续 Shift；运动中的支撑失败仍由 fail 停住，只有任务边界显式释放。
        if (context != null && owns(context) && !released) {
            status = Status.FAILED; if (failure.isEmpty()) failure = "edge_motion_stopped";
            released = true; context.body().applyMovement(BodyControlPort.Movement.STOPPED, context.tickRevision());
        }
    }
    /** 只有调用方确认离开边缘或脚下已补成可靠平台时才显式放开；此后本对象不再续任何输入。 */
    public void release(LocalPlayer player) {
        LocalPlayerContext context = context(player);
        released = true;
        if (context != null && owns(context)) context.body().applyMovement(BodyControlPort.Movement.STOPPED, context.tickRevision());
        if (status == Status.RUNNING) { status = Status.FAILED; failure = "edge_motion_released"; }
    }
    public String failure() { return failure; }
    public boolean requiresSneak() { return requestedSneak; }
    public String postureReason() { return postureReason; }
    public Map<String, Object> evidence() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", status.name().toLowerCase(Locale.ROOT)); out.put("failure", failure);
        out.put("alignment", alignment);
        out.put("requested_sneak", requestedSneak); out.put("posture_reason", postureReason);
        out.put("approach", coordinates(approach)); out.put("target", coordinates(target));
        if (observed != null) out.put("actual_feet", coordinates(observed));
        out.put("velocity", coordinates(velocity)); out.put("observed_sneak", observedSneak);
        out.put("stable_ticks", stableTicks); out.put("elapsed_ticks", owner == null ? 0 : Math.max(0, lastTick - startedTick));
        out.put("released", released);
        if (!geometryCheck.isEmpty()) out.put("geometry_check", geometryCheck);
        return Map.copyOf(out);
    }

    private boolean safe(LocalPlayer player, Vec3 from, Vec3 to, Vec3 drift, double height) {
        try {
            var world = player.level(); double width = player.getBbWidth();
            if (!dimensions(width, height)) { geometryCheck = Map.of("reason", "invalid_body_dimensions"); return false; }
            var physical = PhysicalObstacleSnapshot.capture(player.clientLevel, from);
            if (!Set.of("not_installed", "ready", "ready_empty").contains(physical.state())) {
                geometryCheck = Map.of("reason", "physical_observation_unavailable", "state", physical.state()); return false;
            }
            var boxes = new ArrayList<>(physical.boxes());
            AABB sweep = body(from, width, height).minmax(body(to, width, height)).minmax(body(drift, width, height));
            for (var shape : world.getEntityCollisions(player, sweep)) {
                boxes.addAll(shape.toAabbs());
                if (boxes.size() > 256) { geometryCheck = Map.of("reason", "entity_collision_budget_exceeded"); return false; }
            }
            var dynamic = new PhysicalObstacleSnapshot(boxes, physical.blockReads(), physical.conservativeStructures(), physical.state());
            var hard = new LongOpenHashSet(forbidden); hard.addAll(NavigationSafetyContext.forbiddenBodyCells());
            Predicate<BlockPos> loaded = pos -> world.isLoaded(pos) && world.getWorldBorder().isWithinBounds(pos);
            if (alignment) {
                // 搜索已找到可达站位后，具体的末段拒绝仍应交回施工回执，供角色选择另一个真实站位。
                var checked = BuildAnchorStepGeometry.check(player, loaded, hard, permittedBody, dynamic, from, to, drift, height);
                geometryCheck = checked.evidence(); return checked.allowed();
            }
            boolean safe = safeSweep(world, loaded, width, height, hard, permittedBody, dynamic, from, to)
                    && safeSweep(world, loaded, width, height, hard, permittedBody, dynamic, from, drift);
            geometryCheck = Map.of("reason", safe ? "verified" : "edge_support_or_collision_unverified"); return safe;
        } catch (RuntimeException | LinkageError unavailable) {
            geometryCheck = Map.of("reason", "geometry_observation_failed", "exception", unavailable.getClass().getSimpleName()); return false;
        }
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
        return steering(direction, strength, yaw, true);
    }
    // 普通对齐和临边共用相机相对方向，区别只在本刻已证明需要的姿态，不把后退或斜移改成抢镜头前进。
    static BodyControlPort.Movement steering(Vec3 direction, float strength, float yaw, boolean sneak) {
        double angle = Math.toRadians(yaw);
        return new BodyControlPort.Movement((float) (-direction.x * Math.sin(angle) + direction.z * Math.cos(angle)) * strength,
                (float) (direction.x * Math.cos(angle) + direction.z * Math.sin(angle)) * strength, false, sneak, false);
    }
    private void command(LocalPlayerContext context, Vec3 direction, float input) {
        commanded = direction; strength = input;
        if (context.player().isInWater() || context.player().isPassenger()) {
            context.body().applyMovement(BodyControlPort.Movement.STOPPED, context.tickRevision()); return;
        }
        if (!Float.isFinite(context.player().getYRot())) {
            context.body().applyMovement(new BodyControlPort.Movement(0, 0, false, requestedSneak, false), context.tickRevision()); return;
        }
        // 随真实平滑相机的 yaw 重算前后／侧移，不抢镜头，也不写入位置或速度来“贴”到目标。
        context.body().applySteering(yaw -> steering(direction, input, yaw, requestedSneak), context.player().getYRot(), context.tickRevision());
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
    private boolean bounded(Vec3 at) {
        if (!alignment) return inside(entry, target, at);
        if (!finite(at) || target.y < entry.y - 1e-5 || target.y - entry.y > .5 + 1e-5
                || at.y < entry.y - 1e-5 || at.y > target.y + 1e-5) return false;
        return inside(entry, new Vec3(target.x, entry.y, target.z), new Vec3(at.x, entry.y, at.z));
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
    private static List<Double> coordinates(Vec3 v) { return List.of(v.x, v.y, v.z); }
}
