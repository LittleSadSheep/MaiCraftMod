// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.inventory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Pose;

/** 点射线确认接收面，物品宽高核验抛物线；审查格是落水硬边界，延迟反应的原料邻域只帮助选择瞄准点。 */
public final class TargetedDropGeometry {
    private TargetedDropGeometry() {}
    public static AABB receiverBox(BlockPos receiver) { return new AABB(receiver).inflate(.35,.1,.35); }
    public static Optional<Vec3> aim(LocalPlayer player, BlockPos receiver) {
        return aim(player,receiver,TargetedDropRegion.ofCells(List.of(receiver)));
    }
    public static Optional<Vec3> aim(LocalPlayer player, BlockPos receiver, Collection<BlockPos> cells) {
        return aim(player,receiver,TargetedDropRegion.ofCells(cells));
    }
    public static Optional<Vec3> aim(LocalPlayer player, BlockPos receiver, TargetedDropRegion region) {
        return aim(player,receiver,region,region);
    }
    public static Optional<Vec3> aim(LocalPlayer player, BlockPos receiver, TargetedDropRegion region, TargetedDropRegion aimRegion) {
        return aimFrom(player,player.getEyePosition(),receiver,region,aimRegion);
    }
    public static boolean canReach(LocalPlayer player, Vec3 feet, BlockPos receiver) {
        return canReach(player,feet,receiver,TargetedDropRegion.ofCells(List.of(receiver)));
    }
    public static boolean canReach(LocalPlayer player, Vec3 feet, BlockPos receiver, Collection<BlockPos> cells) {
        return canReach(player,feet,receiver,TargetedDropRegion.ofCells(cells));
    }
    public static boolean canReach(LocalPlayer player, Vec3 feet, BlockPos receiver, TargetedDropRegion region) {
        return canReach(player,feet,receiver,region,region);
    }
    public static boolean canReach(LocalPlayer player, Vec3 feet, BlockPos receiver, TargetedDropRegion region, TargetedDropRegion aimRegion) {
        // 站位预检只试算站立眼高，不移动角色；真正出手还要用实际相机与眼位复核。
        return aimFrom(player,feet.add(0,player.getEyeHeight(Pose.STANDING),0),receiver,region,aimRegion).isPresent();
    }

    private static Optional<Vec3> aimFrom(LocalPlayer player, Vec3 eye, BlockPos receiver, TargetedDropRegion region, TargetedDropRegion aimRegion) {
        Level world=player.level(); if(!world.isLoaded(receiver))return Optional.empty();
        FluidState required=world.getFluidState(receiver); Vec3 origin=eye.add(0,-.3F,0);
        // 原生延迟转化会在入水后继续漂移；从新鲜反应区取瞄准偏好，却不要求首次落水就完成原生的邻域匹配。
        for(BlockPos cell:aimRegion.cells()) {
            if(!region.cells().contains(cell))continue;
            if(!world.isLoaded(cell))continue;
            FluidState fluid=world.getFluidState(cell);
            if(!required.isEmpty()&&!required.getType().isSame(fluid.getType()))continue;
            double plane=cell.getY()+(fluid.isEmpty()?.1:fluid.getHeight(world,cell));
            AABB part=aimRegion.part(cell);
            if(part==null||plane<part.minY||plane>=part.maxY||origin.y<=plane+.05)continue;
            Vec3 target=new Vec3((part.minX+part.maxX)/2,plane,(part.minZ+part.maxZ)/2);
            Vec3 horizontal=target.subtract(origin).multiply(1,0,1);double distance=horizontal.length();
            if(distance<.6||distance>6||!visible(player,eye,target,region,!required.isEmpty()))continue;
            Vec3 forward=horizontal.normalize(),chosen=null;double best=Double.POSITIVE_INFINITY;
            // 水面可跨多个相连的已审查格；仍逐格约束所有保守散布，不能把缺角或池沿纳入一个扩大盒子。
            for(int pitch=-55;pitch<=70;pitch+=5) {
                double angle=Math.toRadians(pitch);
                double score=scatter(world,origin,forward,angle,plane,required,region,target);
                if(score<best) { best=score;chosen=eye.add(forward.scale(Math.cos(angle)).add(0,-Math.sin(angle),0).scale(4)); }
            }
            if(chosen!=null)return Optional.of(chosen);
        }
        return Optional.empty();
    }

