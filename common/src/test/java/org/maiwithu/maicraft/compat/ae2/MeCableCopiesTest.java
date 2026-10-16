// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineCopy;

/** 读现场时 ME 线缆抄成一段段：每段一格接一格、后面一段挨着前面的，部件照抄，只有部件的格写进说明。 */
class MeCableCopiesTest {

    private static final String GLASS = "ae2:fluix_glass_cable";
    private static final String COVERED = "ae2:fluix_covered_cable";
    private static final BlockPos DRIVE = new BlockPos(0, 64, 0);

    private static Ae2Grid.GridCell cable(String item, Map<Direction, Ae2Grid.PartView> parts) {
        return new Ae2Grid.GridCell(true, Optional.ofNullable(item), Set.of(), parts, Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    @Test
    void 一条直线从挨着设备的那头接起_拐弯处照样连成一段() {
        // 驱动器东边一排三格，第三格往北拐一格：一段四格，从挨着驱动器的那一格起。
        Map<BlockPos, String> cables = new LinkedHashMap<>();
        cables.put(DRIVE.east(3).north(), GLASS);
        cables.put(DRIVE.east(3), GLASS);
        cables.put(DRIVE.east(2), GLASS);
        cables.put(DRIVE.east(), GLASS);
        List<Installation> runs = MeCableCopies.runs(cables, DRIVE::equals);
        assertEquals(1, runs.size());
        assertEquals(List.of(DRIVE.east(), DRIVE.east(2), DRIVE.east(3), DRIVE.east(3).north()), runs.getFirst().cells());
        assertEquals(GLASS, runs.getFirst().kind());
    }

    @Test
    void 分叉与换线缆种类_后面一段的头挨着前面接好的() {
        // 主干三格玻璃线缆，中间那格往南分出一格包层线缆。
        Map<BlockPos, String> cables = new LinkedHashMap<>();
        cables.put(DRIVE.east(), GLASS);
        cables.put(DRIVE.east(2), GLASS);
        cables.put(DRIVE.east(3), GLASS);
        cables.put(DRIVE.east(2).south(), COVERED);
        List<Installation> runs = MeCableCopies.runs(cables, DRIVE::equals);
        assertEquals(2, runs.size());
        assertEquals(List.of(DRIVE.east(), DRIVE.east(2), DRIVE.east(3)), runs.get(0).cells());
        assertEquals(COVERED, runs.get(1).kind());
        BlockPos head = runs.get(1).cells().getFirst();
        assertTrue(runs.get(0).cells().stream().anyMatch(laid -> laid.distManhattan(head) == 1),
                "第二段的头挨着第一段，照着建时点得到旁边那一格");
    }

    @Test
    void 部件照抄_只有部件没有线缆芯的格写进说明_读不到的也写() {
        BlockPos withPart = DRIVE.east();
        BlockPos onlyPart = DRIVE.above();
        BlockPos unread = DRIVE.east(2);
        Map<BlockPos, Ae2Grid.GridCell> read = Map.of(
                withPart, cable(GLASS, Map.of(Direction.UP, new Ae2Grid.PartView("ae2:terminal", true, true))),
                onlyPart, cable(null, Map.of(Direction.NORTH, new Ae2Grid.PartView("ae2:terminal", true, true))));
        MachineCopy copy = MeCableCopies.copy(Set.of(withPart, onlyPart, unread), read, DRIVE::equals);
        assertTrue(copy.blocks().isEmpty(), "线缆格不写成方块");
        assertEquals(1, copy.installations().size());
        assertEquals(1, copy.parts().size());
        assertEquals(Direction.UP, copy.parts().getFirst().side());
        assertEquals(2, copy.notes().size(), copy.notes().toString());
    }
}
