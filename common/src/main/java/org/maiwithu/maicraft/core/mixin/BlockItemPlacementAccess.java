// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 预测角色右键放置时走物品自己的原生分派，让漏斗等物品能够选择其合法的最终方块形态。 */
@Mixin(BlockItem.class)
public interface BlockItemPlacementAccess {
    @Invoker("getPlacementState")
    BlockState maicraft$placementState(BlockPlaceContext context);
}
