package org.maiwithu.maicraft.core.integration.create;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import org.maiwithu.maicraft.core.integration.physics.StructurePose;

/**
 * 读取 Create 运动结构的各个方块碰撞，并转换到世界位置，避免把整座有门洞的结构当成实心盒子。读取失败或超预算时退回整体范围。
 */
public final class ContraptionObstacles {
    private static final Api API=Api.load();
    private static WeakReference<ClientLevel> cachedLevel=new WeakReference<>(null);
    private static Vec3 cachedOrigin;
    private static long cachedTick=Long.MIN_VALUE;
    private static PhysicalObstacleSnapshot cached=PhysicalObstacleSnapshot.EMPTY;
    private ContraptionObstacles() {}
    // 同世界、同一游戏刻且关注位置移动不足一格时复用结果；下一刻重新看结构与门的位置。
    public static PhysicalObstacleSnapshot capture(ClientLevel level,Vec3 focus) {
        if(API==null) return PhysicalObstacleSnapshot.EMPTY;
        if(cachedLevel.get()==level && cachedTick==level.getGameTime() && cachedOrigin.distanceToSqr(focus)<1) return cached;
        var boxes=new ArrayList<AABB>(); int reads=0, conservative=0;
        AABB interest=new AABB(focus,focus).inflate(16);
        // 飞艇上的桨叶实体保存在地块存储区，先从已加载实体识别装置，再用真实世界范围筛选，不能先按原始坐标漏掉它。
        for(Entity entity:level.entitiesForRendering()) {
            if(!API.entity.isInstance(entity)||!entity.isAlive())continue;
            int first=boxes.size();
            AABB fallback=interest;
            try {
                StructurePose parent=SableStructureBridge.containingPose(level,entity.blockPosition());
                fallback=parent==null?entity.getBoundingBox():PhysicalObstacleSnapshot.transformBox(parent,entity.getBoundingBox(),true);
                if(!fallback.intersects(interest))continue;
                if(reads>=4096 || boxes.size()>=4096) throw new IllegalStateException("voxel budget exhausted");
                Object contraption=API.contraption.invoke(entity);
                if(contraption==null) continue;
                @SuppressWarnings("unchecked") var blocks=(Map<BlockPos,StructureBlockInfo>)API.blocks.invoke(contraption);
                var view=(BlockGetter)API.world.invoke(contraption);
                Vec3 origin=(Vec3)API.global.invoke(entity,Vec3.ZERO,1F);
                Vec3 x=((Vec3)API.global.invoke(entity,new Vec3(1,0,0),1F)).subtract(origin);
                Vec3 y=((Vec3)API.global.invoke(entity,new Vec3(0,1,0),1F)).subtract(origin);
                Vec3 z=((Vec3)API.global.invoke(entity,new Vec3(0,0,1),1F)).subtract(origin);
                UnaryOperator<Vec3> transform=worldTransform(p->origin.add(x.scale(p.x)).add(y.scale(p.y)).add(z.scale(p.z)),parent);
                for(var entry:blocks.entrySet()) {
                    if(reads++>=4096) throw new IllegalStateException("voxel budget exhausted");
                    if(!worldBox(new AABB(entry.getKey()).inflate(1),transform).intersects(interest)) continue;
                    if(Boolean.TRUE.equals(API.hidden.invoke(contraption,entry.getKey()))) continue;
                    var shape=entry.getValue().state().getCollisionShape(view,entry.getKey());
                    var parts=shape.toAabbs();
                    if(parts.size()>64 || boxes.size()+parts.size()>4096) throw new IllegalStateException("shape budget exhausted");
                    for(AABB part:parts) {
                        AABB obstacle=worldBox(part.move(entry.getKey()),transform);
                        if(obstacle.intersects(interest)) boxes.add(obstacle);
                    }
                }
            } catch(ReflectiveOperationException | RuntimeException | LinkageError unknown) {
                boxes.subList(first,boxes.size()).clear(); boxes.add(fallback); conservative++;
            }
            if(boxes.size()>4096) { boxes.clear(); boxes.add(interest); conservative++; break; }
        }
        cachedLevel=new WeakReference<>(level); cachedTick=level.getGameTime(); cachedOrigin=focus;
        return cached=new PhysicalObstacleSnapshot(boxes,Math.min(reads,4096),conservative,
                conservative>0 ? "create_partial" : "create_observed");
    }
    // 先执行桨叶自身的转动和平移，再执行飞艇的转动、缩放和平移；普通世界装置只变换一次。
    static UnaryOperator<Vec3> worldTransform(UnaryOperator<Vec3> localToPlot,StructurePose parent) {
        return parent==null?localToPlot:p->parent.toWorld(localToPlot.apply(p));
    }
    static AABB worldBox(AABB box,UnaryOperator<Vec3> transform) {
        Vec3 first=transform.apply(new Vec3(box.minX,box.minY,box.minZ));
        double minX=first.x,minY=first.y,minZ=first.z,maxX=first.x,maxY=first.y,maxZ=first.z;
        for(int bits=1;bits<8;bits++) {
            Vec3 point=transform.apply(new Vec3((bits&1)==0?box.minX:box.maxX,(bits&2)==0?box.minY:box.maxY,(bits&4)==0?box.minZ:box.maxZ));
            minX=Math.min(minX,point.x); minY=Math.min(minY,point.y); minZ=Math.min(minZ,point.z);
            maxX=Math.max(maxX,point.x); maxY=Math.max(maxY,point.y); maxZ=Math.max(maxZ,point.z);
        }
        return new AABB(minX,minY,minZ,maxX,maxY,maxZ);
    }
    private record Api(Class<?> entity,Method contraption,Method global,Method blocks,Method world,Method hidden) {
        static Api load() {
            Class<?> entity;
            try { entity=Class.forName("com.simibubi.create.content.contraptions.AbstractContraptionEntity"); }
            catch(ClassNotFoundException | LinkageError absent) { return null; }
            try {
                Class<?> contraption=Class.forName("com.simibubi.create.content.contraptions.Contraption");
                return new Api(entity,entity.getMethod("getContraption"),entity.getMethod("toGlobalVector",Vec3.class,float.class),
                        contraption.getMethod("getBlocks"),contraption.getMethod("getContraptionWorld"),contraption.getMethod("isHiddenInPortal",BlockPos.class));
            } catch(ReflectiveOperationException | LinkageError unknown) { return new Api(entity,null,null,null,null,null); }
        }
    }
}
