// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 走到情况的离线测试：状态各就各位，失败必须带问题，路上与终点必须带脚下位置。
 */
class WalkReportTest {

    private static final BlockPos FEET = new BlockPos(3, 64, 7);

    @Test
    void planningCarriesNoPositionYet() {
        WalkReport report = WalkReport.planning();
        assertEquals(WalkReport.State.PLANNING, report.state());
        assertNull(report.feet());
        assertTrue(report.crossedWater() == false);
        assertNull(report.problem());
    }

    @Test
    void onTheWayCarriesFeetAndWaterFact() {
        WalkReport report = WalkReport.onTheWay(FEET, true);
        assertEquals(WalkReport.State.ON_THE_WAY, report.state());
        assertEquals(FEET, report.feet());
        assertTrue(report.crossedWater());
        assertNull(report.problem());
    }

    @Test
    void arrivedCarriesFeet() {
        WalkReport report = WalkReport.arrived(FEET, false);
        assertEquals(WalkReport.State.ARRIVED, report.state());
        assertEquals(FEET, report.feet());
    }

    @Test
    void stoppedCarriesFeet() {
        WalkReport report = WalkReport.stopped(FEET, true);
        assertEquals(WalkReport.State.STOPPED, report.state());
        assertEquals(FEET, report.feet());
        assertTrue(report.crossedWater());
    }

    @Test
    void failedCarriesProblemAndLastKnownFeet() {
        Problem problem = Problem.of(Problem.Kind.UNREACHABLE, "河对岸的悬崖走不上去");
        WalkReport report = WalkReport.failed(problem, FEET, true);
        assertEquals(WalkReport.State.FAILED, report.state());
        assertEquals(problem, report.problem());
        assertEquals(FEET, report.feet());
    }

    @Test
    void failureWithoutProblemIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> WalkReport.failed(null, FEET, false));
    }

    @Test
    void statesOtherThanPlanningAndFailureNeedFeet() {
        assertThrows(IllegalArgumentException.class, () -> WalkReport.onTheWay(null, false));
        assertThrows(IllegalArgumentException.class, () -> WalkReport.arrived(null, false));
        assertThrows(IllegalArgumentException.class, () -> WalkReport.stopped(null, false));
    }
}
