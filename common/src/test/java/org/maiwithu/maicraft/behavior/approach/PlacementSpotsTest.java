// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/** 放置站位：靠近的目标是被点的那一面，薄框贴在面上；放下去会卡住自己的站位不许站。 */
class PlacementSpotsTest {

    @Test
    void 面上的框贴在那一面() {
        BlockPos clicked = new BlockPos(3, 64, -2);
        AABB up = PlacementSpots.faceBox(clicked, Direction.UP);
        assertEquals(65.0, up.maxY, 1e-9);
        assertTrue(up.minY > 64.9 && up.minX == 3 && up.maxX == 4, "贴在顶面、盖住整个面");
        AABB west = PlacementSpots.faceBox(clicked, Direction.WEST);
        assertEquals(3.0, west.minX, 1e-9);
        assertTrue(west.maxX < 3.1 && west.minY == 64 && west.maxY == 65);
        ApproachTarget target = PlacementSpots.faceTarget(clicked, Direction.NORTH);
        assertEquals(clicked, target.block());
        assertEquals(InteractionKind.BLOCK, target.kind());
        assertTrue(target.center().z < -1.9, "目标中心在北面上");
    }

    @Test
    void 放下去会卡住自己的站位不许站() {
        BlockPos cell = new BlockPos(0, 65, 0);
        ProtectedCells avoid = PlacementSpots.avoiding(Set.of(cell), at -> at.equals(new BlockPos(9, 9, 9)));
        assertTrue(avoid.contains(cell), "脚下就是要放的格");
        assertTrue(avoid.contains(cell.below()), "头顶是要放的格");
        assertFalse(avoid.contains(cell.east()));
        assertTrue(avoid.contains(new BlockPos(9, 9, 9)), "别的受保护格照旧");
    }
}
