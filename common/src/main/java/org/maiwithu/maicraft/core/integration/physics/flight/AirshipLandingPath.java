package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 已减速到落点上方的飞艇按实测漂移垂直下降；地面接触由原生遥测确认，不虚构前进速度。 */
final class AirshipLandingPath {
    private AirshipLandingPath() {}
    static FlightPathProbe.Result trace(FlightPathProbe.World world,FlightSample sample,AABB bounds,
                                        FlightLandingSite site,double seconds,double descentRate) {
        if(site==null)return new FlightPathProbe.Result(FlightPathProbe.Space.UNKNOWN,List.of(sample.position()),null,0);
        if(!Double.isFinite(seconds)||seconds<=0||seconds>12||!Double.isFinite(descentRate)||descentRate<=0)
            throw new IllegalArgumentException("invalid vertical landing horizon");
        var path=new ArrayList<Vec3>();path.add(sample.position());
        Vec3 at=sample.position();AABB previous=bounds;
        // 仍在下坠时按实际更大的下降速度检查，不能假定燃烧器会在一刻内消除下沉惯性。
        double vy=Math.min(-descentRate,sample.velocity().y);
        int steps=(int)Math.ceil(seconds/.25);double dt=seconds/steps;
        for(int step=0;step<steps;step++) {
            at=new Vec3(at.x+sample.velocity().x*dt,Math.max(site.touchdown().y,at.y+vy*dt),at.z+sample.velocity().z*dt);
            AABB next=bounds.move(at.subtract(sample.position()));
            AABB swept=previous.minmax(next).inflate(.08,0,.08);
            // 只在已完整核实的场地内允许预期地面接触；漂出场地必须重新进近，不扩大着陆许可。
            AABB runway=site.runway();
            if(swept.minX<runway.minX||swept.maxX>runway.maxX||swept.minZ<runway.minZ||swept.maxZ>runway.maxZ)
                return new FlightPathProbe.Result(FlightPathProbe.Space.BLOCKED,path,swept,0);
            double above=Math.max(swept.minY,runway.minY);
            if(above>=swept.maxY)return new FlightPathProbe.Result(FlightPathProbe.Space.BLOCKED,path,swept,0);
            // 跑道地面已在选址时核实；这里只查其上方机体扫掠，地面不能被当成空中障碍反复触发复飞。
            AABB airborne=new AABB(swept.minX,above,swept.minZ,swept.maxX,swept.maxY,swept.maxZ);
            var state=world.observe(airborne);path.add(at);
            if(state!=FlightPathProbe.Space.CLEAR)return new FlightPathProbe.Result(state,path,airborne,0);
            if(at.y<=site.touchdown().y)break;
            previous=next;
        }
        return new FlightPathProbe.Result(FlightPathProbe.Space.CLEAR,path,null,0);
    }
}
