// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.mixin;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.StandingAndWallBlockItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读取立式/墙式共用品的墙式方块；放置预测与验收需要知道同一物品会落成哪两种方块。 */
@Mixin(StandingAndWallBlockItem.class)
public interface StandingAndWallBlockItemAccess {
    @Accessor("wallBlock")
    Block maicraft$wallBlock();
}
