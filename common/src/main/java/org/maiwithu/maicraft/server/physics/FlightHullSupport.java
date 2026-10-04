package org.maiwithu.maicraft.server.physics;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;
import org.maiwithu.maicraft.core.integration.physics.StructurePose;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 无轮吊舱也可读取船壳与真实地形的支撑几何；明确标注几何证据，不伪造物理引擎没有公开的接触冲量。 */
final class FlightHullSupport {
    private FlightHullSupport() {}
    static JsonObject read(ServerLevel level,Object ship) {
        Object raw=NativeApi.call(ship,null,"logicalPose"),plot=NativeApi.call(ship,null,"getPlot");
        var pose=StructurePose.copyOf((Vector3dc)NativeApi.call(raw,null,"position"),(Quaterniondc)NativeApi.call(raw,null,"orientation"),
                (Vector3dc)NativeApi.call(raw,null,"rotationPoint"),(Vector3dc)NativeApi.call(raw,null,"scale"));
        Object bounds=NativeApi.call(plot,null,"getBoundingBox");int[] min=new int[3],max=new int[3];int axis=0;
        for(String name:List.of("X","Y","Z")) {
            min[axis]=((Number)NativeApi.call(bounds,null,"min"+name)).intValue();
            max[axis++]=((Number)NativeApi.call(bounds,null,"max"+name)).intValue();
        }
        long volume=(1L+max[0]-min[0])*(1L+max[1]-min[1])*(1L+max[2]-min[2]);
        if(volume<=0||volume>100_000)return result("unknown",0,0,"hull observation volume unavailable or exceeds per-update budget");
        int reads=0,points=0,contacts=0;boolean unknown=false;
        for(BlockPos pos:BlockPos.betweenClosed(min[0],min[1],min[2],max[0],max[1],max[2])) {
            if(!level.hasChunkAt(pos)){unknown=true;continue;}
            reads++;
            var state=level.getBlockState(pos);if(state.isAir())continue;
            for(AABB box:state.getCollisionShape(level,pos).toAabbs())for(int corner=0;corner<8;corner++) {
                Vec3 local=new Vec3(pos.getX()+((corner&1)==0?box.minX+.001:box.maxX-.001),
                        pos.getY()+((corner&2)==0?box.minY+.001:box.maxY-.001),
                        pos.getZ()+((corner&4)==0?box.minZ+.001:box.maxZ-.001));
                Vec3 world=pose.toWorld(local);points++;
                if(points>100_000)return result("unknown",reads,contacts,"hull surface observation budget exhausted");
                BlockPos below=BlockPos.containing(world.add(0,-.06,0));
                if(!level.hasChunkAt(below)){unknown=true;continue;}
                for(AABB ground:level.getBlockState(below).getCollisionShape(level,below).toAabbs())
                    if(supports(world,ground.move(below))){contacts++;break;}
            }
        }
        return result(contacts>0?"grounded":unknown||points==0?"unknown":"airborne",reads,contacts,
                unknown?"some terrain or hull cells were not loaded":"loaded native collision surfaces at the actual transformed hull");
    }
    // 必须有原生实体顶面位于船壳真实表面点的 0.06 格邻域；只靠高度或零速度不算支撑。
    static boolean supports(Vec3 point,AABB ground) {
        return point.x>=ground.minX&&point.x<=ground.maxX&&point.z>=ground.minZ&&point.z<=ground.maxZ
                &&Math.abs(point.y-ground.maxY)<=.06;
    }
    private static JsonObject result(String state,int reads,int contacts,String detail) {
        var out=new JsonObject();out.addProperty("state",state);out.addProperty("source","observed_hull_collision_geometry");
        out.addProperty("block_reads",reads);out.addProperty("support_points",contacts);out.addProperty("detail",detail);
        out.addProperty("solver_contact_impulse_observed",false);return out;
    }
}
