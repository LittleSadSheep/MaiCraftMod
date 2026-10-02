package org.maiwithu.maicraft.core.integration.physics.balance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.joml.Vector3d;

/** 将每个力投影到同一世界坐标，围绕实际质心求力矩，分别分析停机和运行工况。 */
public record PhysicsWrench(PhysicsVector force, PhysicsVector torque,
                            PhysicsVector acceleration, PhysicsVector angularAcceleration,
                            double verticalAcceleration, List<Contribution> contributions) {
    public record Contribution(String id, String group, PhysicsVector pointWorld,
                               PhysicsVector forceWorld, PhysicsVector torqueWorld) {}

    public static PhysicsWrench evaluate(PhysicsBody body, PhysicsBody.Rotation attitude,
                                         PhysicsVector position, PhysicsVector angularVelocity,
                                         Map<String, Double> controls, double propulsion) {
        PhysicsVector force = body.gravity().scale(body.mass()), torque = PhysicsVector.ZERO;
        List<Contribution> parts = new ArrayList<>();
        parts.add(new Contribution("gravity", "gravity", position, force, torque));
        for (var load : body.loads()) {
            double setting = controls.getOrDefault(load.id(), load.propulsion() ? propulsion : 1.0);
            if (!Double.isFinite(setting)) throw new IllegalArgumentException("推力设置必须是有限数值");
            PhysicsVector applied = (load.frame() == PhysicsBody.Frame.BODY
                    ? attitude.world(load.force()) : load.force()).scale(setting);
            PhysicsVector couple = (load.frame() == PhysicsBody.Frame.BODY
                    ? attitude.world(load.torque()) : load.torque()).scale(setting);
            PhysicsVector arm = attitude.world(load.point().subtract(body.center()));
            PhysicsVector moment = arm.cross(applied).add(couple);
            force = force.add(applied); torque = torque.add(moment);
            parts.add(new Contribution(load.id(), load.group(), position.add(arm), applied, moment));
        }
        // 船正在转动时保留陀螺项，否则倾斜启动的模拟会低估横滚与偏航之间的耦合。
        Vector3d omega = attitude.local(angularVelocity).mutable();
        Vector3d momentum = body.inertia().matrix().transform(new Vector3d(omega));
        Vector3d rhs = attitude.local(torque).mutable().sub(new Vector3d(omega).cross(momentum));
        PhysicsVector alpha = attitude.world(PhysicsVector.of(body.inertia().matrix().invert().transform(rhs)));
        PhysicsVector acceleration = force.scale(1 / body.mass());
        double gravity = body.gravity().length();
        return new PhysicsWrench(force, torque, acceleration, alpha,
                gravity == 0 ? 0 : -acceleration.dot(body.gravity()) / gravity, List.copyOf(parts));
    }
}
