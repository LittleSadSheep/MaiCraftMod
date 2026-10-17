// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 坐一趟电梯的一段：进轿厢站里面一点，出轿厢的门口从近到远挨个试。 */
class ElevatorRideTest {

    @BeforeAll
    static void 引导方块注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 进轿厢站里面一点_出轿厢的门口从近到远() {
        assertEquals(Optional.of(new BlockPos(11, 64, 11)), ElevatorRide.innerSpot(ElevatorFloorsTest.elevator(false, true, 64),
                new Vec3(15, 64, 11)));
        ElevatorFloorsTest.Grid grid = new ElevatorFloorsTest.Grid();
        for (int x = 8; x <= 15; x++) {
            for (int z = 8; z <= 15; z++) grid.put(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState());
        }
        List<BlockPos> exits = ElevatorRide.landings(grid, ElevatorFloorsTest.CABIN, new BlockPos(12, 64, 11));
        assertTrue(exits.stream().noneMatch(cell -> ElevatorFloorsTest.CABIN.contains(Vec3.atCenterOf(cell))), "轿厢里的格不当出口");
        assertEquals(new BlockPos(13, 64, 11), exits.getFirst(), "最近的门口先试");
    }

}
