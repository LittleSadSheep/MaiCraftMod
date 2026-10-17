// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.lighting;

import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 光源物品：这次照明用哪种灯、放下去是什么样子、发射多少光。区域补光按它把灯位转成计划格。
 *
 * <p>默认火把：身上捡到的发光方块不改默认，免得整片基地改成昂贵材料。
 * 发射量达不到验收亮度的灯具不选——验收亮度 9 的农田档拿灵魂火把补不满，宁可换灯也不降验收。
 *
 * @param itemId   用的物品（备料按它算）
 * @param standing 立在地上（支撑面朝上）的落法；没有立式落法为 null
 * @param wall     贴墙的落法；没有为 null
 * @param emission 发射量（方块最高状态的光）
 */
public record LampItem(String itemId, BlockState standing, BlockState wall, int emission) {

    /** 默认光源：火把，发射 14，立式与贴墙都有。 */
    public static LampItem torch() {
        return new LampItem("minecraft:torch", Blocks.TORCH.defaultBlockState(),
                Blocks.WALL_TORCH.defaultBlockState(), 14);
    }

    /**
     * 按物品 ID 解析光源；不是光源、或发射量达不到验收亮度时为空。
     * 发射量按方块最高状态算：挂在墙上的火把和立着的火把一样亮。
     */
    public static Optional<LampItem> resolve(String itemId, int threshold) {
                ResourceLocation id;
        try {
            id = ResourceLocation.parse(itemId);
        } catch (RuntimeException invalid) {
            // 写法不是合法的资源 ID：当"没有这个光源"如实回答，不往外抛。
            return Optional.empty();
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == Items.AIR || !(item instanceof BlockItem blockItem)) {
            return Optional.empty();
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        int emission = state.getLightEmission();
        if (emission < threshold) {
            return Optional.empty();
        }
        if (state.is(Blocks.TORCH)) {
            return Optional.of(new LampItem(itemId, state, Blocks.WALL_TORCH.defaultBlockState(), emission));
        }
        if (state.is(Blocks.SOUL_TORCH)) {
            return Optional.of(new LampItem(itemId, state, Blocks.SOUL_WALL_TORCH.defaultBlockState(), emission));
        }
        if (state.getBlock() instanceof WallTorchBlock) {
            // 给的是墙式火把方块自己：立式落法同样存在，按同族火把补上。
            return Optional.of(new LampItem(itemId,
                    state.is(Blocks.WALL_TORCH) ? Blocks.TORCH.defaultBlockState() : Blocks.SOUL_TORCH.defaultBlockState(),
                    state, emission));
        }
        BlockState standing = state.hasProperty(LanternBlock.HANGING)
                ? state.setValue(LanternBlock.HANGING, false)
                : state;
        // 灯笼不选悬挂态：立在地上放。
        return Optional.of(new LampItem(itemId, standing, null, emission));
    }
}
