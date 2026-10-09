// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.kernel.task.Urgency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 饥饿与夜晚、贴边的分流：三档阈值、有床睡无床看处境、贴边只在贴着深落差时才算。 */
class SurvivalBranchTest {

    @Test
    void hungerThreeLevels() {
        assertEquals(Urgency.NOW, HungerNeed.assess(new HungerNeed.Facts(0, true, true)));
        assertEquals(Urgency.SOON, HungerNeed.assess(new HungerNeed.Facts(6, false, false)));
        assertEquals(Urgency.SOON, HungerNeed.assess(new HungerNeed.Facts(3, false, true)));
        // 低于满但还有吃的：找空当吃；没吃的且还不急：不插。
        assertEquals(Urgency.LATER, HungerNeed.assess(new HungerNeed.Facts(15, false, true)));
        assertNull(HungerNeed.assess(new HungerNeed.Facts(15, false, false)));
        assertNull(HungerNeed.assess(new HungerNeed.Facts(20, false, true)));
    }

    @Test
    void nightBranching() {
        // 不在可睡时段：不插。
        assertNull(NightfallNeed.branch(new NightfallNeed.Facts(false, false, true,
                ThreatAssessment.Verdict.WINNABLE, 20, 15)));
        // 能弄到床：找空当去睡（入睡归睡觉规格）。
        // 有床：夜间休息接上之前接着干活，不封坑熬夜。
        assertNull(NightfallNeed.branch(new NightfallNeed.Facts(true, false, true,
                ThreatAssessment.Verdict.WINNABLE, 20, 15)));
        // 安全处（矿道、屋里、照明充足）：接着干。
        assertNull(NightfallNeed.branch(new NightfallNeed.Facts(true, true, false,
                ThreatAssessment.Verdict.OUTMATCHED, 20, 15)));
        // 露天但打得过：接着干。
        assertNull(NightfallNeed.branch(new NightfallNeed.Facts(true, false, false,
                ThreatAssessment.Verdict.WINNABLE, 20, 15)));
        // 露天又打不过：极端自保。
        assertEquals(Urgency.SOON, NightfallNeed.branch(new NightfallNeed.Facts(true, false, false,
                ThreatAssessment.Verdict.OUTMATCHED, 20, 15)));
    }

    @Test
    void edgeOnlyWhenCloseToDeepDrop() {
        assertTrue(EdgeProximityNeed.atRisk(new EdgeProximityNeed.Facts(0.2, 5, true, new double[] {0, 0, 0})));
        // 离边远：不算。
        assertFalse(EdgeProximityNeed.atRisk(new EdgeProximityNeed.Facts(1.5, 5, true, new double[] {0, 0, 0})));
        // 边外只是个台阶：不算。
        assertFalse(EdgeProximityNeed.atRisk(new EdgeProximityNeed.Facts(0.2, 2, true, new double[] {0, 0, 0})));
    }
}
