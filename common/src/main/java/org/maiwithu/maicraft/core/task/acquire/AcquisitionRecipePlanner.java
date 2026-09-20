// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.tools.RecipeProbe;

/** 只推演缺料配方，不开菜单、不消耗材料；角色实际取材和合成仍由取物任务调度。 */
final class AcquisitionRecipePlanner {
    private final LocalPlayer player;
    private Map<ResourceLocation, List<CraftingRecipe>> recipeIndex;
    private final Map<ResourceLocation, List<ObservedRecipeStockCost.Recipe>> stockRecipes = new HashMap<>();

    AcquisitionRecipePlanner(LocalPlayer player) {
        this.player = player;
    }

    List<ObservedRecipeStockCost.Recipe> stockRecipes(ResourceLocation output) {
        // 仓库估价只展开普通九格内配方；未知配方不会凭空变成可用材料，也不会阻断其他候选。
        return stockRecipes.computeIfAbsent(output, ignored -> {
            List<ObservedRecipeStockCost.Recipe> result = new ArrayList<>();
            for (CraftingRecipe recipe : recipes().getOrDefault(output, List.of()).stream().limit(64).toList()) {
                try {
                    if (recipe.isSpecial() || recipe instanceof ShapedRecipe shaped
                            && (shaped.getWidth() > 3 || shaped.getHeight() > 3)) continue;
                    ItemStack stack = RecipeProbe.resultOf(recipe, player.level().registryAccess());
                    if (stack.isEmpty()) continue;
                    List<ObservedRecipeStockCost.Need> ingredients = new ArrayList<>();
                    for (Ingredient ingredient : recipe.getIngredients()) {
                        if (ingredient == null || ingredient.isEmpty()) continue;
                        var ids = Arrays.stream(ingredient.getItems())
                                .filter(value -> value != null && !value.isEmpty())
                                .map(value -> BuiltInRegistries.ITEM.getKey(value.getItem())).distinct().toList();
                        ingredients.add(new ObservedRecipeStockCost.Need(ids, 1));
                    }
                    if (!ingredients.isEmpty() && ingredients.size() <= 9)
                        result.add(new ObservedRecipeStockCost.Recipe(stack.getCount(), ingredients));
                } catch (RuntimeException unavailable) {
                    // 这只是排序提示；读取不了的配方不能用来承诺库存已经足够。
                }
            }
            return List.copyOf(result);
        });
    }

    Map<ResourceLocation, List<CraftingRecipe>> recipes() {
        // 一次任务共用按成品整理的配方索引；递归找原料时复用，避免每一层重新扫描整张配方表。
        if (recipeIndex != null) return recipeIndex;
        Map<ResourceLocation, List<CraftingRecipe>> indexed = new LinkedHashMap<>();
        for (var holder : ClientRuntime.requireContext(player).connection().getRecipeManager().getRecipes()) {
            if (!(holder.value() instanceof CraftingRecipe recipe)) continue;
            if (!RecipeProbe.usableIngredients(recipe)) continue;
            ItemStack output = RecipeProbe.resultOf(recipe, player.level().registryAccess());
            if (output.isEmpty()) continue;
            ResourceLocation outputId = BuiltInRegistries.ITEM.getKey(output.getItem());
            indexed.computeIfAbsent(outputId, ignored -> new ArrayList<>()).add(recipe);
        }
        Map<ResourceLocation, List<CraftingRecipe>> frozen = new LinkedHashMap<>();
        indexed.forEach((id, recipes) -> frozen.put(id, List.copyOf(recipes)));
        recipeIndex = Map.copyOf(frozen);
        return recipeIndex;
    }
}
