package org.maiwithu.maicraft.core.task.physics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.StructurePose;

/** 按原生轮廓选择可见的支撑面；半砖和楼梯不能按整格中心猜测点击位置。 */
final class StructureEditTarget {
    record Click(BlockPos support, Direction face, Vec3 world) {}
    private StructureEditTarget() {}
    // 候选面只描述可点击几何；不能把最近但被甲板顶面挡住的外侧面冒充可见施工面。
    static List<Click> targets(BlockGetter level,Predicate<BlockPos> loaded,StructurePose pose,BlockPos target,boolean placing) {
        var result=new ArrayList<Click>();
        for(Direction side:Direction.values()) {
            BlockPos support=placing?target.relative(side):target;
            if(!loaded.test(support)) continue;
            Direction face=placing?side.getOpposite():side;
            for(AABB box:level.getBlockState(support).getShape(level,support).toAabbs())
                result.add(new Click(support,face,pose.toWorld(face(box,face).add(Vec3.atLowerCornerOf(support)))));
        }
        return List.copyOf(result);
    }
    static Click visible(LocalPlayer player,Vec3 eye,List<Click> targets) {
        return visible(eye,player.blockInteractionRange()-.1,targets,(from,to)->player.level().clip(
                new ClipContext(from,to,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player)));
    }
    // 从候选眼点重新走原生射线，要求方块及其本地朝向同时匹配；转过身后的实际视线仍由执行器再核验。
    static Click visible(Vec3 eye,double reach,List<Click> targets,BiFunction<Vec3,Vec3,BlockHitResult> ray) {
        return targets.stream().filter(c->c.world().distanceToSqr(eye)<=reach*reach)
                .sorted(Comparator.comparingDouble(c->c.world().distanceToSqr(eye))).filter(c->{
                    var hit=ray.apply(eye,c.world());
                    return hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(c.support())&&hit.getDirection()==c.face();
                }).findFirst().orElse(null);
    }
    // 放置时先避开将要占据的格子，免得导航把麦麦送进目标格后被原生实体碰撞拒绝。
    static boolean outsidePlacement(StructurePose pose,BlockPos target,AABB worldBody) {
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=-minX,maxY=-minX,maxZ=-minX;
        for(double x:new double[]{worldBody.minX,worldBody.maxX})
            for(double y:new double[]{worldBody.minY,worldBody.maxY})
                for(double z:new double[]{worldBody.minZ,worldBody.maxZ}) {
                    var p=pose.toStorage(new Vec3(x,y,z));
                    minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);minZ=Math.min(minZ,p.z);
                    maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);maxZ=Math.max(maxZ,p.z);
                }
        return !new AABB(minX,minY,minZ,maxX,maxY,maxZ).intersects(new AABB(target));
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
