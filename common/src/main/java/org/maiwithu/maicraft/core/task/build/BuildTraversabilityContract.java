// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Internal, planner-authored endpoints for proving that a completed semantic build is usable.
 *
 * <p>This is deliberately separate from the public semantic goal and from per-cell construction
 * targets. The planner names only the human-scale connections that the finished structure must
 * provide; {@link BuildTraversabilityVerifier} re-reads the actual client world and proves those
 * connections instead of trusting planner claims.
 */
public record BuildTraversabilityContract(
        Cell exteriorApproach,
        Cell entranceDoor,
        Cell interiorEntry,
        Bounds interiorBounds,
        List<Cell> floorWaypoints,
        VerticalLink verticalLink,
        DockPath dockPath) {

    public BuildTraversabilityContract {
        floorWaypoints = floorWaypoints == null ? List.of() : List.copyOf(floorWaypoints);
    }

    public record Cell(int x, int y, int z) {
        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    /** Inclusive bounds for feet positions reachable inside the structure. */
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        public long volume() {
            long width = (long) maxX - minX + 1L;
            long height = (long) maxY - minY + 1L;
            long depth = (long) maxZ - minZ + 1L;
            return width <= 0 || height <= 0 || depth <= 0 ? 0L : width * height * depth;
        }
    }

    /** A continuous climbable column connecting all generated storeys. */
    public record VerticalLink(int x, int z, int bottomY, int topY) {}

    /** Straight centre-line of a dock, from the house/shore side to the deck end. */
    public record DockPath(Cell houseSide, Cell deckEnd) {}
}
