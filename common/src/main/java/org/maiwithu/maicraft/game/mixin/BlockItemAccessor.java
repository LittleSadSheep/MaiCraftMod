// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 方块物品的读端：问它在这个放置上下文里放成什么（物品自己的放法，模组物品覆写的也算），放置预测按它算。 */
@Mixin(BlockItem.class)
public interface BlockItemAccessor {

    @Invoker("getPlacementState")
    BlockState maicraft$getPlacementState(BlockPlaceContext context);
}
