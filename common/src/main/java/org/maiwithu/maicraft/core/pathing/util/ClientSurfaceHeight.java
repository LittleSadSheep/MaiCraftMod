// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.util;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 在客户端查某一列的地面高度：从已经同步的高度上界往下读方块，补出客户端没有同步的“忽略树叶高度图”。
 */
public final class ClientSurfaceHeight {

    private ClientSurfaceHeight() {}

    /**
     * 返回最高的非树叶遮挡物上面一格。它找的是这一整列的顶部，不是玩家当前楼层的地板。
     */
    public static int motionBlockingNoLeaves(ClientLevel level, int x, int z) {
        int minY = level.getMinBuildHeight();
        int upperY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = upperY - 1; y >= minY; y--) {
            cursor.set(x, y, z);
            if (Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque()
                    .test(level.getBlockState(cursor))) {
                return y + 1;
            }
        }
        return minY;
    }

    /**
     * 允许清树的选址再跳过树干和树叶，但遇到水、建筑或容器仍保留；最多向下找六十四格。
     */
    public static int constructionGround(ClientLevel level, int x, int z) {
        return constructionGround(level, x, z,
                level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
    }

    static int constructionGround(BlockGetter level, int x, int z, int upperY) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // 从同步高度向下限量找地面；找不到时返回世界最低高度作为未找到标记，当前建筑选址据此拒绝这一候选。
        int bottom = Math.max(level.getMinBuildHeight(), upperY - 64);
        for (int y = upperY - 1; y >= bottom; y--) {
            cursor.set(x, y, z);
            var state = level.getBlockState(cursor);
            if (!state.getFluidState().isEmpty()) return y + 1;
            if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) continue;
            if (Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(state)) return y + 1;
        }
        return level.getMinBuildHeight();
    }
}
