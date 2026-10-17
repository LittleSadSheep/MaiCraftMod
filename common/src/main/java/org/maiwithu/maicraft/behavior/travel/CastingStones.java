// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.game.player.BackpackStack;

/**
 * 浇筑用的石料：挡块、模具、填台都用不怕火的整块（圆石、深板岩圆石、下界岩、泥土……）。
 * 从身上挑最不值钱的：整块碰撞、没有方块实体、不会掉落、挨着岩浆不会被点着的建材，越多的越先用；
 * 不够的部分按圆石去要——挖石头就有。
 */
final class CastingStones {

    /** 不够时去要的石料：挖石头就有，哪里都不缺。 */
    static final String COBBLESTONE = "minecraft:cobblestone";

    private CastingStones() {}

    /** 这一格物品能当浇筑石料吗。 */
    static boolean eligible(BackpackStack stack) {
        if (!stack.buildingMaterial() || stack.precious() || stack.gear() || stack.food()) return false;
        BlockState state = stateOf(stack.itemId());
        return state != null
                && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                && !state.hasBlockEntity()
                && !(state.getBlock() instanceof FallingBlock)
                // 木板、羊毛挨着岩浆会被点着，模具烧掉水就跑了。
                && !state.ignitedByLava();
    }

    /** 身上能当石料的一共几块。 */
    static int carried(List<BackpackStack> stacks) {
        int total = 0;
        for (BackpackStack stack : stacks) {
            if (eligible(stack)) total += stack.count();
        }
        return total;
    }

    /** 给这些格各配一块石料：最多的那种先用，几种凑着用也行；配不上的格按圆石写，施工引擎备料时会去要。 */
    static List<PlannedCell> assign(List<BlockPos> cells, List<BackpackStack> stacks) {
        List<BackpackStack> stones = new ArrayList<>();
        for (BackpackStack stack : stacks) {
            if (eligible(stack)) stones.add(stack);
        }
        stones.sort(Comparator.comparingInt(BackpackStack::count).reversed());
        List<PlannedCell> planned = new ArrayList<>(cells.size());
        int index = 0;
        int usedOfCurrent = 0;
        for (BlockPos at : cells) {
            while (index < stones.size() && usedOfCurrent >= stones.get(index).count()) {
                index++;
                usedOfCurrent = 0;
            }
            BlockState state = index < stones.size() ? stateOf(stones.get(index).itemId())
                    : Blocks.COBBLESTONE.defaultBlockState();
            usedOfCurrent++;
            planned.add(PlannedCell.block(at, state, Set.of()));
        }
        return List.copyOf(planned);
    }

    /** 物品对应的方块状态；不是方块物品为 null。 */
    static BlockState stateOf(String itemId) {
        Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId)).orElse(null);
        return item instanceof BlockItem blockItem ? blockItem.getBlock().defaultBlockState() : null;
    }
}
