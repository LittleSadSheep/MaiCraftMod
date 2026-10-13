// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.navigation.baritone.ClearanceWhitelist;

/**
 * 地图上自然生成的方块：缺材料时只从这些里挖——天然地形（清障名单里的土石沙砾冰雪）、矿石（含模组矿石）、
 * 长在树上的原木、自然长的树叶。机器零件、机壳、房子的木板砖块玻璃、平台、刷石机的圆石这些搭出来的一律不拆，
 * 不管是自己搭的还是地图上本来就有的；缺料先从 AE、箱子拿，再去挖天然的。
 */
public final class NaturalBlocks {

    /** 各模组登记矿石用的公共标签。 */
    private static final TagKey<Block> ORES = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores"));

    private NaturalBlocks() {}

    /** 这一格是不是地图上自然长出来的、缺材料时能挖的。 */
    public static boolean grown(BlockState state, BlockPos pos, BlockGetter level, Predicate<BlockPos> loaded,
            Map<BlockPos, Boolean> trunks) {
        if (ClearanceWhitelist.allowsAt(state, pos, level, loaded, trunks)) return true;
        return state.is(ORES) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(BlockTags.DIRT) || state.is(BlockTags.SAND);
    }
}
