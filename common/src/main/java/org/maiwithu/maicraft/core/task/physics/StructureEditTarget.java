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
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.StructurePose;

/** 按原生轮廓选择可见的支撑面；半砖和楼梯不能按整格中心猜测点击位置。 */
final class StructureEditTarget {
    record Click(BlockPos support, Direction face, Vec3 world,int preference,Vec3 preciseStorage) {
        Click(BlockPos support,Direction face,Vec3 world){this(support,face,world,0,null);}
        Click(BlockPos support,Direction face,Vec3 world,int preference){this(support,face,world,preference,null);}
    }
    private StructureEditTarget() {}
    /** 扳手使用面角点避开常见的居中旋钮；保留本地精确点，转头尚未到位时不能误点同一面的设置区。 */
    static List<Click> wrenchTargets(BlockGetter level,Predicate<BlockPos> loaded,StructurePose pose,BlockPos target) {
        if(!loaded.test(target))return List.of();
        var result=new ArrayList<Click>();
        for(AABB box:level.getBlockState(target).getShape(level,target).toAabbs())for(Direction face:Direction.values()) {
            Vec3 middle=face(box,face);
            for(double a:new double[]{.06,.94})for(double b:new double[]{.06,.94}) {
                Vec3 local=switch(face.getAxis()) {
                    case X -> new Vec3(middle.x,box.minY+a*box.getYsize(),box.minZ+b*box.getZsize());
                    case Y -> new Vec3(box.minX+a*box.getXsize(),middle.y,box.minZ+b*box.getZsize());
                    case Z -> new Vec3(box.minX+a*box.getXsize(),box.minY+b*box.getYsize(),middle.z);
                };
                Vec3 storage=local.add(Vec3.atLowerCornerOf(target));
                result.add(new Click(target,face,pose.toWorld(storage),0,storage));
            }
        }
        return List.copyOf(result);
    }
    static boolean precise(Click click,BlockHitResult hit) {
        return hit!=null&&(click.preciseStorage()==null||hit.getLocation().distanceToSqr(click.preciseStorage())<=.08*.08);
    }
    // 候选面只描述可点击几何；不能把最近但被甲板顶面挡住的外侧面冒充可见施工面。
    static List<Click> targets(BlockGetter level,Predicate<BlockPos> loaded,StructurePose pose,BlockPos target,boolean placing) {
        return targets(level,loaded,pose,target,placing,null);
    }
    static List<Click> targets(BlockGetter level,Predicate<BlockPos> loaded,StructurePose pose,BlockPos target,boolean placing,SlabType half) {
        var result=new ArrayList<Click>();
        for(Direction side:Direction.values()) {
            BlockPos support=placing?target.relative(side):target;
            if(!loaded.test(support)) continue;
            Direction face=placing?side.getOpposite():side;
            for(AABB box:level.getBlockState(support).getShape(level,support).toAabbs()) {
                Vec3 point=face(box,face);int preference=0;
                if(placing&&half!=null&&half!=SlabType.DOUBLE) {
                    // 半砖侧面点击离开中线，避免镜头误差把下半砖放成上半砖；相反的顶底面仍保留为后备原生操作。
                    if(face.getAxis()!=Direction.Axis.Y) {
                        point=new Vec3(point.x,Math.clamp(half==SlabType.BOTTOM?.25:.75,box.minY+.00001,box.maxY-.00001),point.z);
                        if((half==SlabType.BOTTOM)!=(point.y<=.5))preference=1;
                    }
                    else if(face!=(half==SlabType.BOTTOM?Direction.UP:Direction.DOWN))preference=1;
                }
                result.add(new Click(support,face,pose.toWorld(point.add(Vec3.atLowerCornerOf(support))),preference));
            }
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
                .sorted(Comparator.comparingInt(Click::preference).thenComparingDouble(c->c.world().distanceToSqr(eye))).filter(c->{
                    var hit=ray.apply(eye,c.world());
                    return hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(c.support())&&hit.getDirection()==c.face()&&precise(c,hit);
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
