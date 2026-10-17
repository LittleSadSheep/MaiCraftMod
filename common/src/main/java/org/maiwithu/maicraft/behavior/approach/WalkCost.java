// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;

import java.util.OptionalDouble;

/**
 * 走过去的代价：只读接缝，问"从这里走到某个站位要多少代价、有没有路"。
 * 寻路属于出行轨，这里只定义查询的样子；没有接上实现之前，靠近只按距离就近挑站位。
 */
public interface WalkCost {

    /**
     * 从当前位置走到给定站位需要的代价（大致与路程成正比）；走不过去时返回 empty。
     * 判断函数对 empty 的处理是把"能到"这一项记为不过，而不是装作没有代价。
     */
    OptionalDouble from(BlockPos currentFeet, BlockPos spot);
}
