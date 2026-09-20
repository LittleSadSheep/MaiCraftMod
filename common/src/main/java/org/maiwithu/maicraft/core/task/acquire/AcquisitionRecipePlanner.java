// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.tools.RecipeProbe;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.task.craft.CraftRecoveryCandidate;

/** 只推演缺料配方，不开菜单、不消耗材料；角色实际取材和合成仍由取物任务调度。 */
final class AcquisitionRecipePlanner {
    private static final int STRUCTURAL_RECIPE_DEPTH = 6;
    static final int UNREACHABLE_STRUCTURE_COST = 1_000_000;
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

    private record StructureKey(
            ResourceLocation itemId,
            int remainingDepth,
            Set<ResourceLocation> blocked) {
        private StructureKey {
            blocked = Set.copyOf(blocked);
        }
    }

    /** 比较缺料路线要再过几层合成；深度只影响排序，不证明世界里有这些材料。 */
    int structureCost(CraftRecoveryCandidate candidate, AcquisitionNeed parent) {
        // 只估计还需经过几层普通合成，用来给候选排序；没有说世界里一定有那些原材料。
        Map<StructureKey, Integer> memo = new HashMap<>();
        int total = 0;
        for (var ingredient : candidate.ingredients()) {
            int missing = ingredient.missing();
            if (missing <= 0) continue;
            int best = UNREACHABLE_STRUCTURE_COST;
            for (ResourceLocation id : ingredient.itemIds()) {
                if (parent.lineageItems.contains(id)) continue;
                best = Math.min(best, recursiveCraftDepth(
                        id, parent.lineageItems, new LinkedHashSet<>(), memo,
                        STRUCTURAL_RECIPE_DEPTH));
            }
            if (best >= UNREACHABLE_STRUCTURE_COST) return UNREACHABLE_STRUCTURE_COST;
            total = Math.min(UNREACHABLE_STRUCTURE_COST,
                    total + missing * Math.max(1, best + 1));
        }
        return total;
    }

    private int recursiveCraftDepth(
            ResourceLocation itemId,
            Set<ResourceLocation> forbidden,
            Set<ResourceLocation> visiting,
            Map<StructureKey, Integer> memo,
            int remainingDepth) {
        // 已经带着或没有更上游配方的物品作为边界；遇到循环或超过前瞻深度就认为这条估计不可靠。
        if (forbidden.contains(itemId) || visiting.contains(itemId)) {
            return UNREACHABLE_STRUCTURE_COST;
        }
        Item item = BuiltInRegistries.ITEM.get(itemId);
        if (PlayerInv.buildableCount(player.getInventory(), item) > 0) return 0;
        List<CraftingRecipe> recipes = recipes().getOrDefault(
                itemId, List.of());
        if (recipes.isEmpty()) return 0;
        if (remainingDepth <= 0) return UNREACHABLE_STRUCTURE_COST;
        Set<ResourceLocation> blocked = new LinkedHashSet<>(forbidden);
        blocked.addAll(visiting);
        StructureKey key = new StructureKey(itemId, remainingDepth, blocked);
        Integer remembered = memo.get(key);
        if (remembered != null) return remembered;

        visiting.add(itemId);
        int bestRecipe = UNREACHABLE_STRUCTURE_COST;
        for (CraftingRecipe recipe : recipes) {
            int criticalDepth = 0;
            boolean valid = true;
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient == null || ingredient.isEmpty()) continue;
                int ingredientDepth = UNREACHABLE_STRUCTURE_COST;
                for (ItemStack accepted : ingredient.getItems()) {
                    if (accepted == null || accepted.isEmpty()) continue;
                    ResourceLocation acceptedId = BuiltInRegistries.ITEM.getKey(
                            accepted.getItem());
                    ingredientDepth = Math.min(ingredientDepth, recursiveCraftDepth(
                            acceptedId, forbidden, visiting, memo, remainingDepth - 1));
                }
                if (ingredientDepth >= UNREACHABLE_STRUCTURE_COST) {
                    valid = false;
                    break;
                }
                criticalDepth = Math.max(criticalDepth, ingredientDepth);
            }
            if (valid) bestRecipe = Math.min(bestRecipe, 1 + criticalDepth);
        }
        visiting.remove(itemId);
        // 没有配方才是真正的叶子；所有配方都绕回祖先时仍是循环，不能把它算成免费原料。
        memo.put(key, bestRecipe);
        return bestRecipe;
    }

    int ingredientStructureCost(
            List<ResourceLocation> itemIds, AcquisitionNeed parent) {
        Map<StructureKey, Integer> memo = new HashMap<>();
        int best = UNREACHABLE_STRUCTURE_COST;
        for (ResourceLocation itemId : itemIds) {
            if (parent.lineageItems.contains(itemId)) continue;
            best = Math.min(best, recursiveCraftDepth(
                    itemId, parent.lineageItems, new LinkedHashSet<>(), memo,
                    STRUCTURAL_RECIPE_DEPTH));
        }
        return best;
    }

}
