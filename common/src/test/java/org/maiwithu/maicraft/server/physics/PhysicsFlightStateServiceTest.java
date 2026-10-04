package org.maiwithu.maicraft.server.physics;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsWheel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 接地遥测区分已施力、悬空、未观察与无轮结构，不能因为机体暂时静止就推断落地。 */
public final class PhysicsFlightStateServiceTest {
    public static void run() {
        check(state(List.of(),List.of()).equals("unknown"),"没有轮胎观察不能推断悬空或落地");
        check(state(List.of(wheel("contact",true)),List.of()).equals("grounded"),"原生接地与施力共同确认支撑");
        check(state(List.of(wheel("contact_pending_application",false)),List.of()).equals("unknown"),"待批量施力不能抢先确认为支撑");
        check(state(List.of(wheel("airborne",false),wheel("no_ground",false)),List.of()).equals("airborne"),"完整轮胎采样没有接地时才报告离地");
        check(state(List.of(wheel("airborne",false)),List.of("unmodeled:车轮观察缺失")).equals("unknown"),"遗漏其他轮子时不能冒称整机离地");
        // 真实表面接近方块顶面才是几何支撑；悬空与横向错开的地板都不能支撑吊舱。
        var ground=new AABB(0,0,0,1,1,1);
        check(FlightHullSupport.supports(new Vec3(.5,1.01,.5),ground),"相接的吊舱表面可作为无轮支撑证据");
        check(!FlightHullSupport.supports(new Vec3(.5,1.2,.5),ground)&&!FlightHullSupport.supports(new Vec3(2,1,.5),ground),"悬空或错位不能伪报接地");
        System.out.println("PhysicsFlightStateServiceTest: passed");
    }
    private static PhysicsWheel wheel(String state,boolean applied) {
        return new PhysicsWheel("offroad:small_tire",.75,10,0,new PhysicsVector(0,0,1),new PhysicsVector(1,0,0),
                1,0,0,1,1,new PhysicsVector(0,0,0),new PhysicsVector(0,1,0),null,state,applied);
    }
    private static String state(List<PhysicsWheel> wheels,List<String> unknown) {
        var zero=PhysicsVector.ZERO;
        var loads=new ArrayList<PhysicsBody.Load>();
        for(int i=0;i<wheels.size();i++)loads.add(new PhysicsBody.Load("wheel:"+i,"offroad:wheel_contact",zero,zero,zero,
                PhysicsBody.Frame.BODY,false,0,0,null,wheels.get(i)));
        var body=new PhysicsBody(UUID.randomUUID(),"minecraft:overworld",10,10,zero,new PhysicsBody.Inertia(1,1,1,0,0,0),
                new PhysicsBody.Rotation(0,0,0,1),zero,zero,zero,new PhysicsVector(0,-9.81,0),loads,unknown);
        return PhysicsFlightStateService.wheelContact(body).get("state").getAsString();
    }
    private static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
}
