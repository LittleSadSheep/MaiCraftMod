package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import static org.maiwithu.maicraft.core.integration.physics.flight.FlightPathProbe.Space.*;

/** 宽机翼与有限转弯半径必须参与避障，未加载与读取预算耗尽均不得当成畅通。 */
public final class FlightPathProbeTest {
    public static void run() {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var sample=new FlightSample(1,new Vec3(0,80,0),new Vec3(0,0,20),0,0,0,0,0,0,FlightSample.Contact.AIRBORNE);
        var bounds=new AABB(-7,79,-3,7,82,3);var envelope=FlightEnvelope.fixedWing();
        // 航点向右九十度，飞机第一秒仍需向原航向前进，正前方的树不能被瞬时改航绕过。
        var tree=new AABB(-1,70,10,1,90,12);
        var curved=FlightPathProbe.trace(box->box.intersects(tree)?BLOCKED:CLEAR,sample,bounds,Math.PI/2,0,3,envelope);
        check(curved.state()==BLOCKED&&curved.path().get(1).z>4,"有限转弯不能跳过正前方障碍");
        var wingTree=new AABB(6,79,10,7,84,11);
        check(FlightPathProbe.trace(box->box.intersects(wingTree)?BLOCKED:CLEAR,sample,bounds,0,0,2,envelope).state()==BLOCKED,
                "机翼会撞树时不能只用中心线判通行");
        var empty=FlightPathProbe.trace(box->CLEAR,sample,bounds,.4,1,3,envelope);
        check(empty.state()==CLEAR&&empty.path().getLast().y>82,"开阔空间保持完整爬升航迹");
        var world=new Scene();var probe=new FlightWorldProbe(world,pos->pos.getZ()<8,List.of(),true,10000);
        check(probe.observe(new AABB(0,80,7,1,81,9))==UNKNOWN,"未加载柱列不能当成空气");
        probe=new FlightWorldProbe(world,pos->true,List.of(),true,1);
        check(probe.observe(new AABB(0,80,0,3,81,3))==UNKNOWN,"碰撞读取预算耗尽要保留未知");
        world.solid=new BlockPos(2,80,2);probe=new FlightWorldProbe(world,pos->true,List.of(),true,100);
        check(probe.observe(new AABB(1,80,1,4,81,4))==BLOCKED,"查询原生碰撞形状，实体方块会阻挡");
        // 原生刚体静止时底面可能略低于地面；只允许真实接地后的微小误差，树冠和台阶仍阻挡上升。
        var resting=new AABB(0,78-.00002,0,4,84,4);
        var floor=new AABB(-10,77,-10,10,78,10);
        check(FlightPathProbe.verticalDeparture(box->box.intersects(floor)?BLOCKED:CLEAR,resting,true)==CLEAR,
                "接地数值误差不能永久阻止飞艇垂直离地");
        check(FlightPathProbe.verticalDeparture(box->box.intersects(floor)?BLOCKED:CLEAR,resting,false)==BLOCKED,
                "未确认接地时不能忽略底面碰撞");
        var canopy=new AABB(0,90,0,4,91,4);
        check(FlightPathProbe.verticalDeparture(box->box.intersects(canopy)?BLOCKED:CLEAR,resting,true)==BLOCKED,
                "垂直起飞仍必须避开气囊上方树冠");
        var edgeBlock=new AABB(4,78,0,5,79,1);
        var restingRoundoff=new AABB(0,78,0,4.00002,84,4);
        check(FlightPathProbe.verticalDeparture(box->box.intersects(edgeBlock)?BLOCKED:CLEAR,restingRoundoff,true)==CLEAR,
                "极小姿态噪声导致的侧面相贴不能阻止离地");
        check(FlightPathProbe.verticalDeparture(box->box.intersects(edgeBlock)?BLOCKED:CLEAR,
                new AABB(0,78,0,4.01,84,4),true)==BLOCKED,"真实侧向穿透仍须报告阻挡");
        // 外置推进器下方本来是空气；只要实体部件向上不会扫到地形，就不能用整机空包围盒拦截。
        var hull=List.of(new AABB(0,78,1,1,79,2),new AABB(2,81,0,3,82,1));
        var underRotor=new AABB(2,78,0,3,79,1);
        check(FlightHullDeparture.inspectParts(box->box.intersects(underRotor)?BLOCKED:CLEAR,hull,true,78)==CLEAR,
                "推进器下方的地形不在真实垂直扫掠体内");
        var overRotor=new AABB(2,84,0,3,85,1);
        check(FlightHullDeparture.inspectParts(box->box.intersects(overRotor)?BLOCKED:CLEAR,hull,true,78)==BLOCKED,
                "桨叶上方的树枝必须阻止垂直起飞");
        check(FlightHullDeparture.inspectParts(box->UNKNOWN,hull,true,78)==UNKNOWN,"缺少任一部件的通道观察不能当畅通");
        // 成型桨叶可能伸出主地块，不能只检查主船体而擦过前方树干。
        var narrow=new AABB(-1,79,-1,1,82,1);
        var extended=FlightPathProbe.includeMovingParts(narrow,List.of(new AABB(-4,79,-1,-1,82,1)));
        var rotorTree=new AABB(-3,79,10,-2,84,11);
        check(FlightPathProbe.trace(box->box.intersects(rotorTree)?BLOCKED:CLEAR,sample,narrow,0,0,2,envelope).state()==CLEAR
                &&FlightPathProbe.trace(box->box.intersects(rotorTree)?BLOCKED:CLEAR,sample,extended,0,0,2,envelope).state()==BLOCKED,
                "机外转子的宽度必须参与整段巡航碰撞检查");
        System.out.println("FlightPathProbeTest: passed");
    }
    private static final class Scene implements BlockGetter {
        BlockPos solid;
        public BlockState getBlockState(BlockPos pos){return pos.equals(solid)?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState();}
        public FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
        public BlockEntity getBlockEntity(BlockPos pos){return null;}
        public int getHeight(){return 384;}
        public int getMinBuildHeight(){return -64;}
    }
    private static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
}
