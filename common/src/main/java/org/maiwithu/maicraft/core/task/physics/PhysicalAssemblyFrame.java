package org.maiwithu.maicraft.core.task.physics;

import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import org.maiwithu.maicraft.core.integration.physics.StructurePose;

/** 每刻重新定位施工坐标；世界锚点与船体存储坐标分开，胶层端点必须由真实射线选中。 */
record PhysicalAssemblyFrame(ClientLevel level,SableStructureBridge.Structure structure,BlockPos origin) {
    private static final StructurePose WORLD=new StructurePose(Vec3.ZERO,0,0,0,1,Vec3.ZERO,new Vec3(1,1,1));
    PhysicalAssemblyFrame { origin=origin.immutable(); }
    static PhysicalAssemblyFrame read(LocalPlayer player,UUID id,BlockPos anchor) {
        if(id==null) return new PhysicalAssemblyFrame(player.clientLevel,null,anchor);
        var ship=SableStructureBridge.find(player.clientLevel,id);
        if(ship==null||ship.pose()==null||ship.plotCenter()==null||!Boolean.TRUE.equals(ship.ready()))
            throw new IllegalStateException("物理结构身份、坐标或加载状态不可读");
        return new PhysicalAssemblyFrame(player.clientLevel,ship,ship.plotCenter());
    }
    StructurePose pose() { return structure==null?WORLD:structure.pose(); }
    BlockPos storage(BlockPos offset) { return origin.offset(offset); }
    Vec3 world(BlockPos offset) { return pose().toWorld(Vec3.atCenterOf(storage(offset))); }
    boolean loaded(BlockPos storage) { return structure==null?level.isLoaded(storage):structure.isLoaded(storage); }
    boolean regionLoaded(AABB region) {
        for(int x=(int)Math.floor(region.minX)>>4;x<=(int)Math.floor(Math.nextDown(region.maxX))>>4;x++)
            for(int z=(int)Math.floor(region.minZ)>>4;z<=(int)Math.floor(Math.nextDown(region.maxZ))>>4;z++)
                if(!loaded(new BlockPos(x*16,(int)Math.floor(region.minY),z*16)))return false;
        return true;
    }
    Vec3 aim(LocalPlayer player,BlockPos offset,Vec3 eye,boolean honey) {
        BlockPos target=storage(offset);if(!loaded(target))return null;
        if(honey&&airSelection(target)) {
            // 蜂蜜胶的 Alt 选点取原生射线最远格；不能把任意近处空气格伪装成一次原生命中。
            Vec3 aim=world(offset);var hit=ray(player,eye,aim.subtract(eye),true);
            return selected(hit,target,true)?aim:null;
        }
        var candidates=StructureEditTarget.targets(level,this::loaded,pose(),target,false);
        for(var candidate:candidates) {
            if(candidate.world().distanceToSqr(eye)>Math.pow(player.blockInteractionRange()-.1,2))continue;
            var hit=ray(player,eye,candidate.world().subtract(eye),honey);
            if(selected(hit,target,false))return candidate.world();
        }
        return null;
    }
    BlockHitResult actualHit(LocalPlayer player,BlockPos offset,boolean honey) {
        var hit=ray(player,player.getEyePosition(),player.getViewVector(1),honey);
        boolean air=honey&&airSelection(storage(offset));
        return selected(hit,storage(offset),air)?hit:null;
    }
    private static boolean selected(BlockHitResult hit,BlockPos target,boolean air) {
        return hit!=null&&hit.getBlockPos().equals(target)&&hit.getType()==(air?HitResult.Type.MISS:HitResult.Type.BLOCK);
    }
    private boolean airSelection(BlockPos target) {
        return structure==null&&loaded(target)&&level.getBlockState(target).getCollisionShape(level,target,CollisionContext.empty()).isEmpty();
    }
    private BlockHitResult ray(LocalPlayer player,Vec3 eye,Vec3 direction,boolean honey) {
        if(direction.lengthSqr()<1e-10)return null;
        Vec3 end=eye.add(direction.normalize().scale(player.blockInteractionRange()));
        // 与原生蜂蜜胶一致使用碰撞射线；强力胶及组装器使用实际方块轮廓。
        for(BlockPos pos:BlockPos.betweenClosed(BlockPos.containing(Math.min(eye.x,end.x),Math.min(eye.y,end.y),Math.min(eye.z,end.z)),
                BlockPos.containing(Math.max(eye.x,end.x),Math.max(eye.y,end.y),Math.max(eye.z,end.z))))if(!level.isLoaded(pos))return null;
        return level.clip(new ClipContext(eye,end,honey?ClipContext.Block.COLLIDER:ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,
                honey?CollisionContext.empty():CollisionContext.of(player)));
    }
    boolean stationary() {
        if(structure==null)return true;
        if(structure.lastPose()==null)return false;
        Vec3 at=Vec3.atLowerCornerOf(origin);var old=structure.lastPose();var now=pose();
        return now.toWorld(at).distanceToSqr(old.toWorld(at))<=.0004&&Math.abs(now.orientationX()*old.orientationX()
                +now.orientationY()*old.orientationY()+now.orientationZ()*old.orientationZ()+now.orientationW()*old.orientationW())>=.99999;
    }
}
