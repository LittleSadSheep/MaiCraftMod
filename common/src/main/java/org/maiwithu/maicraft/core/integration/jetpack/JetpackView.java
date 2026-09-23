package org.maiwithu.maicraft.core.integration.jetpack;

import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.BodyControlPort;

/**
 * 选择镜头看向路线哪里，并把实际朝向交给飞行按键计算；镜头还没转向目标时先减少横向赶路。
 */
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

    // 前方观察点与当前路线偏离太大时，仍盯当前路点，避免镜头越过转角把控制方向带偏。
    static Vec3 focus(Vec3 position, Vec3 aim, Vec3 ahead) {
        Vec3 step = aim.subtract(position), glance = ahead.subtract(position);
        if (step.horizontalDistance() < 0.3) return ahead;
        // 当前路线段未抵达拐角前，保持航向位于 55 度转向门限内。
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
                // 保持高度并制动现有侧向速度，同时转向预定航线。
                aim = new Vec3(position.x, aim.y, position.z);
            }
        }
        return JetpackSteering.toward(position, velocity, aim, actualYaw, landing, power);
    }
}
