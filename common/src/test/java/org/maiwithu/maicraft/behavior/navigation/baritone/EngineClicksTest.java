// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 路上右键确认后认垫块：原来能被顶替的格子长出了实心方块才算垫上，开门、挖掉都不算。 */
class EngineClicksTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 空气或水里长出方块_算垫上() {
        assertTrue(EngineClicks.placedInto(Blocks.AIR.defaultBlockState(), Blocks.DIRT.defaultBlockState()));
        assertTrue(EngineClicks.placedInto(Blocks.WATER.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState()));
        assertTrue(EngineClicks.placedInto(Blocks.SHORT_GRASS.defaultBlockState(), Blocks.DIRT.defaultBlockState()));
    }

    @Test
    void 开门_挖掉_流进水都不算垫上() {
        var closed = Blocks.OAK_DOOR.defaultBlockState();
        assertFalse(EngineClicks.placedInto(closed, closed.setValue(DoorBlock.OPEN, true)));
        assertFalse(EngineClicks.placedInto(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        assertFalse(EngineClicks.placedInto(Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState()));
    }
}
