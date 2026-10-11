// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 照明看现场的只读接缝：一格有没有加载、是什么方块、这一面结不结实。
 * 结实不结实按原版的支撑规则回答（完整方块、玻璃、楼梯实心背面都算），生产实现转述游戏接口层的同一条事实；
 * 测试用替身摆场景。没加载的格要如实说没加载，不能当空气。
 */
public interface LightingWorld {

    /** 这一格所在的区块是否已加载。 */
    boolean loaded(BlockPos pos);

    /** 这一格现在的方块状态；只在已加载时有意义。 */
    BlockState state(BlockPos pos);

    /** 这一格的某一面是否结实到能给灯具当支撑。 */
    boolean sturdy(BlockPos pos, Direction face);
}
