// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 维度坐标换算：主世界与下界水平 1:8，末地没有换算预期。 */
class DimensionScaleTest {

    @Test
    void overworldToNetherDividesByEight() {
        WorldPosition expected = DimensionScale.expectedSpot(DimensionRoute.OVERWORLD,
                new BlockPos(800, 64, -240), DimensionRoute.NETHER);
        assertEquals(new WorldPosition(100, 64, -30, DimensionRoute.NETHER), expected);
    }

    @Test
    void netherToOverworldMultipliesByEight() {
        WorldPosition expected = DimensionScale.expectedSpot(DimensionRoute.NETHER,
                new BlockPos(-12, 70, 5), DimensionRoute.OVERWORLD);
        assertEquals(new WorldPosition(-96, 70, 40, DimensionRoute.OVERWORLD), expected);
    }

    @Test
    void endRouteHasNoScaleExpectation() {
        assertNull(DimensionScale.expectedSpot(DimensionRoute.OVERWORLD,
                new BlockPos(100, 64, 100), DimensionRoute.END));
        assertNull(DimensionScale.expectedSpot(DimensionRoute.END,
                new BlockPos(100, 64, 100), DimensionRoute.OVERWORLD));
    }

    @Test
    void offsetMeasuresHorizontalDistanceToExpectation() {
        WorldPosition expected = new WorldPosition(100, 64, -30, DimensionRoute.NETHER);
        assertEquals(5, DimensionScale.horizontalOffset(expected, new BlockPos(103, 70, -34)));
        assertEquals(-1, DimensionScale.horizontalOffset(null, new BlockPos(0, 64, 0)));
    }
}
