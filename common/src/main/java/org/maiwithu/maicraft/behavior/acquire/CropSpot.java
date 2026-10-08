// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import net.minecraft.core.BlockPos;

/**
 * 一格熟了的作物：在哪、是什么作物。"熟没熟"以方块自己的状态为准（例如小麦的 age=7），
 * 判断在扫描接缝的实现里做，这里只带结论。
 *
 * @param pos       作物方块的格子
 * @param blockType 作物方块的注册 ID，例如 minecraft:wheat
 */
public record CropSpot(BlockPos pos, String blockType) {

    public CropSpot {
        if (pos == null) throw new IllegalArgumentException("作物必须有格子");
        if (blockType == null || blockType.isBlank()) throw new IllegalArgumentException("作物必须有方块类型");
    }
}
