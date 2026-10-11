// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.create;

import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.createmod.catnip.platform.CatnipServices;
import com.simibubi.create.content.contraptions.glue.SuperGlueSelectionPacket;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackPlacement;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.compat.create.CreateTools;

/**
 * 读机械动力手持工具的读写端：能不能用扳手拆看方块是不是 Create 的可调方块；
 * 砂纸配方从客户端的配方表里按 Create 的砂纸打磨类型取，原料取配方唯一那一格能放的全部物品；
 * 铺轨读轨道物品记着的起点与两头轨道方块实体里的连接。只翻译，不判断。
 */
public final class CreateToolReads implements CreateTools {

    @Override public boolean wrenchable(BlockState state) {
        return state.getBlock() instanceof IWrenchable;
    }

    @Override public List<Polishing> polishing(ClientLevel level) {
        List<Polishing> recipes = new ArrayList<>();
        for (RecipeHolder<? extends Recipe<?>> holder
                : level.getRecipeManager().getAllRecipesFor(AllRecipeTypes.SANDPAPER_POLISHING.getType())) {
            Recipe<?> recipe = holder.value();
            if (recipe.getIngredients().isEmpty()) continue;
            Ingredient ingredient = recipe.getIngredients().getFirst();
            Set<String> inputs = new LinkedHashSet<>();
            for (ItemStack option : ingredient.getItems()) {
                inputs.add(BuiltInRegistries.ITEM.getKey(option.getItem()).toString());
            }
            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (inputs.isEmpty() || result.isEmpty()) continue;
            recipes.add(new Polishing(List.copyOf(inputs), BuiltInRegistries.ITEM.getKey(result.getItem()).toString(),
                    result.getCount()));
        }
        return recipes;
    }

    @Override public void glue(BlockPos from, BlockPos to) {
        CatnipServices.NETWORK.sendToServer(new SuperGlueSelectionPacket(from, to));
    }

    // 那一片有没有胶：附近的胶里有一块的范围正好是这一片（Create 用 span 算两角盖住的方块）。
    @Override public boolean glued(ClientLevel level, BlockPos from, BlockPos to) {
        AABB span = SuperGlueEntity.span(from, to);
        return level.getEntitiesOfClass(SuperGlueEntity.class, span.inflate(1)).stream()
                .anyMatch(glue -> glue.getBoundingBox().equals(span));
    }

    @Override public boolean track(BlockState state) {
        return state.getBlock() instanceof TrackBlock;
    }

    // 轨道物品右键过一格轨道后，起点记在它的数据组件 TRACK_CONNECTING_FROM 里。
    @Override public Optional<BlockPos> trackStart(ItemStack held) {
        TrackPlacement.ConnectingFrom from = held.get(AllDataComponents.TRACK_CONNECTING_FROM);
        return from == null ? Optional.empty() : Optional.of(from.pos());
    }

    // 铺成的一段两头各记一份：两格轨道的方块实体都以对方的位置为键记着这一段。
    @Override public boolean trackConnected(ClientLevel level, BlockPos from, BlockPos to) {
        return level.getBlockEntity(from) instanceof TrackBlockEntity start && start.getConnections().containsKey(to)
                && level.getBlockEntity(to) instanceof TrackBlockEntity end && end.getConnections().containsKey(from);
    }
}
