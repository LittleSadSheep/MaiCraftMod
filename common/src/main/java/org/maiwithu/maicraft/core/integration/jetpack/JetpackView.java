package org.maiwithu.maicraft.core.integration.jetpack;

import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;

/** Look down the nearby course, then steer in the actual smoothly changing camera frame. */
final class JetpackView {
    record Look(float yaw, float pitch) {}
    private JetpackView() {}

    static Vec3 lookAhead(List<Vec3> course, int index, Vec3 position, double distance) {
        Vec3 cursor = position;
        for (int i = Math.max(0, index); i < course.size(); i++) {
            Vec3 next = course.get(i);
            double length = cursor.distanceTo(next);
            if (length > distance) return cursor.lerp(next, distance / length);
            distance -= length; cursor = next;
        }
        return cursor;
    }

    static Look toward(Vec3 feet, double eyeHeight, Vec3 target, float currentYaw, boolean landing) {
        Vec3 delta = target.subtract(feet);
        double horizontal = delta.horizontalDistance();
        float yaw = horizontal > 0.3 ? (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90 : currentYaw;
        double eyeDelta = delta.y - eyeHeight * (landing ? 0.75 : 0.25);
        float pitch = (float) -Math.toDegrees(Math.atan2(eyeDelta, Math.max(0.5, horizontal)));
        return new Look(yaw, Mth.clamp(pitch, -55, landing ? 75 : 45));
    }

    static Vec3 focus(Vec3 position, Vec3 aim, Vec3 ahead) {
        Vec3 step = aim.subtract(position), glance = ahead.subtract(position);
        if (step.horizontalDistance() < 0.3) return ahead;
        // Keep the current leg inside the 55-degree steering gate until we reach the corner.
        double dot = step.x * glance.x + step.z * glance.z;
        return dot >= Math.cos(Math.PI / 4) * step.horizontalDistance() * glance.horizontalDistance()
                && glance.horizontalDistance() > 0.3 ? ahead : aim;
    }

    static BodyControlPort.Movement command(Vec3 position, Vec3 velocity, Vec3 aim, float actualYaw,
                                            boolean landing, JetpackNativeAdapter.Snapshot power) {
        Vec3 delta = aim.subtract(position);
        if (!landing && delta.horizontalDistance() > 0.3) {
            float bearing = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90;
            if (Math.abs(Mth.wrapDegrees(bearing - actualYaw)) > 55) {
                // Turn toward the course while holding height and braking existing sideways motion.
                aim = new Vec3(position.x, aim.y, position.z);
            }
        }
        return JetpackSteering.toward(position, velocity, aim, actualYaw, landing, power);
    }
}
