// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * 代价替身：默认按曼哈顿距离回答走过去的代价，声明过的格子回答走不过去。
 */
final class StubWalkCost implements WalkCost {

    final Set<BlockPos> unreachable = new HashSet<>();

    @Override public OptionalDouble from(BlockPos currentFeet, BlockPos spot) {
        if (unreachable.contains(spot)) return OptionalDouble.empty();
        int manhattan = Math.abs(spot.getX() - currentFeet.getX())
                + Math.abs(spot.getY() - currentFeet.getY())
                + Math.abs(spot.getZ() - currentFeet.getZ());
        return OptionalDouble.of(manhattan);
    }
}
