// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.calc;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导航目标的到达判断、搜索估价与语义指纹；全部是纯格计算，不读世界。
 */
class NavGoalTest {

    private static final BlockPos ORIGIN = new BlockPos(10, 64, 10);

    @Test
    void exactArrivalRequiresSameCell() {
        NavGoal goal = NavGoal.exact(ORIGIN);
        assertTrue(goal.isAt(ORIGIN), "same cell is the goal");
        assertFalse(goal.isAt(ORIGIN.above()), "one block up is a different cell");
        assertEquals(0.0, goal.heuristic(ORIGIN), 1.0E-9, "standing on the goal costs nothing");
        assertTrue(goal.heuristic(ORIGIN.east()) > 0.0, "a step away has positive remaining cost");
    }

    @Test
    void yLevelOnlyLooksAtHeight() {
        NavGoal goal = NavGoal.yLevel(64);
        assertTrue(goal.isAt(new BlockPos(0, 64, 0)), "any column at the level counts");
        assertFalse(goal.isAt(new BlockPos(0, 65, 0)), "one above the level does not");
    }

    @Test
    void nearUsesThreeDimensionalDistanceWithinRadius() {
        NavGoal goal = NavGoal.near(ORIGIN, 2.0);
        assertTrue(goal.isAt(ORIGIN.offset(2, 0, 0)), "two blocks east is inside the radius");
        assertTrue(goal.isAt(ORIGIN.offset(1, 1, 0)), "a diagonal step is still inside");
        assertFalse(goal.isAt(ORIGIN.offset(2, 1, 0)), "slightly beyond the radius is outside");
    }

    @Test
    void ringRejectsBothTooCloseAndTooFar() {
        NavGoal goal = NavGoal.ring(ORIGIN, 2.0, 4.0);
        assertFalse(goal.isAt(ORIGIN), "the centre is inside the inner edge");
        assertTrue(goal.isAt(ORIGIN.offset(3, 0, 0)), "three blocks east is on the ring");
        assertFalse(goal.isAt(ORIGIN.offset(5, 0, 0)), "five blocks east is beyond the outer edge");
    }

    @Test
    void compositeArrivesWhenAnyMemberDoes() {
        NavGoal goal = NavGoal.composite(List.of(NavGoal.yLevel(64), NavGoal.column(10, 10, 1)));
        assertTrue(goal.isAt(ORIGIN), "matching level and column satisfies both members");
        assertTrue(goal.isAt(new BlockPos(13, 64, 10)), "matching the level alone already satisfies one member");
        assertFalse(goal.isAt(new BlockPos(13, 66, 10)), "matching no member fails the composite");
    }

    @Test
    void semanticFingerprintTellsSameGoalApartFromChangedOne() {
        assertEquals(NavGoal.near(ORIGIN, 2).semanticFingerprint(),
                NavGoal.near(new BlockPos(10, 64, 10), 2.0).semanticFingerprint(),
                "the same place and radius is the same goal");
        assertNotEquals(NavGoal.near(ORIGIN, 2).semanticFingerprint(),
                NavGoal.near(ORIGIN, 3).semanticFingerprint(),
                "a changed radius means a changed goal");
        assertNotEquals(NavGoal.exact(ORIGIN).semanticFingerprint(),
                NavGoal.yLevel(64).semanticFingerprint(),
                "different kinds never share a fingerprint");
    }

    @Test
    void runAwayNeverClaimsArrivalAndPrefersFartherCells() {
        NavGoal goal = NavGoal.runAway(ORIGIN, 64);
        assertFalse(goal.isAt(ORIGIN.east()), "a run-away goal never declares arrival");
        assertTrue(goal.heuristic(ORIGIN.offset(-8, 0, 0)) < goal.heuristic(ORIGIN.offset(-2, 0, 0)),
                "farther cells have a lower remaining estimate");
    }

    @Test
    void heuristicConstantsStayFiniteAndPositive() {
        assertTrue(NavGoal.JUMP_ONE_BLOCK > 0.0 && Double.isFinite(NavGoal.JUMP_ONE_BLOCK),
                "jump cost comes from fall physics");
        assertTrue(NavGoal.DESCEND_ONE_BLOCK > 0.0 && Double.isFinite(NavGoal.DESCEND_ONE_BLOCK),
                "descend cost comes from fall physics");
    }
}
