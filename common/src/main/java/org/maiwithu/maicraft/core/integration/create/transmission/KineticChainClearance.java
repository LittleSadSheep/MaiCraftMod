// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Sweeps installed Create's 1.25-radius wheel footprint and both straight tangent strands, not just endpoints. */
final class KineticChainClearance {
    private static final double RADIUS = 1.25, TANGENT_ANGLE = Math.toRadians(35), STRAND_PADDING = .2;
    private KineticChainClearance() {}
    static boolean clear(KineticGeometryWork work, List<BlockPos> wheels) {
        Set<BlockPos> wheelCenters = Set.copyOf(wheels), swept = new HashSet<>();
        // The wheel projects out of its block into every neighboring horizontal cell, including diagonals.
        for (BlockPos wheel : wheels) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) swept.add(wheel.offset(dx, 0, dz));
        for (int i = 1; i < wheels.size(); i++) {
            BlockPos a = wheels.get(i - 1), b = wheels.get(i);
            double horizontal = Math.hypot((double) b.getX() - a.getX(), (double) b.getZ() - a.getZ());
            if (horizontal <= 1.5) return false;
            double ux = (b.getX() - a.getX()) / horizontal, uz = (b.getZ() - a.getZ()) / horizontal;
            double inward = RADIUS * Math.cos(TANGENT_ANGLE), sideways = RADIUS * Math.sin(TANGENT_ANGLE);
            for (int sign : new int[] {-1, 1}) {
                // Mirrors ChainConveyorBlockEntity.calculateConnectionStats and forPointsAlongChains.
                Vec3 from = new Vec3(a.getX() + .5 + ux * inward - uz * sideways * sign, a.getY() + .375,
                        a.getZ() + .5 + uz * inward + ux * sideways * sign);
                Vec3 to = new Vec3(b.getX() + .5 - ux * inward - uz * sideways * sign, b.getY() + .375,
                        b.getZ() + .5 - uz * inward + ux * sideways * sign);
                sweep(from, to, swept);
            }
        }
        for (BlockPos at : swept) {
            KineticRouteGeometry.checkpoint();
            if (!work.terrain.loaded(at) || work.terrain.protectedCell(at)) return false;
            if (wheelCenters.contains(at)) continue; // Exact planned or observed wheel centers are the only occupied exceptions.
            if (work.blocks.containsKey(at) || !work.terrain.passable(at) || work.terrain.kinetic(at)) return false;
            Integer ground = work.ground(at.getX(), at.getZ());
            if (ground == null || !endpointColumn(work, at) && at.getY() - ground - 1 < work.limits.clearance()) return false;
        }
        return true;
    }
    private static void sweep(Vec3 from, Vec3 to, Set<BlockPos> cells) {
        int count = Math.max(1, (int) Math.ceil(from.distanceTo(to) * 2));
        for (int step = 1; step <= count; step++) {
            Vec3 a = from.lerp(to, (step - 1.0) / count), b = from.lerp(to, (double) step / count);
            // The union of segment boxes covers the complete continuous strand, including sloped voxel crossings.
            for (int x = (int) Math.floor(Math.min(a.x, b.x) - STRAND_PADDING); x <= (int) Math.floor(Math.max(a.x, b.x) + STRAND_PADDING); x++)
                for (int y = (int) Math.floor(Math.min(a.y, b.y) - STRAND_PADDING); y <= (int) Math.floor(Math.max(a.y, b.y) + STRAND_PADDING); y++)
                    for (int z = (int) Math.floor(Math.min(a.z, b.z) - STRAND_PADDING); z <= (int) Math.floor(Math.max(a.z, b.z) + STRAND_PADDING); z++)
                        cells.add(new BlockPos(x, y, z));
        }
    }
    private static boolean endpointColumn(KineticGeometryWork work, BlockPos at) {
        return at.getX() == work.source.position().getX() && at.getZ() == work.source.position().getZ()
                || at.getX() == work.target.position().getX() && at.getZ() == work.target.position().getZ();
    }
}
