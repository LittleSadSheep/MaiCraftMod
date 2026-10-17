// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import net.minecraft.core.BlockPos;

/**
 * 一格可以挖的方块：在哪、是什么、挖了掉什么。挖了掉什么以游戏真实的掉落规则为准，
 * 结论由扫描接缝的实现读出来，这里只带结论。
 *
 * @param pos        方块的格子
 * @param blockType  方块的注册 ID，例如 minecraft:coal_ore
 * @param droppedItem 挖掉后掉落的物品 ID，例如 minecraft:coal
 */
public record MinableSpot(BlockPos pos, String blockType, String droppedItem) {

    public MinableSpot {
        if (pos == null) throw new IllegalArgumentException("可挖的方块必须有格子");
        if (blockType == null || blockType.isBlank()) throw new IllegalArgumentException("可挖的方块必须有方块类型");
        if (droppedItem == null || droppedItem.isBlank()) throw new IllegalArgumentException("可挖的方块必须写掉落物");
    }
}
