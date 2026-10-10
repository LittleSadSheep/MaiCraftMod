// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 读世界里一格方块的只读接缝：核对与施工判断都从这里看现场，自己不读游戏对象。
 * 没加载的格要如实说没加载，不能当空气；真正读客户端世界的实现在使用方接上，测试里用替身摆场景。
 */
public interface ReadsBlocks {

    /** 这一格所在的区块是否已加载。 */
    boolean loaded(BlockPos pos);

    /** 这一格现在的方块状态；只在已加载时有意义。 */
    BlockState state(BlockPos pos);
}
