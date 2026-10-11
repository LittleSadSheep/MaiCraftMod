// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleUnaryOperator;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 箭往哪儿瞄：算上箭的下坠、空气减速和目标正在走的方向，先按公式猜几个发射方向，
 * 再逐刻模拟飞行，中途撞墙、撞到别的生物的方向不要，剩下的挑离目标身体中心近、少转头、飞得快的那个。
 * 算出来的是"照眼前的世界这样射能中"的预测；真射没射出去、中没中，由射箭的动作和战斗任务按事实认。
 * 一个方向都找不到就说没有弹道——不凭估计盲射。
 */
public final class ArrowAim {

    /** 瞄的结果：往哪儿看（转头用）、发射方向、估计飞几刻。 */
    public record Aim(Vec3 lookPoint, Vec3 direction, double travelTicks) {}

    /** 原版箭每刻下坠与减速。 */
    static final double GRAVITY = 0.05;
    static final double DRAG = 0.99;
    /** 原版判箭打中实体时把对方的碰撞箱放大这么多。 */
    static final double HIT_INFLATE = 0.3;
    /** 弓拉满时箭的初速（格每刻），弩是 3.15。 */
    public static final double BOW_FULL_SPEED = 3.0;
    public static final double CROSSBOW_SPEED = 3.15;
    /** 箭从眼睛下方这么多发出。 */
    private static final double EYE_OFFSET = 0.1;
    private static final int MAX_FLIGHT_TICKS = 80;
    private static final double EPS = 1.0e-6;

    private ArrowAim() {}

    /** 弓拉了几刻射出去多快：原版拉满（20 刻）是每刻 3 格，拉得越短越慢。 */
    public static double bowSpeed(int drawTicks) {
        double seconds = drawTicks / 20.0;
        double power = Math.min(1.0, Math.max(0.0, (seconds * seconds + seconds * 2.0) / 3.0));
        return BOW_FULL_SPEED * power;
    }

    /**
     * 找一条打得中的弹道。
     *
     * @param speed           箭的初速（弓按拉了几刻算，弩固定）
     * @param inheritsMotion  箭带不带上射手自己的速度：弓带，弩不带
     * @param maxRange        比这远就不算
     */
    public static Optional<Aim> find(Level level, Entity shooter, Entity target, double speed,
            boolean inheritsMotion, double maxRange) {
        if (speed <= EPS || target.isRemoved()) return Optional.empty();
        Vec3 start = new Vec3(shooter.getX(), shooter.getEyeY() - EYE_OFFSET, shooter.getZ());
        AABB box = target.getBoundingBox();
        if (start.distanceToSqr(box.getCenter()) > maxRange * maxRange) return Optional.empty();
        Vec3 look = shooter.getViewVector(1.0f).normalize();
        // 目标按它此刻的速度往前走，不猜它转弯、起跳和被击退。
        Vec3 targetMotion = target.getDeltaMovement();
        Vec3 shooterMotion = inheritsMotion
                ? new Vec3(shooter.getDeltaMovement().x, shooter.onGround() ? 0 : shooter.getDeltaMovement().y,
                        shooter.getDeltaMovement().z)
                : Vec3.ZERO;
        List<Vec3> candidates = new ArrayList<>();
        dragAwareCandidates(candidates, start, box, targetMotion, speed);
        for (Vec3 point : samples(box.move(targetMotion.scale(Math.min(30, start.distanceTo(box.getCenter()) / speed))))) {
            add(candidates, lowArc(start, point, speed));
        }
        candidates.sort(Comparator.comparingDouble(direction -> angleDegrees(look, direction)));
        Aim best = null;
        double bestScore = Double.MAX_VALUE;
        Vec3 eye = shooter.getEyePosition();
        for (Vec3 direction : candidates) {
            Flight hit = fly(level, shooter, target, targetMotion, start, direction.scale(speed).add(shooterMotion));
            if (hit == null) continue;
            // 打得中的方向里，偏好离身体中心近、少转头、飞得快的。
            double score = hit.offCenter + angleDegrees(look, direction) * 0.03 + hit.ticks * 0.01;
            if (score < bestScore) {
                bestScore = score;
                best = new Aim(eye.add(direction.scale(64.0)), direction, hit.ticks);
            }
        }
        return Optional.ofNullable(best);
    }

