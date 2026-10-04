package org.maiwithu.maicraft.core.task.physics;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Quaterniond;
import org.maiwithu.maicraft.core.integration.physics.StructurePose;

/** 模拟同面中心的设置区与角点旋转目标；转过的船也必须按真实本地命中点确认。 */
public final class StructureWrenchTargetTest {
    public static void run() {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var target=new BlockPos(1,1,1);var world=new World(target);
        var q=new Quaterniond().rotationY(Math.PI/2);
        var pose=new StructurePose(new Vec3(20,4,30),q.x,q.y,q.z,q.w,Vec3.ZERO,new Vec3(1,1,1));
        var clicks=StructureEditTarget.wrenchTargets(world,p->true,pose,target);
        check(clicks.size()==24,"六个面都应提供四个可避开中央控件的角点");
        var south=clicks.stream().filter(c->c.face()==Direction.SOUTH).toList();
        Vec3 eye=pose.toWorld(new Vec3(1.5,1.5,4));
        var visible=StructureEditTarget.visible(eye,4.4,south,(a,b)->world.ray(pose.toStorage(a),pose.toStorage(b)));
        check(visible!=null,"旋转船体的外侧角点仍可通过原生射线验证");
        var center=new BlockHitResult(new Vec3(1.5,1.5,2),Direction.SOUTH,target,false);
        check(!StructureEditTarget.precise(visible,center),"同一面的中心命中不能冒充已经瞄准角点");
        check(StructureEditTarget.visible(eye,4.4,south,(a,b)->center)==null,"被其他轮廓挡回面中心时不能提交扳手");
        check(StructureEditTarget.wrenchTargets(world,p->false,pose,target).isEmpty(),"未知方块不生成虚构角点");
        System.out.println("StructureWrenchTargetTest: passed");
    }
    private record World(BlockPos target) implements BlockGetter {
        public BlockState getBlockState(BlockPos pos){return pos.equals(target)?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState();}
        public BlockEntity getBlockEntity(BlockPos pos){return null;}
        public FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
        public int getHeight(){return 384;}
        public int getMinBuildHeight(){return -64;}
        BlockHitResult ray(Vec3 from,Vec3 to){return clip(new ClipContext(from,to,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,CollisionContext.empty()));}
    }
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
}
