// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;

/**
 * 到达判断的离线测试：站着算到，跳到最高点与空中经过不算，泅渡按目标自己给出的规则算数。
 */
class WalkArrivalTest {

    private static final BlockPos TARGET = new BlockPos(10, 64, 20);

    @Test
    void exactGoalCountsOnlyWhenStandingOnTheCell() {
        assertTrue(WalkArrival.reached(NavGoal.exact(TARGET), TARGET, true, false));
    }

    @Test
    void exactGoalRejectsAirbornePass() {
        assertFalse(WalkArrival.reached(NavGoal.exact(TARGET), TARGET, false, false));
    }

    @Test
    void exactGoalRejectsFloatingInWater() {
        // 精确格要求站进那一格：水里漂到位不能点不了东西意义上的"站进"，不算到。
        assertFalse(WalkArrival.reached(NavGoal.exact(TARGET), TARGET, false, true));
    }

    @Test
    void looseColumnGoalAcceptsWaterArrival() {
        BlockPos floating = new BlockPos(TARGET.getX(), 62, TARGET.getZ());
        assertFalse(WalkArrival.reached(NavGoal.column(TARGET.getX(), TARGET.getZ()), floating, false, false));
        assertTrue(WalkArrival.reached(NavGoal.column(TARGET.getX(), TARGET.getZ()), floating, false, true));
    }

    @Test
    void looseGoalStillRequiresBeingAtTheCell() {
        // 在水里但不在范围内，照旧不算到。
        BlockPos away = TARGET.offset(5, 0, 5);
        assertFalse(WalkArrival.reached(NavGoal.column(TARGET.getX(), TARGET.getZ()), away, false, true));
    }

    @Test
    void interactionGoalKeepsGroundedArrival() {
        // 要在方块旁交互：水里漂到点不了东西。
        BlockPos floating = new BlockPos(TARGET.getX() + 1, TARGET.getY(), TARGET.getZ());
        assertTrue(NavGoal.adjacent(TARGET).isAt(floating));
        assertFalse(WalkArrival.reached(NavGoal.adjacent(TARGET), floating, false, true));
        assertTrue(WalkArrival.reached(NavGoal.adjacent(TARGET), floating, true, false));
    }

    @Test
    void compositeStaysStrictWhenAnyMemberRequiresGround() {
        // 一组候选里混着交互站位：整组不能靠水里漂到算到达。
        NavGoal mixed = NavGoal.composite(List.of(
                NavGoal.exact(TARGET),
                NavGoal.column(TARGET.getX(), TARGET.getZ())));
        BlockPos floating = new BlockPos(TARGET.getX(), 62, TARGET.getZ());
        assertFalse(WalkArrival.reached(mixed, floating, false, true));
        // 全部成员都接受水中到达的组合仍按宽松规则算到。
        NavGoal loose = NavGoal.composite(List.of(
                NavGoal.column(TARGET.getX(), TARGET.getZ()),
                NavGoal.yLevel(62)));
        assertTrue(WalkArrival.reached(loose, floating, false, true));
    }
}
