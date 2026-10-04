package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;

/** 不同载荷需要不同悬停基准；倾斜起飞先扶正，不能为了通过高度门槛提前开桨。 */
final class AirshipHoverTrimTest {
    static void run() {
        var zero=PhysicsVector.ZERO;
        var body=new PhysicsBody(UUID.randomUUID(),"minecraft:overworld",1,10,zero,PhysicsBody.Inertia.of(new Matrix3d()),
                PhysicsBody.Rotation.of(new Quaterniond()),zero,zero,zero,new PhysicsVector(0,-10,0),
                List.of(new PhysicsBody.Load("gas","sable:balloon_lift",zero,new PhysicsVector(0,125,0),zero,PhysicsBody.Frame.WORLD,false,0)),List.of());
        var trim=AirshipHoverTrim.observe(body);
        check(Math.abs(trim.fraction()-.8)<1e-9,"悬停输出应由自重与气源满量升力求得");
        var c=new FlightFeedbackController(FlightEnvelope.airship());c.hoverLift(trim.fraction());
        var course=new FlightGuidance(new Vec3(0,100,300),new Vec3(0,100,300),new Vec3(0,80,300),0,100,true,true,true,true);
        FlightCommand command=null;
        for(int t=1;t<=3;t++)command=c.tick(sample(t,100,0,FlightSample.Contact.AIRBORNE),course,true);
        check(Math.abs(command.lift()-.8)<1e-9,"高度和垂直速度稳定时不能又退回固定半升力");
        c=new FlightFeedbackController(FlightEnvelope.airship());c.hoverLift(.8);
        for(int t=1;t<=8;t++)c.tick(sample(t,80,0,FlightSample.Contact.GROUNDED),course,true);
        for(int t=9;t<=11;t++)command=c.tick(sample(t,100,.3,FlightSample.Contact.AIRBORNE),course,true);
        check(c.phase()==FlightFeedbackController.Phase.CLIMB&&command.power()==0&&command.yaw()==0,"高度已到但船体未扶正时保留垂直控制");
        c.tick(sample(12,100,0,FlightSample.Contact.AIRBORNE),course,true);
        check(c.phase()==FlightFeedbackController.Phase.CRUISE,"真实扶正后才进入巡航");
        System.out.println("AirshipHoverTrimTest: passed");
    }
    private static FlightSample sample(long tick,double y,double pitch,FlightSample.Contact contact) {
        return new FlightSample(tick,new Vec3(0,y,0),Vec3.ZERO,0,pitch,0,0,0,0,contact);
    }
    private static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
}
