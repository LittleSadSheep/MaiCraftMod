// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.create;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.simibubi.create.AllRecipeTypes;
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
 * 砂纸配方从客户端的配方表里按 Create 的砂纸打磨类型取，原料取配方唯一那一格能放的全部物品。只翻译，不判断。
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
}
