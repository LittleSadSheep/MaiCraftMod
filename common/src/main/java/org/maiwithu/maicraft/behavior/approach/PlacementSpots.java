// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * 放置站位：站位判断的一种用法。放一格方块要点旁边某块的某一面，所以靠近的目标是"那一面"而不是整格；
 * 站得稳、够得着、看得见都按那一面判。放下去之后身体不能卡在里面：脚下格与头顶格都不能是要放的格。
 * 纯函数，不读世界。
 */
public final class PlacementSpots {

    /** 面上框的厚度：薄薄一片贴在被点的那一面上，瞄准点落在里面就是点中了这一面。 */
    static final double FACE_DEPTH = 0.02;

    private PlacementSpots() {}

    /** 靠近的目标：点 {@code clicked} 的 {@code face} 那一面。 */
    public static ApproachTarget faceTarget(BlockPos clicked, Direction face) {
        return ApproachTarget.ofBlockPart(clicked, faceBox(clicked, face));
    }

    /** 贴在那一面上的薄框（世界坐标）。 */
    public static AABB faceBox(BlockPos clicked, Direction face) {
        AABB cell = new AABB(clicked);
        return switch (face) {
            case DOWN -> new AABB(cell.minX, cell.minY, cell.minZ, cell.maxX, cell.minY + FACE_DEPTH, cell.maxZ);
            case UP -> new AABB(cell.minX, cell.maxY - FACE_DEPTH, cell.minZ, cell.maxX, cell.maxY, cell.maxZ);
            case NORTH -> new AABB(cell.minX, cell.minY, cell.minZ, cell.maxX, cell.maxY, cell.minZ + FACE_DEPTH);
            case SOUTH -> new AABB(cell.minX, cell.minY, cell.maxZ - FACE_DEPTH, cell.maxX, cell.maxY, cell.maxZ);
            case WEST -> new AABB(cell.minX, cell.minY, cell.minZ, cell.minX + FACE_DEPTH, cell.maxY, cell.maxZ);
            case EAST -> new AABB(cell.maxX - FACE_DEPTH, cell.minY, cell.minZ, cell.maxX, cell.maxY, cell.maxZ);
        };
    }

    /**
     * 放下这些格之后身体不能在里面：脚下格或头顶格是其中之一的站位不许站。
     * 和别的受保护格判断叠起来用。
     */
    public static ProtectedCells avoiding(Set<BlockPos> placed, ProtectedCells others) {
        Set<BlockPos> cells = Set.copyOf(placed);
        return feet -> others.contains(feet) || cells.contains(feet) || cells.contains(feet.above());
    }
}
