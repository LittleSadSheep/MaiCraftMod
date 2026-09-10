// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;

/**
 * 把前后左右、上升按键转换成短时间的位置估计，再检查保持原朝向或逐步转头时会不会碰撞。
 */
final class JetpackMotion {
    record Step(Vec3 position, Vec3 velocity) {}
    private JetpackMotion() {}

    static boolean canRise(JetpackRoute.Space space, Vec3 position, Vec3 velocity, Vec3 aim,
                           float yaw, float requestedYaw, boolean grounded, JetpackNativeAdapter.Snapshot power) {
        double rise = JetpackDynamics.riseEnvelope(velocity.y, true, power);
        if (space.clear(position, position.add(0, rise, 0))) return true;
        if (grounded) return false; // Native ground jumps are outside the dry-air model.
        // A moving body can leave a slab's footprint before reaching its UP peak.
        // Include the full pulse/coast duration, even when configured thrust exceeds the usual five-tick forecast.
        double raw = JetpackDynamics.rawAfterStep(JetpackDynamics.nextVertical(velocity.y, true, power), power);
        int ticks = 1;
        while (raw > 0 && ticks < 32) { raw = JetpackDynamics.rawAfterStep(raw, power); ticks++; }
        return raw <= 0 && clearTrajectory(space, position, velocity, aim, yaw, requestedYaw, power, Math.max(5, ticks));
    }

    static boolean clearTrajectory(JetpackRoute.Space space, Vec3 position, Vec3 velocity, Vec3 aim,
                                   float yaw, float requestedYaw, JetpackNativeAdapter.Snapshot power) {
        return clearTrajectory(space, position, velocity, aim, yaw, requestedYaw, power, 5);
    }

    // 分别估计镜头不转和每刻最多转十二度两种情况，都通畅才接受；按键在每一步重新计算。
    private static boolean clearTrajectory(JetpackRoute.Space space, Vec3 position, Vec3 velocity, Vec3 aim,
                                           float yaw, float requestedYaw, JetpackNativeAdapter.Snapshot power, int ticks) {
        // Cover both a stationary camera and its maximum next-tick turn (240 degrees/second).
        for (boolean turning : new boolean[]{false, true}) {
            Vec3 p = position, v = velocity;
            float heading = yaw;
            for (int tick = 0; tick < ticks; tick++) {
                if (turning) heading += Mth.clamp(Mth.wrapDegrees(requestedYaw - heading), -12, 12);
                var command = JetpackView.command(p, v, aim, heading, false, power);
                Step next = step(p, v, command, heading, power);
                if (!space.clear(p, next.position())) return false;
                p = next.position(); v = next.velocity();
            }
        }
        return true;
    }

    /** FlightLib 3.2.1 applies each native direction separately; vanilla alone normalizes diagonal input. */
    // 合计背包横向推力与普通空中按键，再计算本次位置和受阻力影响的下一刻速度。
    static Step step(Vec3 position, Vec3 velocity, BodyControlPort.Movement command, float yaw,
                     JetpackNativeAdapter.Snapshot power) {
        double radians = yaw * (Math.PI / 180), sin = Math.sin(radians), cos = Math.cos(radians);
        double nativeSide = Math.signum(command.strafe()) * power.horizontal();
        double nativeForward = command.forward() > 0 ? power.horizontal() * 1.2
                : command.forward() < 0 ? -power.horizontal() * .8 : 0;
        double x = deadzone(velocity.x + nativeSide * cos - nativeForward * sin);
        double z = deadzone(velocity.z + nativeForward * cos + nativeSide * sin);
        double side = command.strafe() * .98F, forward = command.forward() * .98F;
        double length = Math.max(1, Math.hypot(side, forward));
        side *= .02F / length; forward *= .02F / length;
        x += side * cos - forward * sin;
        z += forward * cos + side * sin;
        double y = JetpackDynamics.nextVertical(velocity.y, command.jumping(), power);
        return new Step(position.add(x, y, z), new Vec3(x * .91F, JetpackDynamics.rawAfterStep(y, power), z * .91F));
    }

    private static double deadzone(double value) { return Math.abs(value) < .003 ? 0 : value; }
}
