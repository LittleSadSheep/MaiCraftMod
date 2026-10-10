// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 火把、告示牌这类立式/墙式共用物品的读端：读出它贴墙时放的那种方块，放置预测按它算墙式结果。 */
@Mixin(StandingAndWallBlockItem.class)
public interface StandingAndWallBlockItemAccessor {

    @Accessor("wallBlock")
    Block maicraft$getWallBlock();
}
