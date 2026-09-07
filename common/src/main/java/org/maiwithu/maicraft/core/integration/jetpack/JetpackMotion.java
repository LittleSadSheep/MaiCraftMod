// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;

/** Short dry-air prediction, restarted from measured motion each tick. Never changes entity physics. */
final class JetpackMotion {
    record Step(Vec3 position, Vec3 velocity) {}
    private JetpackMotion() {}

    static boolean clearTrajectory(JetpackRoute.Space space, Vec3 position, Vec3 velocity, Vec3 aim,
                                   float yaw, float requestedYaw, JetpackNativeAdapter.Snapshot power) {
        // Cover both a stationary camera and its maximum next-tick turn (240 degrees/second).
        for (boolean turning : new boolean[]{false, true}) {
            Vec3 p = position, v = velocity;
            float heading = yaw;
            for (int tick = 0; tick < 5; tick++) {
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
