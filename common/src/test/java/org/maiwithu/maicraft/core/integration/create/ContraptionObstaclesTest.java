package org.maiwithu.maicraft.core.integration.create;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;

// 用碰撞盒模拟开门、关门、升降和旋转，检查门洞与快照隔离；没有调用真实 Create 结构读取接口。
public final class ContraptionObstaclesTest {
    public static void main(String[] args) {
        var walls=new ArrayList<>(List.of(new AABB(2,0,-2,2.25,3,0),new AABB(2,0,1,2.25,3,3),new AABB(1,-1,-2,4,0,3)));
        var open=new PhysicalObstacleSnapshot(walls,3,0,"create_observed");
        Vec3 outside=new Vec3(.5,0,.5),inside=new Vec3(3.5,0,.5);
        check(open.clearSegment(outside,inside,.6,1.8),"an open entrance is not filled by the cabin bounding box");
        check(!open.clearSegment(outside.add(0,0,1),inside.add(0,0,1),.6,1.8),"native cabin walls obstruct a swept body");
        walls.add(new AABB(2,0,0,2.25,3,1));
        var closed=new PhysicalObstacleSnapshot(walls,4,0,"create_observed");
        check(!closed.clearSegment(outside,inside,.6,1.8),"closed dynamic door retires the old passage");
        check(open.clearSegment(outside,inside,.6,1.8),"worker snapshots remain immutable after a live door update");
        var raised=new PhysicalObstacleSnapshot(walls.stream().map(b->ContraptionObstacles.worldBox(b,p->p.add(0,5,0))).toList(),4,0,"create_observed");
        check(raised.clearSegment(outside,inside,.6,1.8),"an elevator leaving the floor releases the old obstacle");
        var rotated=ContraptionObstacles.worldBox(new AABB(0,0,0,2,3,1),p->new Vec3(10-p.z,p.y,p.x));
        check(rotated.minX==9 && rotated.maxX==10 && rotated.maxZ==2,"native rotation transforms every voxel corner");
        check(!PhysicalObstacleSnapshot.EMPTY.plus(closed).clearSegment(outside,inside,.6,1.8),"Create obstacles survive merging with an absent Sable installation");
        System.out.println("ContraptionObstaclesTest: passed");
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
