package org.maiwithu.maicraft.core.task.physics;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.integration.jetpack.JetpackRoute;
import org.maiwithu.maicraft.core.integration.machine.control.DriverStation;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import org.maiwithu.maicraft.core.integration.physics.StructurePose;

/** 在真实原版碰撞与射线夹具中核对站位接入，规划可见绝不替代实际转头后的命中。 */
public final class StructureEditApproachTest {
    public static void run() throws Exception {
        try(var h=new InteractionWorldTestHarness()) {
            // 此场景实际查询导航身体净空，补齐轻量玩家夹具的站立尺寸，不能把缺字段当作碰撞失败。
            var dimensions=Entity.class.getDeclaredField("dimensions");dimensions.setAccessible(true);
            dimensions.set(h.player,h.player.getDimensions(Pose.STANDING));
            for(int x=3;x<=7;x++) for(int z=3;z<=7;z++) h.set(new BlockPos(x,1,z),Blocks.OAK_PLANKS.defaultBlockState());
            h.position(new Vec3(6.58,2,5.5));
            var pose=new StructurePose(Vec3.ZERO,0,0,0,1,Vec3.ZERO,new Vec3(1,1,1));
            var ship=new SableStructureBridge.Structure(UUID.randomUUID(),"fixture",true,pose,pose,
                    new AABB(3,1,3,8,2,8),BlockPos.ZERO,new AABB(3,1,3,8,2,8),List.of(h.level.getChunk(0,0)),Map.of());
            var target=new BlockPos(8,1,5);
            // 夹具共用一个世界承载甲板和地面；只把甲板范围交给结构读面，避免把主世界地面误认成船体。
            var faces=StructureEditTarget.targets(h.level,p->ship.isLoaded(p)&&ship.storageBounds().contains(Vec3.atCenterOf(p)),pose,target,true);
            var east=faces.stream().filter(c->c.face()==Direction.EAST).findFirst().orElseThrow();
            var ctx=ClientRuntime.requireContext(h.player);
            check(StructureEditApproach.current(ctx,ship,target,true,faces)==null,"甲板内侧必须触发移位");
            var delta=east.world().subtract(h.player.getEyePosition());
            h.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)));
            h.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z))));
            var hit=DriverStation.hit(h.player,ship,east.support());
            check(hit!=null&&hit.getDirection()==Direction.UP,"保留实际顶面命中，不能伪造成 EAST");
            check(StructureEditApproach.gaze(ctx,faces).get("actual_face").equals("up"),"回执需报告阻挡视线的实际面");
            var space=JetpackRoute.observed(ctx);
            var exterior=StructureEditApproach.probe(ctx,ship,target,true,faces,space,new BlockPos(9,1,5));
            check(exterior.site()!=null&&exterior.site().click().face()==Direction.EAST,"带真实地面支撑的外侧站位应可用: "+exterior
                    +"; body="+h.player.getDimensions(Pose.STANDING)+"; clear="+space.clear(new Vec3(9.5,1,5.5),new Vec3(9.5,1,5.5))
                    +"; eye="+StructureEditApproach.eyeHeight(ctx,true)+"; visible="+StructureEditTarget.visible(h.player,new Vec3(9.5,2.27,5.5),faces));
            check(StructureEditApproach.probe(ctx,ship,target,true,faces,space,target).site()==null,"不得站进将放置的方块");
            check(StructureEditApproach.probe(ctx,ship,target,true,faces,space,new BlockPos(18,1,5)).unloaded(),"不能把未加载落点当空气");
            h.set(new BlockPos(9,2,5),Blocks.STONE.defaultBlockState());
            check(StructureEditApproach.probe(ctx,ship,target,true,faces,space,new BlockPos(9,1,5)).site()==null,"站位头部碰撞必须被拒绝");
            h.set(new BlockPos(9,2,5),Blocks.AIR.defaultBlockState());
            h.position(exterior.site().landing().landingPoint());h.nextTick();ctx=ClientRuntime.requireContext(h.player);
            h.player.setYRot(0);h.player.setXRot(0);
            check(StructureEditApproach.current(ctx,ship,target,true,faces)!=null,"抵达外侧后可重新建立规划视线");
            check(DriverStation.hit(h.player,ship,east.support())==null,"未转头前不能把规划射线当作原生点击命中");
            check(h.blockUses()==0,"搜索和瞄准回归不应直接提交任何放置");
        }
    }
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
}
