package org.maiwithu.maicraft.core.integration.physics.balance;

import java.util.List;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import static org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBalanceRegression.*;

/** 用四轮承重、刹停和单侧压缩校验地面配平，不能把停车等同于没有轮胎支撑的悬停。 */
final class PhysicsWheelDynamicsTest {
    static void run() {
        var source=vessel(List.of());
        var body=new PhysicsBody(source.structureId(),source.dimension(),source.tick(),10,PhysicsVector.ZERO,source.inertia(),source.rotation(),
                v(0,.5+.75+.65-.65/6-.0125,0),PhysicsVector.ZERO,PhysicsVector.ZERO,v(0,-10,0),List.of(),List.of());
        var point=v(1,-.5,1);var wheel=wheel(point,0,.75);
        near(force(body,point,wheel,PhysicsVector.ZERO,0).y(),25,"停动力后每个轮子仍需承受四分之一重量");
        near(force(body,point,wheel,PhysicsVector.ZERO,16).z(),28,"原生轮胎驱动转速与推力比例错误");
        near(force(body,point,wheel,v(0,0,10),0).z(),-75,"松动力后的滚动阻力丢失");
        near(force(body,point,wheel(point,1,.75),v(0,0,10),16).z(),-375,"满刹车应取消驱动并加强减速");
        near(force(body,point,wheel,v(2,0,0),0).x(),-120,"车轮横向摩擦没有抑制侧滑");
        near(PhysicsWheelDynamics.force(body,point,wheel,body.rotation(),body.position().add(v(0,2,0)),PhysicsVector.ZERO,PhysicsVector.ZERO,16).length(),0,"离地轮胎仍在提供支撑或驱动");
        near(force(body,point,wheel(point,0,0),PhysicsVector.ZERO,16).length(),0,"没有装轮胎的轮座产生了地面力");
        PhysicsVector sum=body.gravity().scale(body.mass()),torque=PhysicsVector.ZERO;
        var tilted=PhysicsBody.Rotation.of(new Quaterniond().rotationZ(.02));
        for(int x:new int[]{-1,1})for(int z:new int[]{-1,1}) {
            var at=v(x,-.5,z);var tire=wheel(at,0,.75);sum=sum.add(force(body,at,tire,PhysicsVector.ZERO,0));
            var local=PhysicsWheelDynamics.force(body,at,tire,tilted,body.position(),PhysicsVector.ZERO,PhysicsVector.ZERO,0);
            torque=torque.add(tilted.world(at).cross(tilted.world(local)));
        }
        near(sum.length(),0,"四轮平衡停车不应被报告为持续下落");
        check(torque.z()<0,"侧倾后受压车轮没有产生原生扶正力矩");
        var heavier=body.ballast(10,PhysicsVector.ZERO,new Matrix3d().zero());
        check(force(heavier,point,wheel,PhysicsVector.ZERO,0).y()>25,"加配重后悬挂法向质量仍冻结在旧车体上");
    }
    private static PhysicsWheel wheel(PhysicsVector point,double brake,double radius) {
        return new PhysicsWheel("offroad:small_tire",radius,10,0,v(0,0,1),v(1,0,0),1,0,brake,1,
                1.2791666666666668,v(point.x(),0,point.z()),v(0,1,0),null,"contact",true);
    }
    private static PhysicsVector force(PhysicsBody body,PhysicsVector point,PhysicsWheel wheel,PhysicsVector velocity,double rpm) {
        return PhysicsWheelDynamics.force(body,point,wheel,body.rotation(),body.position(),velocity,PhysicsVector.ZERO,rpm);
    }
}