    /** 转头门槛只说明相机已接近目标；发原生 Q 前必须再证明实际朝向的所有散布仍在接收域内。 */
    public static boolean safeActualView(LocalPlayer player, BlockPos receiver, TargetedDropRegion region) {
        if(!player.level().isLoaded(receiver))return false;
        Vec3 horizontal=player.getViewVector(1).multiply(1,0,1);if(horizontal.lengthSqr()<1e-10)return false;
        Vec3 origin=player.getEyePosition().add(0,-.3F,0);FluidState required=player.level().getFluidState(receiver);
        for(BlockPos cell:region.cells()) {
            if(!player.level().isLoaded(cell))continue;
            var fluid=player.level().getFluidState(cell);
            double plane=cell.getY()+(fluid.isEmpty()?.1:fluid.getHeight(player.level(),cell));
            if(Double.isFinite(scatter(player.level(),origin,horizontal.normalize(),Math.toRadians(player.getXRot()),
                    plane,required,region,Vec3.atCenterOf(cell))))return true;
        }
        return false;
    }

    private static boolean visible(LocalPlayer player, Vec3 eye, Vec3 surface, TargetedDropRegion region, boolean fluid) {
        // 看见水面使用眼睛的点射线；物品厚度只约束真实抛物线，不能用粗管线扫过池沿来误判视线。
        var hit=player.level().clip(new ClipContext(eye,surface.add(0,fluid?-.001:0,0),ClipContext.Block.COLLIDER,
                fluid?ClipContext.Fluid.ANY:ClipContext.Fluid.NONE,player));
        return fluid ? hit.getType()==HitResult.Type.BLOCK&&region.cells().contains(hit.getBlockPos())
                && !player.level().getFluidState(hit.getBlockPos()).isEmpty() : hit.getType()==HitResult.Type.MISS;
    }

    private static double scatter(Level world, Vec3 origin, Vec3 forward, double angle, double plane,
                                  FluidState required, TargetedDropRegion region, Vec3 target) {
        Vec3 nominal=forward.scale(.3*Math.cos(angle)).add(0,.1-.3*Math.sin(angle),0);double score=0;
        // 原版横向随机是半径 .02 的圆盘；用外接方形包住它，纵向保留 ±.1，不删掉最坏散布来迁就场地。
        for(double lateral:new double[]{-.02,.02})for(double along:new double[]{-.02,.02})for(double vertical:new double[]{-.1,.1}) {
            Vec3 velocity=nominal.add(forward.x*along-forward.z*lateral,vertical,forward.z*along+forward.x*lateral);
            Vec3 landing=trace(world,origin,velocity,plane,required,region);
            if(landing==null)return Double.POSITIVE_INFINITY;
            score=Math.max(score,landing.subtract(target).horizontalDistanceSqr());
        }
        return score;
    }

    private static Vec3 trace(Level level, Vec3 origin, Vec3 velocity, double plane, FluidState required, TargetedDropRegion region) {
        Vec3 at=origin;
        for(int tick=0;tick<40;tick++) {
            velocity=velocity.add(0,-.04,0);Vec3 next=at.add(velocity);
            if(next.y<=plane&&at.y>=plane&&velocity.y<0) {
                Vec3 landing=at.lerp(next,(at.y-plane)/(at.y-next.y));BlockPos actual=BlockPos.containing(landing);
                if(!region.contains(landing)||!level.isLoaded(actual))return null;
                if(!required.isEmpty()&&!required.getType().isSame(level.getFluidState(actual).getType()))return null;
                return clear(level,at,landing)?landing:null;
            }
            if(!clear(level,at,next)||next.y<plane-2)return null;
            at=next;velocity=velocity.multiply((double).98F,.98,(double).98F);
        }
        return null;
    }

    private static boolean clear(Level level, Vec3 from, Vec3 to) {
        // 每小步都按原版物品实体的宽高检查池沿与顶棚；入池前不能依赖撞墙滑落或人为改速度。
        for(int part=0;part<=3;part++) {
            Vec3 point=from.lerp(to,part/3.0);
            AABB item=new AABB(point.x-.125,point.y,point.z-.125,point.x+.125,point.y+.25,point.z+.125);
            for(int x=Mth.floor(item.minX);x<=Mth.floor(item.maxX);x++)for(int y=Mth.floor(item.minY);y<=Mth.floor(item.maxY);y++)
                for(int z=Mth.floor(item.minZ);z<=Mth.floor(item.maxZ);z++) {
                    BlockPos cell=new BlockPos(x,y,z);if(!level.isLoaded(cell))return false;
                    for(AABB obstacle:level.getBlockState(cell).getCollisionShape(level,cell).toAabbs())
                        if(item.intersects(obstacle.move(cell)))return false;
                }
        }
        return true;
    }
}
