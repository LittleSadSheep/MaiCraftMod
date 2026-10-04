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
    public FlightWorldProbe(BlockGetter world,Predicate<BlockPos> loaded,List<AABB> dynamic,boolean dynamicKnown,int readBudget) {
        this.world=world;this.loaded=loaded;this.dynamic=List.copyOf(dynamic);this.dynamicKnown=dynamicKnown;this.readBudget=readBudget;
    }
    public static FlightWorldProbe capture(ClientLevel level,SableStructureBridge.Structure own) {
        var obstacles=new ArrayList<AABB>();var frame=SableStructureBridge.open(level,own.pose().position(),null);
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
                if(own.id().equals(SableStructureBridge.containingId(level,entity.blockPosition())))continue;
                var parent=SableStructureBridge.containingPose(level,entity.blockPosition());
                obstacles.add(parent==null?entity.getBoundingBox():PhysicalObstacleSnapshot.transformBox(parent,entity.getBoundingBox(),true));
            } catch(RuntimeException unknown){complete=false;}
        }
        return new FlightWorldProbe(level,pos->level.getChunkSource().hasChunk(pos.getX()>>4,pos.getZ()>>4),obstacles,complete,100_000);
    }
    @Override public FlightPathProbe.Space observe(AABB swept) {
        if(!dynamicKnown)return UNKNOWN;
        for(AABB obstacle:dynamic)if(obstacle.intersects(swept))return BLOCKED;
        BlockPos min=BlockPos.containing(swept.minX,swept.minY,swept.minZ);
        BlockPos max=BlockPos.containing(Math.nextDown(swept.maxX),Math.nextDown(swept.maxY),Math.nextDown(swept.maxZ));
        for(BlockPos mutable:BlockPos.betweenClosed(min,max)) {
            BlockPos pos=mutable.immutable();
            if(pos.getY()<world.getMinBuildHeight()||pos.getY()>=world.getMaxBuildHeight())continue;
            if(!loaded.test(pos))return UNKNOWN;
            List<AABB> boxes=shapes.get(pos);
            if(boxes==null) {
                if(reads>=readBudget)return UNKNOWN;
                reads++;
                try {
                    boxes=world.getBlockState(pos).getCollisionShape(world,pos).toAabbs().stream().map(box->box.move(pos)).toList();
                    shapes.put(pos,boxes);
                } catch(RuntimeException|LinkageError unknown){return UNKNOWN;}
            }
            for(AABB box:boxes)if(box.intersects(swept))return BLOCKED;
        }
        return CLEAR;
    }
    public int blockReads(){return reads;}
}
