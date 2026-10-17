// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

/** 末地的地理事实：主岛外岛以 512 格为界、跨没跨虚空、主岛那一圈 20 个开门位置、门大概送到哪。 */
class EndIslandsTest {

    @Test
    void 离中心五百一十二格内是主岛_外面是外岛() {
        assertEquals(EndIslands.Side.MAIN_ISLAND, EndIslands.sideOf(new BlockPos(0, 64, 0)));
        assertEquals(EndIslands.Side.MAIN_ISLAND, EndIslands.sideOf(new BlockPos(512, 64, 0)));
        assertEquals(EndIslands.Side.MAIN_ISLAND, EndIslands.sideOf(new BlockPos(300, 64, -300)));
        assertEquals(EndIslands.Side.OUTER_ISLANDS, EndIslands.sideOf(new BlockPos(513, 64, 0)));
        assertEquals(EndIslands.Side.OUTER_ISLANDS, EndIslands.sideOf(new BlockPos(-1100, 60, 400)));
    }

    @Test
    void 一头主岛一头外岛才是隔着虚空() {
        assertTrue(EndIslands.acrossVoid(new BlockPos(10, 64, 10), new BlockPos(1100, 64, 0)));
        assertTrue(EndIslands.acrossVoid(new BlockPos(0, 64, 1200), new BlockPos(96, 75, 0)));
        assertFalse(EndIslands.acrossVoid(new BlockPos(1100, 64, 0), new BlockPos(1300, 70, 600)),
                "外岛之间算同一片，走不走得通交给走路");
        assertFalse(EndIslands.acrossVoid(new BlockPos(0, 64, 0), new BlockPos(90, 64, 20)));
    }

    @Test
    void 那一圈二十个位置与原版开门的算式一致() {
        List<BlockPos> slots = EndIslands.ringSlots();
        assertEquals(20, slots.size());
        assertEquals(20, new HashSet<>(slots).size(), "二十个位置各不相同");
        // 原版第 0 扇在正东、第 5 扇在正南（余弦取整落在 -1）。
        assertEquals(new BlockPos(96, 75, 0), slots.get(0));
        assertEquals(new BlockPos(-1, 75, 96), slots.get(5));
        for (BlockPos slot : slots) {
            assertEquals(75, slot.getY());
            double radius = Math.sqrt(slot.getX() * slot.getX() + slot.getZ() * slot.getZ());
            assertTrue(radius > 94 && radius < 98, slot + " 离中心约 96 格");
            assertEquals(EndIslands.Side.MAIN_ISLAND, EndIslands.sideOf(slot));
        }
    }

    @Test
    void 主岛的门送到同方向一千多格外_外岛的门送回那一圈() {
        BlockPos out = EndIslands.exitEstimate(new BlockPos(96, 75, 0));
        assertEquals(new BlockPos(1024, 64, 0), out);
        assertEquals(EndIslands.Side.OUTER_ISLANDS, EndIslands.sideOf(out));

        BlockPos back = EndIslands.exitEstimate(new BlockPos(0, 80, -1100));
        assertEquals(new BlockPos(0, 75, -96), back);
        assertEquals(EndIslands.Side.MAIN_ISLAND, EndIslands.sideOf(back));
    }
}
