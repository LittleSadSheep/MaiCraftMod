// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.block.Block;
import org.maiwithu.maicraft.game.mixin.StandingAndWallBlockItemAccessor;

/** 放置用的物品事实：火把、告示牌这类物品贴墙时放的是哪种方块，原版把它藏在物品里，这里读出来。 */
public final class PlacementItems {

    private PlacementItems() {}

    /** 这件立式/墙式共用的物品贴墙时放的方块。 */
    public static Block wallBlock(StandingAndWallBlockItem item) {
        return ((StandingAndWallBlockItemAccessor) item).maicraft$getWallBlock();
    }
}
