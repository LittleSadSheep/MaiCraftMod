// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fish;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 抛竿弹道：按原版鱼钩的初速、重力与阻力逐刻模拟，折半求出抛到指定水面的俯仰角，
 * 并判断飞行途中的方块会不会挡住钩子。纯函数，不依赖任务与角色；
 * 常数（水面高度、阻力、重力、最多飞行步数）是实机验证过的游戏事实。
 */
final class CastTrajectory {

    /** 鱼钩落点相对水方块坐标的高度：原版浮标停在水面 0.85 格处。 */
    static final double WATER_SURFACE_OFFSET = 0.85;
    /** 鱼钩每刻速度按这个比例衰减。 */
    private static final double DRAG = 0.92;
    /** 鱼钩每刻先加上的下坠速度。 */
    private static final double GRAVITY = 0.03;
    /** 弹道模拟的最多步数；超过还没有抵达目标水平距离就当飞不到。 */
    static final int MAX_FLIGHT_TICKS = 80;
    /** 原版把浮标生成在玩家眼部前方 0.3 格处。 */
    private static final double HOOK_SPAWN_AHEAD = 0.3;
    /** 抛出初速的水平分量基数与瞄准方向分量。 */
    private static final double THROW_BASE_SPEED = 0.5;
    private static final double THROW_POWER = 0.6;

    /** 弹道上的碰撞查询：两个世界坐标之间会不会碰到方块；生产实现问客户端世界，测试换成画好的地图。 */
    @FunctionalInterface
    interface RayBlocks {
        boolean blocked(Vec3 from, Vec3 to);
    }

    /**
     * 从眼睛抛到一格水面的俯仰角：水平距离扣掉浮标前置量，解出到达水面的角度。
     * 眼睛与落点几乎同列时按原地正下方处理。
     */
    static double pitchDegrees(Vec3 eye, BlockPos landing) {
        double dx = landing.getX() + 0.5 - eye.x;
        double dz = landing.getZ() + 0.5 - eye.z;
        double distance = Math.max(0.1, Math.sqrt(dx * dx + dz * dz) - HOOK_SPAWN_AHEAD);
        return pitchFor(distance, landing.getY() + WATER_SURFACE_OFFSET - eye.y);
    }

    // 在向上 45° 到向下 55° 之间反复折半试角度，看模拟飞到目标距离时是偏高还是偏低。
    static double pitchFor(double horizontalDistance, double targetHeight) {
        double low = -45.0;
        double high = 55.0;
        for (int i = 0; i < 32; i++) {
            double mid = (low + high) * 0.5;
            double height = heightAt(horizontalDistance, mid);
            if (height > targetHeight) {
                low = mid;  // 模拟轨迹高于目标水面时，继续增大下俯角。
            } else {
                high = mid;
            }
        }
        return (low + high) * 0.5;
    }

    // 从初速度开始，每步先加下坠再按比例减速；抵达目标水平距离时用该步中的比例估算高度。
    static double heightAt(double horizontalDistance, double pitchDegrees) {
        double pitch = Math.toRadians(pitchDegrees);
        double horizontalVelocity = THROW_POWER * Math.cos(pitch) + THROW_BASE_SPEED;
        double verticalVelocity = -Math.tan(pitch) * horizontalVelocity;
        double travelled = 0.0;
        double height = 0.0;
        for (int tick = 0; tick < MAX_FLIGHT_TICKS; tick++) {
            verticalVelocity -= GRAVITY;
            double nextDistance = travelled + horizontalVelocity;
            double nextHeight = height + verticalVelocity;
            if (nextDistance >= horizontalDistance) {
                double fraction = (horizontalDistance - travelled) / horizontalVelocity;
                return height + verticalVelocity * fraction;
            }
            travelled = nextDistance;
            height = nextHeight;
            horizontalVelocity *= DRAG;
            verticalVelocity *= DRAG;
        }
        return Double.NEGATIVE_INFINITY;
    }

    /**
     * 按解出的俯仰角逐刻模拟抛到目标水面的整段飞行，任何一小段碰到方块就拒绝；
     * 沿途实体不查，钩住了在等咬钩的阶段处理。
     *
     * @param eye       眼睛位置，浮标从它前方 0.3 格处出发
     * @param landing   目标水面的那一格水
     * @param rays      方块碰撞查询
     */
    static boolean clearsTerrain(Vec3 eye, BlockPos landing, RayBlocks rays) {
        double tx = landing.getX() + 0.5;
        double tz = landing.getZ() + 0.5;
        double dx = tx - eye.x;
        double dz = tz - eye.z;
        double directDistance = Math.sqrt(dx * dx + dz * dz);
        if (directDistance < 1.0e-6) return false;
        double ux = dx / directDistance;
        double uz = dz / directDistance;
        Vec3 pos = eye.add(ux * HOOK_SPAWN_AHEAD, 0.0, uz * HOOK_SPAWN_AHEAD);
        double distance = Math.sqrt((tx - pos.x) * (tx - pos.x) + (tz - pos.z) * (tz - pos.z));
        double pitch = Math.toRadians(pitchFor(distance, landing.getY() + WATER_SURFACE_OFFSET - eye.y));
        double horizontalVelocity = THROW_POWER * Math.cos(pitch) + THROW_BASE_SPEED;
        double verticalVelocity = -Math.tan(pitch) * horizontalVelocity;
        double travelled = 0.0;

        for (int tick = 0; tick < MAX_FLIGHT_TICKS; tick++) {
            verticalVelocity -= GRAVITY;
            double fraction = Math.min(1.0, (distance - travelled) / horizontalVelocity);
            Vec3 next = pos.add(ux * horizontalVelocity * fraction,
                    verticalVelocity * fraction, uz * horizontalVelocity * fraction);
            if (rays.blocked(pos, next)) return false;
            travelled += horizontalVelocity * fraction;
            if (travelled >= distance - 1.0e-6) return true;
            pos = next;
            horizontalVelocity *= DRAG;
            verticalVelocity *= DRAG;
        }
        return false;
    }

    private CastTrajectory() {}
}
