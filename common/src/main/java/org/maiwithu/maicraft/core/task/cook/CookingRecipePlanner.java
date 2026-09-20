// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.cook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.Constants;
import org.maiwithu.maicraft.core.tools.RecipeProbe;

/** 只读比较加工配方、燃料和准备路线；选定后由烹饪执行器操作同一台炉子并确认实际结果。 */
final class CookingRecipePlanner {
    private final LocalPlayer player;
    private Map<Item, List<CraftRoute>> craftRoutes;

    CookingRecipePlanner(LocalPlayer player) { this.player = player; }

    List<CraftRoute> routesFor(Item item) {
        if (craftRoutes == null) craftRoutes = indexCraftRoutes();
        return craftRoutes.getOrDefault(item, List.of());
    }

    record IngredientGroup(List<Item> alternatives, int uses) {}
    record CraftRoute(
            ResourceLocation recipeId,
            int outputCount,
            List<IngredientGroup> ingredients,
            boolean requiresWorkstation) {}
    // 为备料估价整理普通配方的产量与材料组；这里只读配方，实际缺料仍交给取物任务处理。
    private Map<Item, List<CraftRoute>> indexCraftRoutes() {
        Map<Item, List<CraftRoute>> indexed = new HashMap<>();
        var manager = ClientRuntime.requireContext(player).connection().getRecipeManager();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            try {
                if (!(holder.value() instanceof CraftingRecipe recipe)
                        || recipe.isSpecial()
                        || !RecipeProbe.usableIngredients(recipe)) {
                    continue;
                }
                ItemStack output = RecipeProbe.resultOf(
                        recipe, player.level().registryAccess());
                if (output.isEmpty()) continue;
                List<IngredientGroup> groups = ingredientGroups(recipe);
                if (groups.isEmpty()) continue;
                int ingredientUses = recipe.getIngredients().stream()
                        .mapToInt(ingredient -> ingredient == null || ingredient.isEmpty() ? 0 : 1)
                        .sum();
                boolean workstation = recipe instanceof ShapedRecipe shaped
                        ? shaped.getWidth() > 2 || shaped.getHeight() > 2
                        : ingredientUses > 4;
                indexed.computeIfAbsent(output.getItem(), ignored -> new ArrayList<>())
                        .add(new CraftRoute(
                                holder.id(), Math.max(1, output.getCount()),
                                groups, workstation));
            } catch (RuntimeException brokenRecipe) {
                Constants.LOG.debug(
                        "[maicraft-cook] skipped unusable acquisition-cost recipe {}: {}",
                        holder.id(), brokenRecipe.toString());
            }
        }
        indexed.replaceAll((item, routes) -> routes.stream()
                .sorted(Comparator.comparing(route -> route.recipeId().toString()))
                .toList());
        return Map.copyOf(indexed);
    }

    // 候选物品列表完全相同的材料格合成一组，并数这种材料用了几格，避免重复遍历相同要求。
    private static List<IngredientGroup> ingredientGroups(CraftingRecipe recipe) {
        Map<List<Item>, Integer> uses = new LinkedHashMap<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient == null || ingredient.isEmpty()) continue;
            List<Item> alternatives = Arrays.stream(ingredient.getItems())
                    .filter(stack -> stack != null && !stack.isEmpty())
                    .map(ItemStack::getItem)
                    .distinct()
                    .sorted(Comparator.comparing(item ->
                            BuiltInRegistries.ITEM.getKey(item).toString()))
                    .toList();
            if (alternatives.isEmpty()) return List.of();
            uses.merge(alternatives, 1, Integer::sum);
        }
        return uses.entrySet().stream()
                .map(entry -> new IngredientGroup(entry.getKey(), entry.getValue()))
                .toList();
    }

}
