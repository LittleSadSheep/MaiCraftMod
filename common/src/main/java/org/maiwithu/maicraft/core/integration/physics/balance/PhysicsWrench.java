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
        return evaluate(body,attitude,position,angularVelocity,controls,propulsion,true);
    }
    public static PhysicsWrench evaluate(PhysicsBody body, PhysicsBody.Rotation attitude,
                                         PhysicsVector position, PhysicsVector angularVelocity,
                                         Map<String, Double> controls, double propulsion, boolean details) {
        return evaluate(body,attitude,position,body.velocity(),angularVelocity,controls,propulsion,details);
    }
    public static PhysicsWrench evaluate(PhysicsBody body, PhysicsBody.Rotation attitude,
                                         PhysicsVector position, PhysicsVector velocity, PhysicsVector angularVelocity,
                                         Map<String, Double> controls, double propulsion, boolean details) {
        PhysicsComputation.spend(body.loads().size()+1);
        PhysicsVector force = body.gravity().scale(body.mass()), torque = PhysicsVector.ZERO;
        List<Contribution> parts = new ArrayList<>();
        if(details) parts.add(new Contribution("gravity", "gravity", position, force, torque));
        for (var load : body.loads()) {
            double setting = controls.getOrDefault(load.id(), load.propulsion() ? propulsion : 1.0);
            if (!Double.isFinite(setting)) throw new IllegalArgumentException("推力设置必须是有限数值");
            PhysicsVector arm = attitude.world(load.point().subtract(body.center()));
            PhysicsVector source=load.force();
            if(load.aerodynamics()!=null) {
                // 飞机加速、转弯或左右翼速度不同时，逐面使用真实作用点速度重新计算升力与阻力。
                var localVelocity=attitude.local(velocity.add(angularVelocity.cross(arm)));
                source=source.add(load.aerodynamics().force(localVelocity));
            }
            if(load.wheel()!=null&&load.wheel().referenceRpm()!=null) {
                // 车轮的倍率只调驱动，停机和松油门仍保留悬挂及摩擦；实测快照没有参考转速，仍直接展示原生点力。
                source=PhysicsWheelDynamics.force(body,load.point(),load.wheel(),attitude,position,velocity,angularVelocity,
                        load.wheel().referenceRpm()*propulsion*setting);
                setting=1;
            }
            PhysicsVector applied = (load.frame() == PhysicsBody.Frame.BODY
                    ? attitude.world(source) : source).scale(setting);
            PhysicsVector couple = (load.frame() == PhysicsBody.Frame.BODY
                    ? attitude.world(load.torque()) : load.torque()).scale(setting);
            if(load.propulsion()&&load.airflow()>1e-9&&applied.length()>1e-9) {
                // 起飞加速后迎流会降低推力；偏置桨还要使用作用点的转动速度，而不是只看船中心速度。
                PhysicsVector pointVelocity=velocity.add(angularVelocity.cross(arm));
                double flow=load.airflow()*Math.abs(setting);
                double advance=pointVelocity.dot(applied.scale(1/applied.length()));
                double fraction=Math.clamp(1-advance/Math.max(flow,1e-9),0,1);
                applied=applied.scale(fraction); couple=couple.scale(fraction);
            }
            PhysicsVector moment = arm.cross(applied).add(couple);
            force = force.add(applied); torque = torque.add(moment);
            if(details) parts.add(new Contribution(load.id(), load.group(), position.add(arm), applied, moment));
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
