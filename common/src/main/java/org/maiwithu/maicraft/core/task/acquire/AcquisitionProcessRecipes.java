// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.tools.RecipeProbe;
import org.maiwithu.maicraft.core.task.acquire.ObservedRecipeStockCost.Need;
import org.maiwithu.maicraft.core.task.acquire.ObservedRecipeStockCost.Recipe;

/** 原生烧炼的输入也进入材料树；工序时长只作估价，实际工作面和燃料仍由烹饪执行器准备。 */
final class AcquisitionProcessRecipes {
    private final LocalPlayer player;
    private Map<ResourceLocation, List<Recipe>> cooking;
    AcquisitionProcessRecipes(LocalPlayer player) { this.player = player; }

    List<Recipe> cooking(ResourceLocation output) {
        if (cooking == null) {
            Map<ResourceLocation, List<Recipe>> indexed = new HashMap<>();
            for (var holder : ClientRuntime.requireContext(player).connection().getRecipeManager().getRecipes()) {
                if (!(holder.value() instanceof AbstractCookingRecipe recipe) || !RecipeProbe.usableIngredients(recipe)) continue;
                var stack = RecipeProbe.resultOf(recipe, player.level().registryAccess());
                if (stack.isEmpty()) continue;
                List<Need> ingredients = new ArrayList<>();
                for (var ingredient : recipe.getIngredients()) {
                    if (ingredient.isEmpty()) continue;
                    var alternatives = Arrays.stream(ingredient.getItems()).filter(item -> !item.isEmpty())
                            .map(item -> BuiltInRegistries.ITEM.getKey(item.getItem())).distinct().toList();
                    if (!alternatives.isEmpty()) ingredients.add(new Need(alternatives, 1));
                }
                if (!ingredients.isEmpty()) indexed.computeIfAbsent(BuiltInRegistries.ITEM.getKey(stack.getItem()), ignored -> new ArrayList<>())
                        .add(new Recipe(stack.getCount(), ingredients, Math.max(2, recipe.getCookingTime() / 20)));
            }
            cooking = new HashMap<>();
            indexed.forEach((id, recipes) -> cooking.put(id, List.copyOf(recipes)));
        }
        return cooking.getOrDefault(output, List.of());
    }
}
