// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;

/**
 * 根据目标距离和现有速度决定前后左右按键：靠近时减速，已经冲过头时反向刹车；需要更高位置时才请求上升。
 */
final class JetpackSteering {
    static BodyControlPort.Movement toward(Vec3 position, Vec3 velocity, Vec3 target, float yaw, boolean landing,
                                          JetpackNativeAdapter.Snapshot power) {
        return toward(position, velocity, target, yaw, landing, power, false);
    }

    // 仅在身体允许疾跑且远离降落点时加速；巡航与制动都计入原版空中输入，避免人为压低背包速度。
    static BodyControlPort.Movement toward(Vec3 position, Vec3 velocity, Vec3 target, float yaw, boolean landing,
                                          JetpackNativeAdapter.Snapshot power, boolean allowSprint) {
        Vec3 error = target.subtract(position);
        double length = Math.hypot(error.x, error.z);
        boolean cruise = allowSprint && !landing && length > 3;
        double braking = power.horizontal() * 0.8 + .02 * .98;
        double speed = Math.min((power.horizontal() * 1.2 + (cruise ? .026 : .02) * .98) / .09,
                Math.sqrt(2 * braking * length));
        double desiredX = length < 0.02 ? 0 : error.x / length * speed;
        double desiredZ = length < 0.02 ? 0 : error.z / length * speed;
        double x = desiredX - velocity.x, z = desiredZ - velocity.z;
        double radians = Math.toRadians(yaw), sin = Math.sin(radians), cos = Math.cos(radians);
        float forward = pulse(-x * sin + z * cos), strafe = pulse(x * cos + z * sin);
        boolean up = !landing && JetpackDynamics.shouldRise(position.y, velocity.y, target.y, power);
        // 原版只有前进输入能疾跑；转向刹车和落点对齐都撤销疾跑，下降交给专门的开关制动流程。
        return new BodyControlPort.Movement(forward, strafe, up, false, cruise && forward > 0);
    }
    private static float pulse(double acceleration) { return acceleration > 0.015 ? 1 : acceleration < -0.015 ? -1 : 0; }
}
