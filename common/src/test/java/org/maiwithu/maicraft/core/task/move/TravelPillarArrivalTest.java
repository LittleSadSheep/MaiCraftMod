// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.move;

import java.util.Set;

import net.minecraft.core.BlockPos;

/**
 * travel 到达时脚下自有垫柱的回执口径（169 修订）：到达后不自动拆柱，只从旅程放置账里
 * 认领脚下连续的自有垫块写进回执；遇到缺口或非自有格就停，途中别处的垫块不算在这根柱子里。
 */
public final class TravelPillarArrivalTest {

    public static void main(String[] args) {
        claimsContiguousOwnColumnTopFirst();
        stopsAtGapAndIgnoresOtherPlacements();
        nothingClaimedWithoutOwnPlacementUnderFeet();
        System.out.println("TravelPillarArrivalTest: own pillar under feet is reported, never dug back");
    }

    /** 脚下连续三格都是本次旅程放的：自顶向下全部列出，供回执点名。 */
    private static void claimsContiguousOwnColumnTopFirst() {
        var feet = new BlockPos(0, 3, 8);
        var column = MoveToCompanionTask.ownPillarUnder(Set.of(
                new BlockPos(0, 2, 8), new BlockPos(0, 1, 8), new BlockPos(0, 0, 8), new BlockPos(5, 1, 5)), feet);
        check(column.size() == 3 && column.getFirst().equals(new BlockPos(0, 2, 8))
                        && column.getLast().equals(new BlockPos(0, 0, 8)),
                "脚下连续自有柱自顶向下列出: " + column);
    }

    /** 柱列中间断开就停：缺口以下即使有自有垫块也不算这根柱子，别处的垫块由旅程地形账另列。 */
    private static void stopsAtGapAndIgnoresOtherPlacements() {
        var column = MoveToCompanionTask.ownPillarUnder(Set.of(
                new BlockPos(0, 2, 8), new BlockPos(0, 0, 8), new BlockPos(5, 1, 5)), new BlockPos(0, 3, 8));
        check(column.size() == 1 && column.getFirst().equals(new BlockPos(0, 2, 8)), "柱列缺口处停止认领: " + column);
    }

    /** 脚下不是本次放的方块（天然地面）：不认领任何垫柱，回执不出现垫柱说明。 */
    private static void nothingClaimedWithoutOwnPlacementUnderFeet() {
        check(MoveToCompanionTask.ownPillarUnder(Set.of(new BlockPos(5, 1, 5)), new BlockPos(0, 3, 8)).isEmpty(),
                "脚下没有自有垫块时不认领");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
