// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ae2;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import appeng.recipes.AERecipeTypes;
import appeng.recipes.transform.TransformRecipe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;

import org.maiwithu.maicraft.compat.ae2.Ae2Transforms;

/**
 * AE2 转化配方的读写端：读这个世界的配方表里 AE2 的转化配方（客户端同步过来的那一份），
 * 只留"泡在流体里"的，料按配方顺序换成物品 ID，流体按 AE2 给画面用的那份流体表换成流体 ID。只翻译、不判断。
 */
public final class AppliedEnergisticsTransforms implements Ae2Transforms {

    @Override public List<Recipe> fluidRecipes(Level level) {
        if (level == null) return List.of();
        List<Recipe> out = new ArrayList<>();
        for (RecipeHolder<TransformRecipe> holder : level.getRecipeManager().getAllRecipesFor(AERecipeTypes.TRANSFORM)) {
            TransformRecipe recipe = holder.value();
            if (!recipe.circumstance.isFluid()) continue;
            List<List<String>> ingredients = new ArrayList<>();
            for (Ingredient ingredient : recipe.ingredients) ingredients.add(itemsOf(ingredient));
            List<String> fluids = new ArrayList<>();
            for (Fluid fluid : recipe.circumstance.getFluidsForRendering()) {
                fluids.add(BuiltInRegistries.FLUID.getKey(fluid).toString());
            }
            ItemStack result = recipe.getResultItem();
            out.add(new Recipe(holder.id().toString(), ingredients,
                    result.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(result.getItem()).toString(),
                    result.getCount(), fluids));
        }
        return out;
    }

    // 一样料能用哪几种物品：按配方里列的先后，去重。
    private static List<String> itemsOf(Ingredient ingredient) {
        Set<String> items = new LinkedHashSet<>();
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) items.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        return List.copyOf(items);
    }
}
