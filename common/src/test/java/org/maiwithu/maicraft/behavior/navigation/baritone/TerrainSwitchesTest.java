// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.junit.jupiter.api.Test;

/** 地形许可四档到 Baritone 挖放开关的翻译：不改全关；只垫不挖放开垫、关掉挖；能挖的全开；落地办法单独由许可决定。 */
class TerrainSwitchesTest {

    @Test
    void walkOnlyClosesEveryTerrainSwitch() {
        TerrainSwitches switches = TerrainSwitches.of(TerrainPermit.WALK_ONLY);
        assertFalse(switches.allowBreak());
        assertFalse(switches.allowPlace());
        assertFalse(switches.allowParkourPlace());
        assertFalse(switches.allowDownward());
        assertFalse(switches.allowLandingAids());
    }

    @Test
    void temporaryPlacesWithoutBreaking() {
        // 只垫不挖：垫块与跑酷垫放行，挖开与向下挖都关掉——路线只往上搭，不拆。
        TerrainSwitches switches = TerrainSwitches.of(TerrainPermit.TEMPORARY);
        assertFalse(switches.allowBreak());
        assertTrue(switches.allowPlace());
        assertTrue(switches.allowParkourPlace());
        assertFalse(switches.allowDownward());
        assertTrue(switches.allowLandingAids());
    }

    @Test
    void naturalAndAnyOpenEveryTerrainSwitch() {
        for (TerrainPermit permit : new TerrainPermit[] {TerrainPermit.NATURAL, TerrainPermit.ANY}) {
            TerrainSwitches switches = TerrainSwitches.of(permit);
            assertTrue(switches.allowBreak(), permit.changes() + " 应能挖开挡路");
            assertTrue(switches.allowPlace());
            assertTrue(switches.allowParkourPlace());
            assertTrue(switches.allowDownward());
            assertTrue(switches.allowLandingAids());
        }
    }

    @Test
    void waterBucketIsGrantedSeparatelyFromTerrain() {
        // 只许放落地办法、不许动土：挖放全关，落地办法单独放行。
        TerrainSwitches switches = TerrainSwitches.of(
                new TerrainPermit(Permissions.BlockChanges.NONE, true));
        assertFalse(switches.allowBreak());
        assertFalse(switches.allowPlace());
        assertTrue(switches.allowLandingAids());
        // 允许动土但不许放水：挖放放开，落地办法保持关闭。
        TerrainSwitches noWater = TerrainSwitches.of(
                new TerrainPermit(Permissions.BlockChanges.NATURAL, false));
        assertTrue(noWater.allowBreak());
        assertFalse(noWater.allowLandingAids());
        assertEquals(new TerrainPermit(Permissions.BlockChanges.NATURAL, false).mayChangeTerrain(),
                noWater.allowPlace());
    }
}
