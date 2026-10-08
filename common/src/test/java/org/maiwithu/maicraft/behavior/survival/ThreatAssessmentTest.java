// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 威胁评估：同一条血穿不穿甲结论不同、空手不打会还手的、退无可退改判背水、点引信的苦力怕排最前。 */
class ThreatAssessmentTest {

    private static final ThreatAssessment.MySide BARE = new ThreatAssessment.MySide(20, 0, 5, 0, true);
    private static final ThreatAssessment.MySide IRON = new ThreatAssessment.MySide(20, 15, 5, 0, true);
    private static final ThreatAssessment.Foe ZOMBIE = new ThreatAssessment.Foe(5, ThreatAssessment.Kind.MELEE, true, false);
    private static final ThreatAssessment.Foe SKELETON = new ThreatAssessment.Foe(8, ThreatAssessment.Kind.RANGED, true, false);
    private static final ThreatAssessment.Foe ARMED_CREEPER =
            new ThreatAssessment.Foe(4, ThreatAssessment.Kind.EXPLOSIVE, true, true);
    private static final ThreatAssessment.Foe IDLE_CREEPER =
            new ThreatAssessment.Foe(6, ThreatAssessment.Kind.EXPLOSIVE, false, false);

    @Test
    void sameHealthDifferentVerdictWithArmor() {
        // 裸奔对两只追兵已经悬了；同一条血穿铁甲就还打得过。
        assertEquals(ThreatAssessment.Verdict.OUTMATCHED, ThreatAssessment.assess(BARE, List.of(ZOMBIE, SKELETON)).verdict());
        assertEquals(ThreatAssessment.Verdict.WINNABLE, ThreatAssessment.assess(IRON, List.of(ZOMBIE, SKELETON)).verdict());
    }

    @Test
    void bareHandsAgainstRetaliationIsOutmatched() {
        var noWeapon = new ThreatAssessment.MySide(20, 0, 0, 0, true);
        assertEquals(ThreatAssessment.Verdict.OUTMATCHED, ThreatAssessment.assess(noWeapon, List.of(ZOMBIE)).verdict());
        // 空手面前只有个没点引信、没追的苦力怕：不会还手，不算打不过。
        assertEquals(ThreatAssessment.Verdict.WINNABLE, ThreatAssessment.assess(noWeapon, List.of(IDLE_CREEPER)).verdict());
    }

    @Test
    void noEscapeRouteDowngradesToLastStand() {
        var cornered = new ThreatAssessment.MySide(20, 0, 0, 0, false);
        // 退不掉就只好打：空手背水一战按"有风险"处理，不是站着等死。
        assertEquals(ThreatAssessment.Verdict.RISKY, ThreatAssessment.assess(cornered, List.of(ZOMBIE)).verdict());
    }

    @Test
    void armedCreeperSortsFirst() {
        List<ThreatAssessment.Foe> sorted = ThreatAssessment.sortedByThreat(List.of(SKELETON, IDLE_CREEPER, ARMED_CREEPER, ZOMBIE));
        assertEquals(ARMED_CREEPER, sorted.get(0), "点着引信的苦力怕最急");
    }

    @Test
    void idleExplosiveExcludedFromMeleeCandidatesWithoutRanged() {
        List<ThreatAssessment.Foe> candidates = ThreatAssessment.meleeCandidates(List.of(IDLE_CREEPER, ZOMBIE), false);
        assertEquals(List.of(ZOMBIE), candidates, "会炸又没远程手段的不凑近打");
        // 有远程手段时它仍是候选：隔远了点它不亏。
        assertEquals(2, ThreatAssessment.meleeCandidates(List.of(IDLE_CREEPER, ZOMBIE), true).size());
    }

    @Test
    void retreatLineFollowsEffectiveHealth() {
        // 满血裸奔 20：折算后仍是 20，不在线下；4 血裸奔折算还是 4，在线下。
        assertTrue(ThreatAssessment.belowRetreatLine(new ThreatAssessment.MySide(4, 0, 5, 0, true)));
        assertFalse(ThreatAssessment.belowRetreatLine(BARE));
        // 满血铁甲折算后更高，更不在线下。
        assertFalse(ThreatAssessment.belowRetreatLine(new ThreatAssessment.MySide(4, 15, 5, 0, true)));
    }

    @Test
    void effectiveHealthGrowsWithArmor() {
        assertTrue(ThreatAssessment.effectiveHealth(20, 15) > ThreatAssessment.effectiveHealth(20, 0));
        // 护甲折到 80% 封顶：20 点甲和 30 点甲同值。
        assertEquals(ThreatAssessment.effectiveHealth(20, 20), ThreatAssessment.effectiveHealth(20, 30), 0.001);
    }
}
