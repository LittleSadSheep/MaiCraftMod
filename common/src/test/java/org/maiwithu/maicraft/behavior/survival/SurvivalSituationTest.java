// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 生存处境同时回答三个判断要读的处境，字段原样转交，不夹带结论。 */
class SurvivalSituationTest {

    @Test
    void situationAnswersFallView() {
        SurvivalSituation situation = SurvivalFakes.calm().fallingOnto(new BlockPos(1, 60, 1), false).build();
        assertTrue(situation.falling());
        assertFalse(situation.survivesLanding());
        assertEquals(new BlockPos(1, 60, 1), situation.waterCell());
    }

    @Test
    void situationAnswersDrowningView() {
        SurvivalSituation situation = SurvivalFakes.calm().underwater(45).surfaceNeeds(80).build();
        assertTrue(situation.headInWater());
        assertEquals(45, situation.airTicks());
        assertEquals(80, situation.airNeededToSurface());
    }

    @Test
    void situationAnswersBuriedView() {
        SurvivalSituation situation = SurvivalFakes.calm().buriedAt(new BlockPos(0, 65, 0)).build();
        assertTrue(situation.stuckInSolidBlock());
        assertEquals(new BlockPos(0, 65, 0), situation.buriedCell());
    }
}
