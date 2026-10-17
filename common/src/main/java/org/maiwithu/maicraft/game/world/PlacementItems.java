// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.game.mixin.BlockItemAccessor;

/**
 * 放置用的物品事实：右键放方块时放成什么由物品自己定（火把对着实墙放成墙式、Create 的漏斗放在传送带上变成带漏斗），
 * 原版把它藏在物品的受保护方法里，这里照原样问出来，放置预测和真的右键走同一条路。
 */
public final class PlacementItems {

    private PlacementItems() {}

    /** 这件方块物品在这个放置上下文里会放成什么；物品说放不下（被占、站不住）为 null。 */
    public static BlockState placementState(BlockItem item, BlockPlaceContext context) {
        return ((BlockItemAccessor) item).maicraft$getPlacementState(context);
    }
}
