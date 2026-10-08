// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;

/**
 * 候选站位：四项检查都通过的一个落脚格子，带着走过去的代价供排序。
 * 代价越小的排得越前，靠近动作按这个顺序逐个尝试。
 */
public record StandSpot(BlockPos feet, double walkCost) implements Comparable<StandSpot> {

    /** 先按代价小大排，一样近的按格子坐标排，保证同一场景每次给出同样的顺序。 */
    @Override public int compareTo(StandSpot other) {
        int byCost = Double.compare(walkCost, other.walkCost);
        if (byCost != 0) return byCost;
        return feet.compareTo(other.feet);
    }
}
