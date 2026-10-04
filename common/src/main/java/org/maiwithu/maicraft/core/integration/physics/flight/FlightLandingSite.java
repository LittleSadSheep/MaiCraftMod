package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 在已经加载的目的地附近选择完整起降场；记录观察时刻，进近期间仍需重新检查真实碰撞。 */
public record FlightLandingSite(Vec3 touchdown,double heading,AABB runway,long observedTick) {
    public Vec3 approach(double cruiseAltitude,FlightEnvelope envelope) {
        if(envelope.kind()==FlightEnvelope.Kind.AIRSHIP)return new Vec3(touchdown.x,cruiseAltitude,touchdown.z);
        double distance=Math.max(48,(cruiseAltitude-touchdown.y)/Math.tan(envelope.approachPitch()));
        return touchdown.subtract(FlightSample.forward(heading,0).scale(distance)).multiply(1,0,1).add(0,cruiseAltitude,0);
    }
    public static FlightLandingSite find(ClientLevel world,FlightWorldProbe probe,Vec3 destination,double clearance,
                                          double width,double length,double preferredHeading,long tick) {
        var candidates=new ArrayList<Vec3>();
        // 先试目标附近，再逐圈寻找可停放本机的空间；不把寻找小角色落脚点的结果冒充飞机跑道。
        for(int x=-32;x<=32;x+=8)for(int z=-32;z<=32;z+=8)candidates.add(destination.add(x,0,z));
        candidates.sort(Comparator.comparingDouble(p->p.subtract(destination).horizontalDistance()));
        for(Vec3 candidate:candidates)for(double heading:List.of(preferredHeading,preferredHeading+Math.PI/2,preferredHeading-Math.PI/2,preferredHeading+Math.PI)) {
            var site=inspect(world,probe,candidate,clearance,width,length,heading,tick);
            if(site!=null)return site;
        }
        return null;
    }
    public static FlightLandingSite inspect(ClientLevel world,FlightWorldProbe probe,Vec3 center,double clearance,
                                             double width,double length,double heading,long tick) {
        Vec3 forward=FlightSample.forward(heading,0),right=forward.cross(new Vec3(0,1,0));
        int minimum=Integer.MAX_VALUE,maximum=Integer.MIN_VALUE;
        AABB footprint=null;
        for(double along=-length/2;along<=length/2;along+=2)for(double across=-width/2;across<=width/2;across+=2) {
            Vec3 at=center.add(forward.scale(along)).add(right.scale(across));
            int x=(int)Math.floor(at.x),z=(int)Math.floor(at.z);
            if(!world.getChunkSource().hasChunk(x>>4,z>>4))return null;
            int y=world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
            var ground=new BlockPos(x,y-1,z);var state=world.getBlockState(ground);
            if(!state.getFluidState().isEmpty()||state.getCollisionShape(world,ground).isEmpty())return null;
            minimum=Math.min(minimum,y);maximum=Math.max(maximum,y);
            if(maximum-minimum>1)return null;
            AABB cell=new AABB(x,y,z,x+1,y+1,z+1);footprint=footprint==null?cell:footprint.minmax(cell);
        }
        if(footprint==null)return null;
        AABB runway=new AABB(footprint.minX,maximum+.05,footprint.minZ,footprint.maxX,maximum+Math.max(8,clearance+4),footprint.maxZ);
        if(probe.observe(runway)!=FlightPathProbe.Space.CLEAR)return null;
        return new FlightLandingSite(new Vec3(center.x,maximum+clearance,center.z),FlightSample.wrap(heading),runway,tick);
    }
}
