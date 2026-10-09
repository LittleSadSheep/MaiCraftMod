// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 工具要求读端的注册表实现：挖掉一种方块需要什么工具、手上这件够不够格，
 * 都按游戏自己的规则从真实的方块与物品注册表读。
 *
 * <p>需要工具的方块按挖掘分组标签（可镐挖、可斧挖……）换成对应工具的物品标签说法，
 * 让缺镐先弄镐的判断能用同一种写法去弄工具；不需要特定工具的方块如实说没有要求。
 * 方块或物品 ID 在注册表里查不到时按"要求不明"处理：toolRequired 给空、sufficient 给假，
 * 宁可先去弄一把镐也不冒充够格。
 */
public final class RegistryToolRequirements implements ReadsToolRequirements {

    @Override
    public Optional<String> toolRequired(String blockTypeId) {
        BlockState state = blockState(blockTypeId);
        if (state == null || !state.requiresCorrectToolForDrops()) return Optional.empty();
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return Optional.of("#minecraft:pickaxes");
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) return Optional.of("#minecraft:axes");
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return Optional.of("#minecraft:shovels");
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) return Optional.of("#minecraft:hoes");
        // 要求正确工具却不在任何挖掘分组里：游戏自己的数据对不上，按要求不明处理。
        return Optional.empty();
    }

    @Override
    public boolean sufficient(String toolItemId, String blockTypeId) {
        BlockState state = blockState(blockTypeId);
        if (state == null) return false;
        Item item = item(toolItemId);
        if (item == null) return false;
        // 与挖掘结算同一套规则：等级不够的镐挖石头不掉落，这里给的结论与游戏判的一致。
        return new ItemStack(item).isCorrectToolForDrops(state);
    }

    // 按注册 ID 查方块；查不到或写法不合法时给 null，由调用方按"要求不明"处理。
    private static BlockState blockState(String blockTypeId) {
        ResourceLocation id = ResourceLocation.tryParse(blockTypeId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;
        return BuiltInRegistries.BLOCK.get(id).defaultBlockState();
    }

    // 按注册 ID 查物品；查不到或写法不合法时给 null。
    private static Item item(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return null;
        return BuiltInRegistries.ITEM.get(id);
    }
}
