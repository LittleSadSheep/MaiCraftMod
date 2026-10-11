// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fish;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 抛竿弹道解算：按原版鱼钩的初速、重力与阻力逐刻模拟，折半求出抛到指定水面的俯仰角。
 * 纯函数，不依赖任务与角色；常量是实机验证过的游戏事实。（按移植清单原样取回，只改 package 与 import。）
 */
final class CastTrajectory {

    private static final double WATER_SURFACE_OFFSET = 0.85;

    private static final double FISHING_DRAG = 0.92;
    private static final double FISHING_GRAVITY = 0.03;
    private static final int MAX_FLIGHT_TICKS = 80;

    private static double castPitchDegrees(Vec3 eye, BlockPos target) {
        double dx = target.getX() + 0.5 - eye.x;
        double dz = target.getZ() + 0.5 - eye.z;
        double distance = Math.max(0.1, Math.sqrt(dx * dx + dz * dz) - 0.3);
        return solvePitchDegrees(distance, waterSurfaceY(target) - eye.y);
    }

    // 在向上 45° 到向下 55° 之间反复折半试角度，看模拟飞到目标距离时是偏高还是偏低。
    static double solvePitchDegrees(double horizontalDistance, double targetHeight) {
        double low = -45.0;
        double high = 55.0;
        for (int i = 0; i < 32; i++) {
            double mid = (low + high) * 0.5;
            double height = trajectoryHeightAtDistance(horizontalDistance, mid);
            if (height > targetHeight) {
                low = mid;  // 模拟轨迹高于目标水面时，继续增大下俯角。
            } else {
                high = mid;
            }
        }
        return (low + high) * 0.5;
    }

    // 从初速度开始，每步先加下坠再按比例减速；抵达目标水平距离时用该步中的比例估算高度。
    static double trajectoryHeightAtDistance(double horizontalDistance, double pitchDegrees) {
        double pitch = Math.toRadians(pitchDegrees);
        double horizontalVelocity = 0.6 * Math.cos(pitch) + 0.5;
        double verticalVelocity = -Math.tan(pitch) * horizontalVelocity;
        double travelled = 0.0;
        double height = 0.0;
        for (int tick = 0; tick < MAX_FLIGHT_TICKS; tick++) {
            verticalVelocity -= FISHING_GRAVITY;
            double nextDistance = travelled + horizontalVelocity;
            double nextHeight = height + verticalVelocity;
            if (nextDistance >= horizontalDistance) {
                double fraction = (horizontalDistance - travelled) / horizontalVelocity;
                return height + verticalVelocity * fraction;
            }
            travelled = nextDistance;
            height = nextHeight;
            horizontalVelocity *= FISHING_DRAG;
            verticalVelocity *= FISHING_DRAG;
        }
        return Double.NEGATIVE_INFINITY;
    }

    private static double waterSurfaceY(BlockPos target) {
        return target.getY() + WATER_SURFACE_OFFSET;
    }

    private CastTrajectory() {}
}
