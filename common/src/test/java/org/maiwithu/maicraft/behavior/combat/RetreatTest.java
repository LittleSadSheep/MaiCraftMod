// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** 撤离：背离追兵重心挑落点、正后方不能站就偏着找、走不通换方向；甩开只认这次的追兵与新近打过我的。 */
class RetreatTest {

    @Test
    void havenIsAwayFromTheChasers() {
        int[] haven = Retreat.haven(0, 64, 0, 5, 0, 0, (x, y, z) -> y == 64);
        assertEquals(-32, haven[0], "追兵在东，往西三十二格");
        assertEquals(0, haven[2]);
    }

    @Test
    void blockedStraightBackFallsBackToTheSides() {
        // 正西那一片是湖：偏一点的方向能站就去那儿；一列里上下四格内找落脚处。
        int[] haven = Retreat.haven(0, 64, 0, 5, 0, 0, (x, y, z) -> z != 0 && y == 62);
        assertTrue(haven[0] < 0 && haven[2] != 0, "偏着往西找，实际 " + haven[0] + "," + haven[2]);
        assertEquals(62, haven[1]);
        assertNull(Retreat.haven(0, 64, 0, 5, 0, 0, (x, y, z) -> false), "都不能站交给离开追兵的寻路");
        // 第二次走不通从下一个方向试起，不反复撞同一堵墙。
        int[] second = Retreat.haven(0, 64, 0, 5, 0, 1, (x, y, z) -> y == 64);
        assertTrue(second[2] != 0, "换了方向");
    }

    @Test
    void escapeCountsOnlyThisRetreatsPursuersAndFreshAttackers() {
        Retreat retreat = new Retreat();
        Battlefield start = field(foe(1, 6, false));
        retreat.begin("血快见底", start);
        assertFalse(retreat.escaped(start));
        assertTrue(retreat.escaped(field(foe(7, 20, false))), "远处没参与追击的不让逃跑无限延长");
        assertFalse(retreat.escaped(field(foe(7, 20, true))), "新近打过我的照样算");
        // 连着走不通到上限才退无可退；身体真走出两格就清账。
        assertFalse(retreat.routeFailed(0, 0));
        assertFalse(retreat.routeFailed(0, 0));
        assertFalse(retreat.routeFailed(5, 0), "走出了两格，重新数");
        assertFalse(retreat.routeFailed(5, 0));
        assertTrue(retreat.routeFailed(5, 0));
    }

    private static Battlefield field(Battlefield.Foe... foes) {
        Battlefield.Me me = new Battlefield.Me(0, 64, 0, 0.3, 4, 0, 3, true, true, false, 3, 0);
        return new Battlefield(me, List.of(foes));
    }

    private static Battlefield.Foe foe(int id, double distance, boolean attacker) {
        CombatSenses.Threat threat = new CombatSenses.Threat(id, UUID.randomUUID(), "minecraft:zombie", distance, 64, 0,
                distance, ThreatAssessment.Kind.MELEE, false, true, true);
        return new Battlefield.Foe(threat, true, attacker, true, false);
    }
}
