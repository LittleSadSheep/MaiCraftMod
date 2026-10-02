package org.maiwithu.maicraft.core.task.physics;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;

/** 按原生轮廓选择可见的支撑面；半砖和楼梯不能按整格中心猜测点击位置。 */
final class StructureEditTarget {
    record Click(BlockPos support, Direction face, Vec3 world) {}
    private StructureEditTarget() {}
    static Click placement(LocalPlayer player,SableStructureBridge.Structure ship,BlockPos target) {
        Click nearest=null,visible=null; double best=Double.POSITIVE_INFINITY,visibleDistance=Double.POSITIVE_INFINITY;
        for(Direction side:Direction.values()) {
            BlockPos neighbor=target.relative(side);
            if(!ship.isLoaded(neighbor)) continue;
            var shape=player.level().getBlockState(neighbor).getShape(player.level(),neighbor);
            Direction face=side.getOpposite();
            for(AABB box:shape.toAabbs()) {
                Vec3 point=ship.pose().toWorld(face(box,face).add(Vec3.atLowerCornerOf(neighbor)));
                double distance=point.distanceToSqr(player.getEyePosition());
                var click=new Click(neighbor,face,point);
                if(distance<best) { nearest=click;best=distance; }
                var hit=player.level().clip(new ClipContext(player.getEyePosition(),point,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
                if(hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(neighbor)&&hit.getDirection()==face&&distance<visibleDistance) {
                    visible=click;visibleDistance=distance;
                }
            }
        }
        return visible==null?nearest:visible;
    }
    static Vec3 face(AABB box,Direction face) {
        Vec3 center=box.getCenter(); double epsilon=.00001;
        return switch(face) {
            case UP -> new Vec3(center.x,box.maxY-epsilon,center.z);
            case DOWN -> new Vec3(center.x,box.minY+epsilon,center.z);
            case EAST -> new Vec3(box.maxX-epsilon,center.y,center.z);
            case WEST -> new Vec3(box.minX+epsilon,center.y,center.z);
            case SOUTH -> new Vec3(center.x,center.y,box.maxZ-epsilon);
            case NORTH -> new Vec3(center.x,center.y,box.minZ+epsilon);
        };
    }
}
