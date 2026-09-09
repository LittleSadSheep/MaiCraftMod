// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * 规划器列出建完后必须能通行的位置，例如屋外、门口、各层房间和码头两端。
 * 这些坐标是需要验证的要求，不是已通过的证明；BuildTraversabilityVerifier 会重新读取现场检查。
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

    /**
     * 室内可走脚下格的范围，最小和最大边界都包含。它不是整个建筑所有墙、顶和地基的范围。
     */
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

    /**
     * 要求一列从 bottomY 连到 topY 的可攀爬方块，用于连接楼层；当前表达不了拐弯楼梯。
     */
    public record VerticalLink(int x, int z, int bottomY, int topY) {}

    /**
     * 从岸边到码头末端的中心线；验收器要求同高度、沿 x 或 z 的直线。
     */
    public record DockPath(Cell houseSide, Cell deckEnd) {}
}
