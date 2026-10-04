package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.function.Predicate;
import org.maiwithu.maicraft.core.integration.machine.control.ControlReflection;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.integration.physics.SableStructureBridge;
import org.maiwithu.maicraft.core.integration.create.ContraptionObstacles;
import static org.maiwithu.maicraft.core.integration.physics.flight.FlightPathProbe.Space.*;

/** 一次航路更新共用只读碰撞缓存；地形、其他物理船与原生运动装置均参与，自己船上的桨叶不算外部障碍。 */
public final class FlightWorldProbe implements FlightPathProbe.World {
    private final BlockGetter world;
    private final Predicate<BlockPos> loaded;
    private final List<AABB> dynamic;
    private final boolean dynamicKnown;
    private final Map<BlockPos,List<AABB>> shapes=new HashMap<>();
    private final int readBudget;
    private int reads;
    private Map<String,Object> lastObservation=Map.of();
    private List<AABB> ownMovingParts=List.of();
    public FlightWorldProbe(BlockGetter world,Predicate<BlockPos> loaded,List<AABB> dynamic,boolean dynamicKnown,int readBudget) {
        this.world=world;this.loaded=loaded;this.dynamic=List.copyOf(dynamic);this.dynamicKnown=dynamicKnown;this.readBudget=readBudget;
    }
    public static FlightWorldProbe capture(ClientLevel level,SableStructureBridge.Structure own) {
        var obstacles=new ArrayList<AABB>();var ownParts=new ArrayList<AABB>();var frame=SableStructureBridge.open(level,own.pose().position(),null);
        boolean complete=!frame.truncated()&&frame.error()==null;
        for(var other:frame.structures()) {
            if(own.id().equals(other.id()))continue;
            if(other.worldBounds()==null){complete=false;continue;}
            AABB box=other.worldBounds();
            // 外部船体用最近原生姿态差扩展两秒运动范围，不把正在横穿航路的船当静止点。
            if(other.lastPose()!=null&&other.pose()!=null)box=box.expandTowards(other.pose().position().subtract(other.lastPose().position()).scale(40));
            obstacles.add(box);
        }
        for(var entity:level.entitiesForRendering()) {
            if(!entity.isAlive()||!ControlReflection.is(entity,"com.simibubi.create.content.contraptions.AbstractContraptionEntity"))continue;
            try {
                var parent=SableStructureBridge.containingPose(level,entity.blockPosition());
                var bounds=parent==null?entity.getBoundingBox():PhysicalObstacleSnapshot.transformBox(parent,entity.getBoundingBox(),true);
                if(own.id().equals(SableStructureBridge.containingId(level,entity.blockPosition()))) {
                    // 自己的转子使用真实桨叶碰撞，避免实体外围的空包围盒把下方地面误认成起飞障碍。
                    var parts=ContraptionObstacles.captureEntity(level,entity);
                    if(parts.conservativeStructures()>0)complete=false;
                    ownParts.addAll(parts.boxes());
                } else obstacles.add(bounds);
            } catch(RuntimeException unknown){complete=false;}
        }
        var probe=new FlightWorldProbe(level,pos->level.getChunkSource().hasChunk(pos.getX()>>4,pos.getZ()>>4),obstacles,complete,100_000);
        probe.ownMovingParts=List.copyOf(ownParts);return probe;
    }
    @Override public FlightPathProbe.Space observe(AABB swept) {
        // 只保存本次查询的阻挡或未知原因，让起飞回执指出究竟是地面、树木、运动结构还是读取范围。
        lastObservation=Map.of();
        if(!dynamicKnown){lastObservation=Map.of("reason","dynamic_obstacles_incomplete");return UNKNOWN;}
        for(AABB obstacle:dynamic)if(obstacle.intersects(swept)){lastObservation=Map.of("reason","dynamic_obstacle","bounds",obstacle);return BLOCKED;}
        BlockPos min=BlockPos.containing(swept.minX,swept.minY,swept.minZ);
        BlockPos max=BlockPos.containing(Math.nextDown(swept.maxX),Math.nextDown(swept.maxY),Math.nextDown(swept.maxZ));
        for(BlockPos mutable:BlockPos.betweenClosed(min,max)) {
            BlockPos pos=mutable.immutable();
            if(pos.getY()<world.getMinBuildHeight()||pos.getY()>=world.getMaxBuildHeight())continue;
            if(!loaded.test(pos)){lastObservation=Map.of("reason","unloaded_cell","position",pos.toShortString());return UNKNOWN;}
            List<AABB> boxes=shapes.get(pos);
            if(boxes==null) {
                if(reads>=readBudget){lastObservation=Map.of("reason","block_read_budget_exhausted");return UNKNOWN;}
                reads++;
                try {
                    boxes=world.getBlockState(pos).getCollisionShape(world,pos).toAabbs().stream().map(box->box.move(pos)).toList();
                    shapes.put(pos,boxes);
                } catch(RuntimeException|LinkageError unknown){lastObservation=Map.of("reason","collision_shape_unavailable","position",pos.toShortString());return UNKNOWN;}
            }
            for(AABB box:boxes)if(box.intersects(swept)) {
                lastObservation=Map.of("reason","native_block_collision","position",pos.toShortString(),
                        "block_state",world.getBlockState(pos).toString(),"bounds",box);return BLOCKED;
            }
        }
        return CLEAR;
    }
    public int blockReads(){return reads;}
    public Map<String,Object> lastObservation(){return lastObservation;}
    List<AABB> ownMovingParts(){return ownMovingParts;}
    FlightPathProbe.Space unknownObservation(String reason){lastObservation=Map.of("reason",reason);return UNKNOWN;}
}
