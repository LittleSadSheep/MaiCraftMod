// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;

/** Quantized native key steering; measured velocity supplies braking, never a velocity write. */
final class JetpackSteering {
    static BodyControlPort.Movement toward(Vec3 position, Vec3 velocity, Vec3 target, float yaw, boolean landing,
                                          JetpackNativeAdapter.Snapshot power) {
        Vec3 error = target.subtract(position);
        double length = Math.hypot(error.x, error.z);
        double braking = power.horizontal() * 0.8;
        double speed = Math.min(power.horizontal() * 1.2 / 0.09, Math.sqrt(2 * braking * length));
        double desiredX = length < 0.02 ? 0 : error.x / length * speed;
        double desiredZ = length < 0.02 ? 0 : error.z / length * speed;
        double x = desiredX - velocity.x, z = desiredZ - velocity.z;
        double radians = Math.toRadians(yaw), sin = Math.sin(radians), cos = Math.cos(radians);
        float forward = pulse(-x * sin + z * cos), strafe = pulse(x * cos + z * sin);
        boolean up = !landing && JetpackDynamics.shouldRise(position.y, velocity.y, target.y, power);
        // Shift selects native fast descent. Retain slow hover descent by releasing UP instead.
        return new BodyControlPort.Movement(forward, strafe, up, false, false);
    }
    private static float pulse(double acceleration) { return acceleration > 0.015 ? 1 : acceleration < -0.015 ? -1 : 0; }
}
