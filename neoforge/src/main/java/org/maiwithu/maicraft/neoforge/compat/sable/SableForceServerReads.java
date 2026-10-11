// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.sable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import org.maiwithu.maicraft.server.compat.sable.StructureForceReads;

/**
 * 在服务端读 Sable 物理结构受力的读写端：子世界的质量记录（质量、重心、惯性）、物理系统里这具刚体的速度与角速度、
 * 这个世界那一处的重力；各处的力读子世界按力组记下的点力（最近一个物理子步的冲量，除以子步时长折成每秒），
 * 对重心求力矩。各处的力要子世界打开"逐个记下"才有：没开且要求时打开它（只是记账，不施力）。
 * 配重的质量按 Sable 自己的方块物理属性算。只翻译，不判断。
 */
public final class SableForceServerReads implements StructureForceReads {

    @Override public Optional<Forces> forces(ServerLevel level, String id, boolean track) {
        ServerSubLevel subLevel = subLevel(level, id);
        if (subLevel == null) return Optional.empty();
        MassData mass = subLevel.getMassTracker();
        Vector3dc center = mass.getCenterOfMass() == null ? new Vector3d() : mass.getCenterOfMass();
        Matrix3dc inertia = mass.getInertiaTensor();
        Vector3d world = subLevel.logicalPose().transformPosition(new Vector3d(center), new Vector3d());
        Vector3d gravity = DimensionPhysicsData.getGravity(level, world, new Vector3d());
        subLevel.logicalPose().orientation().transformInverse(gravity);
        Vector3d velocity = new Vector3d();
        Vector3d spin = new Vector3d();
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        RigidBodyHandle handle = system == null ? null : system.getPhysicsHandle(subLevel);
        if (handle != null) {
            handle.getLinearVelocity(velocity);
            handle.getAngularVelocity(spin);
        }
        boolean tracking = subLevel.isTrackingIndividualQueuedForces();
        if (!tracking && track) subLevel.enableIndividualQueuedForcesTracking(true);
        List<Push> pushes = new ArrayList<>();
        if (tracking && subLevel.getQueuedForceGroups() != null) {
            // 记下的是一个物理子步的冲量：除以子步时长折成每秒的力。
            int substeps = system == null ? 2 : system.getConfig().substepsPerTick;
            double step = 1.0 / 20.0 / Math.max(1, substeps);
            for (Map.Entry<ForceGroup, QueuedForceGroup> entry : subLevel.getQueuedForceGroups().entrySet()) {
                ResourceLocation name = ForceGroups.REGISTRY.getKey(entry.getKey());
                Vector3d force = new Vector3d();
                Vector3d torque = new Vector3d();
                int points = 0;
                for (QueuedForceGroup.PointForce point : entry.getValue().getRecordedPointForces()) {
                    force.add(point.force());
                    torque.add(new Vector3d(point.point()).sub(center).cross(point.force()));
                    points++;
                }
                if (points == 0) continue;
                pushes.add(new Push(name == null ? "unknown" : name.toString(), vec(force.div(step)),
                        vec(torque.div(step)), points));
            }
        }
        return Optional.of(new Forces(mass.getMass(), vec(new Vector3d(center)),
                new Vec3(inertia.m00(), inertia.m11(), inertia.m22()), vec(gravity), vec(velocity), vec(spin),
                tracking, pushes));
    }

    @Override public List<BlockPos> freeCells(ServerLevel level, String id, int limit) {
        List<BlockPos> cells = new ArrayList<>();
        ServerSubLevel subLevel = subLevel(level, id);
        if (subLevel == null) return cells;
        BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        for (BlockPos at : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(),
                bounds.maxY(), bounds.maxZ())) {
            if (cells.size() >= limit) break;
            if (level.getBlockState(at).isAir() && !level.getBlockState(at.below()).isAir()) cells.add(at.immutable());
        }
        return cells;
    }

    @Override public double blockMass(ServerLevel level, String id, BlockPos local, String blockId) {
        ResourceLocation key = ResourceLocation.tryParse(blockId);
        Block block = key == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(key);
        if (block == Blocks.AIR) return 0;
        return PhysicsBlockPropertyHelper.getMass(level, local, block.defaultBlockState());
    }

    @Override public Optional<Vec3> toWorld(ServerLevel level, String id, Vec3 local) {
        ServerSubLevel subLevel = subLevel(level, id);
        if (subLevel == null) return Optional.empty();
        return Optional.of(subLevel.logicalPose().transformPosition(local));
    }

    private static ServerSubLevel subLevel(ServerLevel level, String id) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        try {
            SubLevel found = container.getSubLevel(UUID.fromString(id));
            return found instanceof ServerSubLevel server && !server.isRemoved() ? server : null;
        } catch (IllegalArgumentException wrong) {
            return null;
        }
    }

    private static Vec3 vec(Vector3dc value) {
        return new Vec3(value.x(), value.y(), value.z());
    }
}