    /** 两个方向的夹角（度）；有一个是零向量时给最大值。 */
    public static double angleDegrees(Vec3 a, Vec3 b) {
        if (a == null || b == null || a.lengthSqr() < EPS || b.lengthSqr() < EPS) return Double.MAX_VALUE;
        double dot = Math.max(-1.0, Math.min(1.0, a.normalize().dot(b.normalize())));
        return Math.toDegrees(Math.acos(dot));
    }

    /** 不计空气减速的低弧线：这个速度下够不着就给 null。 */
    static Vec3 lowArc(Vec3 start, Vec3 target, double speed) {
        double dx = target.x - start.x;
        double dz = target.z - start.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < EPS) return target.subtract(start).normalize();
        double y = target.y - start.y;
        double speedSquared = speed * speed;
        double root = speedSquared * speedSquared - GRAVITY * (GRAVITY * horizontal * horizontal + 2.0 * y * speedSquared);
        if (root < 0.0) return null;
        double tan = (speedSquared - Math.sqrt(root)) / (GRAVITY * horizontal);
        Vec3 direction = new Vec3(dx, tan * horizontal, dz);
        return direction.lengthSqr() < EPS ? null : direction.normalize();
    }

    /**
     * 假定箭飞 ticks 刻正好到达 target，倒推出发方向（把每刻减速和下坠都算进去）；
     * 结果的长度接近 1 才说明这个速度真能这样飞到。
     */
    static Vec3 directionByTime(Vec3 start, Vec3 target, double ticks, double speed) {
        if (ticks <= EPS || speed <= EPS) return null;
        double decay = Math.pow(DRAG, ticks);
        double resistance = DRAG - 1.0;
        double distanceTerm = decay - 1.0;
        if (Math.abs(distanceTerm) < EPS) return null;
        double horizontal = resistance / (speed * distanceTerm);
        double fall = GRAVITY * (decay - DRAG * ticks + ticks - 1.0) / (speed * resistance * distanceTerm);
        Vec3 direction = new Vec3((target.x - start.x) * horizontal, (target.y - start.y) * horizontal + fall,
                (target.z - start.z) * horizontal);
        return Double.isFinite(direction.x) && Double.isFinite(direction.y) && Double.isFinite(direction.z)
                ? direction : null;
    }

    // 先找让"倒推方向长度正好为 1"的飞行时间，再对目标那时的中心和身体几处各算一个方向。
    private static void dragAwareCandidates(List<Vec3> out, Vec3 start, AABB box, Vec3 targetMotion, double speed) {
        Vec3 center = box.getCenter();
        double longest = Math.max(1.0, Math.min(MAX_FLIGHT_TICKS, start.distanceTo(center) / speed * 1.75));
        DoubleUnaryOperator error = ticks -> {
            Vec3 direction = directionByTime(start, center.add(targetMotion.scale(ticks)), ticks, speed);
            return direction == null ? Double.MAX_VALUE : Math.abs(direction.length() - 1.0);
        };
        double ticks = minimum(0.05, longest, error);
        if (error.applyAsDouble(ticks) > 0.1) return;
        AABB then = box.move(targetMotion.scale(ticks));
        for (double flight : new double[] {ticks, Math.max(1.0, Math.rint(ticks))}) {
            for (Vec3 point : samples(then)) {
                Vec3 direction = directionByTime(start, point, flight, speed);
                if (direction != null && Math.abs(direction.length() - 1.0) <= 0.15) add(out, direction);
            }
        }
    }

    // 三分法找误差最小的飞行时间。
    private static double minimum(double from, double to, DoubleUnaryOperator function) {
        double lower = from;
        double upper = to;
        while (upper - lower > 1.0e-4) {
            double mid = (lower + upper) * 0.5;
            if (function.applyAsDouble((lower + mid) * 0.5) < function.applyAsDouble((mid + upper) * 0.5)) upper = mid;
            else lower = mid;
        }
        return (lower + upper) * 0.5;
    }

    private record Flight(double ticks, double offCenter) {}

    // 逐刻飞：这一小段先碰到目标就算中；先碰到方块或别的生物就不要这个方向。
    private static Flight fly(Level level, Entity shooter, Entity target, Vec3 targetMotion, Vec3 start, Vec3 velocity) {
        Vec3 position = start;
        Vec3 motion = velocity;
        for (int tick = 1; tick <= MAX_FLIGHT_TICKS; tick++) {
            Vec3 next = position.add(motion);
            AABB body = target.getBoundingBox().move(targetMotion.scale(tick));
            Vec3 from = position;
            Optional<Vec3> onTarget = body.inflate(HIT_INFLATE).clip(from, next);
            double toTarget = onTarget.map(hit -> hit.distanceToSqr(from)).orElse(Double.MAX_VALUE);
            BlockHitResult wall = level.clip(new ClipContext(from, next, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, shooter));
            double toWall = wall.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : wall.getLocation().distanceToSqr(from);
            double toOther = otherEntity(shooter, target, from, next);
            if (onTarget.isPresent() && toTarget <= toWall + EPS && toTarget <= toOther + EPS) {
                return new Flight(tick, distanceToSegment(body.getCenter(), from, next));
            }
            if (toWall < Double.MAX_VALUE || toOther < Double.MAX_VALUE) return null;
            position = next;
            // 飞完这一刻先按比例减速，再往下掉一点。
            motion = motion.scale(DRAG).add(0.0, -GRAVITY, 0.0);
        }
        return null;
    }

    // 这一小段上有没有别的活着、能被碰到的生物挡箭（自己和目标不算）。
    private static double otherEntity(Entity shooter, Entity target, Vec3 from, Vec3 to) {
        AABB sweep = new AABB(from, from).expandTowards(to.subtract(from)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(shooter, from, to, sweep,
                entity -> entity != shooter && entity != target && !entity.isSpectator() && entity.isPickable()
                        && entity.isAlive(), Double.MAX_VALUE);
        return hit == null ? Double.MAX_VALUE : hit.getLocation().distanceToSqr(from);
    }

    private static double distanceToSegment(Vec3 point, Vec3 from, Vec3 to) {
        Vec3 segment = to.subtract(from);
        double length = segment.lengthSqr();
        if (length < EPS) return point.distanceTo(from);
        double t = Math.max(0.0, Math.min(1.0, point.subtract(from).dot(segment) / length));
        return point.distanceTo(from.add(segment.scale(t)));
    }

    // 身体上取几处瞄点：中心，以及每个方向靠近两边往里收一点的位置。
    private static List<Vec3> samples(AABB box) {
        List<Vec3> out = new ArrayList<>();
        for (double x : axis(box.minX, box.maxX)) {
            for (double y : axis(box.minY, box.maxY)) {
                for (double z : axis(box.minZ, box.maxZ)) {
                    out.add(new Vec3(x, y, z));
                }
            }
        }
        return out;
    }

    private static double[] axis(double min, double max) {
        double size = max - min;
        if (size <= 0.2) return new double[] {(min + max) * 0.5};
        double inset = Math.min(0.15, size * 0.25);
        return new double[] {(min + max) * 0.5, min + inset, max - inset};
    }

    // 去掉无效的和几乎一样的方向（夹角不到 0.05 度）。
    private static void add(List<Vec3> out, Vec3 direction) {
        if (direction == null || direction.lengthSqr() < EPS) return;
        Vec3 normalized = direction.normalize();
        for (Vec3 existing : out) {
            if (angleDegrees(existing, normalized) < 0.05) return;
        }
        out.add(normalized);
    }
}
