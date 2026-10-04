package org.maiwithu.maicraft.core.integration.create;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.integration.physics.StructurePose;

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
        nestedPropeller();
        System.out.println("ContraptionObstaclesTest: passed");
    }
    private static void nestedPropeller() {
        // 桨叶已离开艇体方块表，但仍在两千万格外的原生存储区；它必须挡住现实中的通路，而非远处虚构位置。
        var plot=new Vec3(20_481_030,123,20_483_075);
        double half=Math.sqrt(.5);
        var ship=new StructurePose(new Vec3(10,4,20),0,half,0,half,plot,new Vec3(1,1,1));
        var blade=new AABB(-1,0,0,2,1,.25);
        var global=ContraptionObstacles.worldTransform(p->plot.add(-p.y,p.x,p.z),ship);
        var box=ContraptionObstacles.worldBox(blade,global);
        check(Math.abs(box.minX-10)<1e-6&&Math.abs(box.maxX-10.25)<1e-6
                &&Math.abs(box.minY-3)<1e-6&&Math.abs(box.maxY-6)<1e-6
                &&Math.abs(box.minZ-20)<1e-6&&Math.abs(box.maxZ-21)<1e-6,"nested rotor rotation was not composed with the ship pose");
        var obstacle=new PhysicalObstacleSnapshot(List.of(box),1,0,"create_observed");
        check(!obstacle.clearSegment(new Vec3(9,3,20.5),new Vec3(12,3,20.5),.6,1.8),"a formed propeller was mistaken for walkable air");
        check(obstacle.clearSegment(new Vec3(9,3,22),new Vec3(12,3,22),.6,1.8),"the open path beside the propeller was lost");
        // 没有外层船体时保留 Create 自己的世界位置；下一份姿态快照移动桨叶，旧快照仍供当前搜索线程读取。
        check(ContraptionObstacles.worldTransform(p->p.add(3,0,0),null).apply(Vec3.ZERO).equals(new Vec3(3,0,0)),"world contraption was transformed twice");
        var moved=new StructurePose(new Vec3(30,4,20),0,half,0,half,plot,new Vec3(1,1,1));
        var movedBox=ContraptionObstacles.worldBox(blade,ContraptionObstacles.worldTransform(p->plot.add(-p.y,p.x,p.z),moved));
        check(movedBox.minX>29&&box.minX<11,"movement changed or reused the old immutable obstacle");
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
