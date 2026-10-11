// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import net.minecraft.world.level.block.Blocks;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Problem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 维度路由：哪对维度走哪种门、没有门怎么交代。 */
class DimensionRouteTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void sameDimensionHasNoCrossDimensionLeg() {
        var same = DimensionRoute.decide(DimensionRoute.OVERWORLD, DimensionRoute.OVERWORLD);
        assertTrue(same.sameDimension(), "同维度不出跨维度段");

        var nullTarget = DimensionRoute.decide(DimensionRoute.NETHER, null);
        assertTrue(nullTarget.sameDimension(), "目标维度没给按当前维度算");
    }

    @Test
    void overworldAndNetherUseNetherPortalBothWays() {
        var toNether = DimensionRoute.decide(DimensionRoute.OVERWORLD, DimensionRoute.NETHER);
        assertEquals(DimensionRoute.Way.NETHER_PORTAL, toNether.way());
        assertEquals(Blocks.NETHER_PORTAL, toNether.portalBlock());

        var backHome = DimensionRoute.decide(DimensionRoute.NETHER, DimensionRoute.OVERWORLD);
        assertEquals(DimensionRoute.Way.NETHER_PORTAL, backHome.way());
        assertEquals(Blocks.NETHER_PORTAL, backHome.portalBlock());
    }

    @Test
    void overworldAndEndUseEndPortal() {
        var toEnd = DimensionRoute.decide(DimensionRoute.OVERWORLD, DimensionRoute.END);
        assertEquals(DimensionRoute.Way.END_PORTAL, toEnd.way());
        assertEquals(Blocks.END_PORTAL, toEnd.portalBlock());

        var backHome = DimensionRoute.decide(DimensionRoute.END, DimensionRoute.OVERWORLD);
        assertEquals(DimensionRoute.Way.END_PORTAL, backHome.way());
    }

    @Test
    void netherToEndSuggestsGoingThroughTheOverworld() {
        var route = DimensionRoute.decide(DimensionRoute.NETHER, DimensionRoute.END);
        assertEquals(DimensionRoute.Way.NO_DIRECT_PORTAL, route.way());
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, route.problem().kind());
        assertTrue(route.problem().suggestion().contains("主世界"), "建议里写明经主世界中转");
    }

    @Test
    void unknownDimensionIsUnsupported() {
        var modded = DimensionRoute.decide(DimensionRoute.OVERWORLD, "somemod:custom_dim");
        assertEquals(DimensionRoute.Way.UNKNOWN_DIMENSION, modded.way());
        assertEquals(Problem.Kind.UNSUPPORTED, modded.problem().kind());
        assertTrue(modded.problem().message().contains("只支持主世界、下界、末地"));
        assertNull(modded.portalBlock());
    }
}
