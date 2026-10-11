// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.sable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.compat.sable.SableStructures;

/**
 * 读 Sable 物理结构的读写端（客户端）：客户端子世界容器里和区域沾边的子世界——编号、名字、世界里的范围、
 * 这一刻与上一刻的位姿、地块里方块的范围；角色正被哪个子世界带着（站在上面或坐着）；地块里一格的方块
 * 只从已经拿到的地块区块读，没有就答没加载，不为读而去加载；装在地块里的实体按位姿把范围换到世界。只翻译，不判断。
 */
public final class SableStructureReads implements SableStructures {

    @Override public List<Structure> near(ClientLevel level, Player viewer, AABB region) {
        List<Structure> found = new ArrayList<>();
        SubLevel carrying = viewer == null ? null : Sable.HELPER.getTrackingOrVehicleSubLevel(viewer);
        for (SubLevel subLevel : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(region))) {
            if (subLevel.isRemoved()) continue;
            BoundingBox3dc world = subLevel.boundingBox();
            BoundingBox3ic local = subLevel.getPlot().getBoundingBox();
            String name = subLevel.getName();
            found.add(new Structure(subLevel.getUniqueId().toString(), name == null ? "" : name,
                    new AABB(world.minX(), world.minY(), world.minZ(), world.maxX(), world.maxY(), world.maxZ()),
                    pose(subLevel.logicalPose()), pose(subLevel.lastPose()),
                    new BlockPos(local.minX(), local.minY(), local.minZ()),
                    new BlockPos(local.maxX(), local.maxY(), local.maxZ()), subLevel == carrying));
        }
        return found;
    }

    @Override public BlockState block(ClientLevel level, String id, BlockPos local) {
        SubLevel subLevel = subLevel(level, id);
        if (subLevel == null) return null;
        LevelPlot plot = subLevel.getPlot();
        PlotChunkHolder holder = plot.getChunkHolder(plot.toLocal(new ChunkPos(local)));
        LevelChunk chunk = holder == null ? null : holder.getChunk();
        return chunk == null ? null : chunk.getBlockState(local);
    }

    @Override public List<AABB> attachedEntities(ClientLevel level, String id) {
        SubLevel subLevel = subLevel(level, id);
        if (subLevel == null) return List.of();
        BoundingBox3ic local = subLevel.getPlot().getBoundingBox();
        AABB plot = new AABB(local.minX(), local.minY(), local.minZ(), local.maxX() + 1, local.maxY() + 1,
                local.maxZ() + 1).inflate(2);
        Pose pose = pose(subLevel.logicalPose());
        List<AABB> boxes = new ArrayList<>();
        // 装在地块里的实体（船上的螺旋桨、风帆装置）：八个角按位姿换到世界里取包围盒。
        for (Entity entity : level.getEntities((Entity) null, plot, Entity::isAlive)) {
            AABB box = entity.getBoundingBox();
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (int bits = 0; bits < 8; bits++) {
                Vec3 corner = pose.toWorld(new Vec3((bits & 1) == 0 ? box.minX : box.maxX,
                        (bits & 2) == 0 ? box.minY : box.maxY, (bits & 4) == 0 ? box.minZ : box.maxZ));
                minX = Math.min(minX, corner.x);
                minY = Math.min(minY, corner.y);
                minZ = Math.min(minZ, corner.z);
                maxX = Math.max(maxX, corner.x);
                maxY = Math.max(maxY, corner.y);
                maxZ = Math.max(maxZ, corner.z);
            }
            boxes.add(new AABB(minX, minY, minZ, maxX, maxY, maxZ));
        }
        return boxes;
    }

    private static SubLevel subLevel(ClientLevel level, String id) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        try {
            return container.getSubLevel(UUID.fromString(id));
        } catch (IllegalArgumentException wrong) {
            return null;
        }
    }

    private static Pose pose(Pose3dc pose) {
        return new Pose(new Vec3(pose.position().x(), pose.position().y(), pose.position().z()),
                new double[]{pose.orientation().x(), pose.orientation().y(), pose.orientation().z(), pose.orientation().w()},
                new Vec3(pose.rotationPoint().x(), pose.rotationPoint().y(), pose.rotationPoint().z()),
                new Vec3(pose.scale().x(), pose.scale().y(), pose.scale().z()));
    }
}
