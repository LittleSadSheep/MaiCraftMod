package org.maiwithu.maicraft.core.integration.physics.flight;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import static org.maiwithu.maicraft.core.integration.physics.flight.FlightPathProbe.Space.*;

/** 允许已检查场地的预期触地，但机体上方的障碍、横向漂出和未知区块仍阻止下降。 */
final class AirshipLandingPathTest {
    static void run() {
        var sample=new FlightSample(1,new Vec3(0,83,0),new Vec3(0,-.5,0),0,0,0,0,0,0,FlightSample.Contact.AIRBORNE);
        var bounds=new AABB(-2,81,-2,2,85,2);
        var site=new FlightLandingSite(new Vec3(0,82,0),Math.PI/2,new AABB(-4,80.05,-4,4,92,4),1);
        var ground=new AABB(-4,79,-4,4,80,4);
        var result=AirshipLandingPath.trace(box->box.intersects(ground)?BLOCKED:CLEAR,sample,bounds,site,6,.5);
        check(result.state()==CLEAR&&result.path().getLast().y==82,"预测在着陆高度结束，地面本身不是空中障碍");
        check(result.path().stream().allMatch(at->at.x==0&&at.z==0),"零水平速度的飞艇不能被预测成继续沿跑道前进");
        var crate=new AABB(-1,80.2,-1,1,81.2,1);
        check(AirshipLandingPath.trace(box->box.intersects(crate)?BLOCKED:CLEAR,sample,bounds,site,6,.5).state()==BLOCKED,
                "落点上方新出现的物体仍应拦截");
        check(AirshipLandingPath.trace(box->UNKNOWN,sample,bounds,site,6,.5).state()==UNKNOWN,"未知碰撞仍然未知");
        var drifting=new FlightSample(2,sample.position(),new Vec3(2,-.5,0),0,0,0,0,0,0,FlightSample.Contact.AIRBORNE);
        check(AirshipLandingPath.trace(box->CLEAR,drifting,bounds,site,6,.5).state()==BLOCKED,"漂出已核实范围不能沿用原场地许可");
        System.out.println("AirshipLandingPathTest: passed");
    }
    private static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
}
