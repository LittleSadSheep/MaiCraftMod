// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.junit.jupiter.api.Test;

/** 地形许可到 Baritone 挖放开关的翻译：不许动就全关，允许动土就放开，落地水单独由许可决定。 */
class TerrainSwitchesTest {

    @Test
    void walkOnlyClosesEveryTerrainSwitch() {
        TerrainSwitches switches = TerrainSwitches.of(TerrainPermit.WALK_ONLY);
        assertFalse(switches.allowBreak());
        assertFalse(switches.allowPlace());
        assertFalse(switches.allowParkourPlace());
        assertFalse(switches.allowDownward());
        assertFalse(switches.allowWaterBucketFall());
    }

    @Test
    void terraformOpensTerrainSwitchesAndWaterBucket() {
        TerrainSwitches switches = TerrainSwitches.of(TerrainPermit.TERRAFORM);
        assertTrue(switches.allowBreak());
        assertTrue(switches.allowPlace());
        assertTrue(switches.allowParkourPlace());
        assertTrue(switches.allowDownward());
        assertTrue(switches.allowWaterBucketFall());
    }

    @Test
    void waterBucketIsGrantedSeparatelyFromTerrain() {
        // 只许放水缓冲、不许动土：挖放全关，落地水单独放行。
        TerrainSwitches switches = TerrainSwitches.of(new TerrainPermit(false, true));
        assertFalse(switches.allowBreak());
        assertFalse(switches.allowPlace());
        assertTrue(switches.allowWaterBucketFall());
        // 允许动土但不许放水：挖放放开，落地水保持关闭。
        TerrainSwitches noWater = TerrainSwitches.of(new TerrainPermit(true, false));
        assertTrue(noWater.allowBreak());
        assertFalse(noWater.allowWaterBucketFall());
        assertEquals(new TerrainPermit(true, false).mayChangeTerrain(), noWater.allowPlace());
    }
}
