// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 读现场的只读接缝：一格加载了没有、是什么、角色看不看得见、是不是角色自己放的、算不算天然地形。
 * 读现场只读客户端已经加载的格，不叫区块加载，也不问服务端；看不见的只有自己放的才算知道。
 */
public interface ReadsSiteCells {

    /** 角色此刻所在的维度；不在世界里为 null。 */
    String dimension();

    /** 这一格加载着没有。 */
    boolean loaded(BlockPos pos);

    /** 这一格此刻是什么；只问加载着的格。 */
    BlockState state(BlockPos pos);

    /** 从角色眼睛能不能直接看见这一格（墙、关着的门都挡）。 */
    boolean visible(BlockPos pos);

    /** 这一格是不是角色自己放的（服务端的方块归属记录说是自己）：自己盖的房子看不见的格也知道。 */
    boolean selfPlaced(BlockPos pos);

    /** 这一格算不算天然地形：空气、液体、草这类一放就顶掉的、地面石头沙砾、矿石、长在树上的原木与树叶。 */
    boolean natural(BlockPos pos);
}
