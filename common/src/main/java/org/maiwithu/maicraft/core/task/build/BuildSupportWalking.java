// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.baritone.GroundCorridor;

/** Existing/proposed footing only: cardinal walking and one-block steps, never digging or extra supports. */
final class BuildSupportWalking {
    private static final double EPS = 1e-5;
    private final BlockGetter world;
    private final LongSet forbidden;
    private final PhysicalObstacleSnapshot physical;
    private final double width, height;
    private final GroundCorridor ground;

    BuildSupportWalking(BlockGetter world, java.util.function.Predicate<BlockPos> loaded,
                        double width, double height, LongSet forbidden, PhysicalObstacleSnapshot physical) {
        this.world = world; this.width = width; this.height = height;
        this.forbidden = forbidden; this.physical = physical;
        ground = new GroundCorridor(world, loaded, width, height, forbidden, physical);
    }

    Vec3 stance(BlockPos cell) { return ground.stance(cell); }

    boolean edge(Vec3 from, Vec3 to) {
        double rise = to.y - from.y;
        if (Math.abs(rise) > 1.0 + EPS || from.distanceToSqr(to) > 3) return false;
        if (Math.abs(rise) < EPS) return ground.clear(from, to);
        Vec3 bend = rise > 0 ? new Vec3(from.x, to.y, from.z) : new Vec3(to.x, from.y, to.z);
        return sweep(from, bend) && sweep(bend, to);
    }

    private boolean sweep(Vec3 from, Vec3 to) {
        if (!physical.clearSegment(from, to, width, height)) return false;
        double half = width / 2;
        AABB area = new AABB(Math.min(from.x, to.x) - half - 1, Math.min(from.y, to.y) - 1,
                Math.min(from.z, to.z) - half - 1, Math.max(from.x, to.x) + half + 1,
                Math.max(from.y, to.y) + height + 1, Math.max(from.z, to.z) + half + 1);
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(area.minX, area.minY, area.minZ),
                BlockPos.containing(area.maxX, area.maxY, area.maxZ))) {
            BlockState state = world.getBlockState(pos);
            if ((forbidden.contains(pos.asLong())
                    || org.maiwithu.maicraft.core.pathing.transport.TransportLanding.unsafe(world, pos, state))
                    && touches(new AABB(pos).inflate(EPS * 2), from, to)) return false;
            for (AABB box : state.getCollisionShape(world, pos).toAabbs())
                if (touches(box.move(pos), from, to)) return false;
        }
        return true;
    }

    private boolean touches(AABB obstacle, Vec3 from, Vec3 to) {
        AABB expanded = new AABB(obstacle.minX - width / 2 + EPS, obstacle.minY - height + EPS,
                obstacle.minZ - width / 2 + EPS, obstacle.maxX + width / 2 - EPS,
                obstacle.maxY - EPS, obstacle.maxZ + width / 2 - EPS);
        return expanded.contains(from) || expanded.contains(to) || expanded.clip(from, to).isPresent();
    }
}
