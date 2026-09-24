// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.core.task.acquire.ObservedRecipeStockCost.Need;
import org.maiwithu.maicraft.core.task.acquire.ObservedRecipeStockCost.Recipe;

/** 现货在任意层终止展开；共享原料只算一次，整批余料复用，补料切点按取得成本选择。 */
public final class RecipeMaterialPlanTest {
    private static final ResourceLocation LOG = id("log"), PLANK = id("plank"), PART = id("part"), IRON = id("iron");
    public static void main(String[] args) {
        Map<ResourceLocation, List<Recipe>> recipes = Map.of(
                PLANK, List.of(new Recipe(4, List.of(need(LOG, 1)))),
                PART, List.of(new Recipe(1, List.of(need(PLANK, 2), need(IRON, 1)))));
        var held = plan(List.of(need(PART, 1)), Map.of(PART, 1L), recipes, Map.of());
        check(held.supplies().isEmpty() && held.crafts().isEmpty(), "held finished items stop the tree immediately");
        var partial = plan(List.of(need(PART, 1)), Map.of(LOG, 1L), recipes, Map.of());
        check(partial.supplies().equals(List.of(need(IRON, 1))) && partial.remaining().get(PLANK) == 2,
                "available logs remove the wood branch even when another ingredient is missing");
        var batch = plan(List.of(need(PLANK, 3), need(PLANK, 2)), Map.of(LOG, 1L), recipes, Map.of(LOG, 10));
        check(batch.supplies().equals(List.of(need(LOG, 1))), "two demands cannot both spend the same original log");
        var mid = plan(List.of(need(PART, 1)), Map.of(), recipes, Map.of(PART, 2, LOG, 20, IRON, 20));
        check(mid.supplies().equals(List.of(need(PART, 1))) && mid.crafts().isEmpty(), "a cheaper known intermediate acquisition can beat raw materials");
        var raw = plan(List.of(need(PLANK, 8)), Map.of(), recipes, Map.of(PLANK, 20, LOG, 5));
        check(raw.supplies().equals(List.of(need(LOG, 2))), "batch yield selects logs rather than eight separate planks");
        Map<ResourceLocation, List<Recipe>> cycle = Map.of(PLANK, List.of(new Recipe(1, List.of(need(LOG, 1)))),
                LOG, List.of(new Recipe(1, List.of(need(PLANK, 1)))));
        check(!plan(List.of(need(PLANK, 1)), Map.of(), cycle, Map.of()).feasible(), "closed conversion cycles cannot manufacture free resources");
        check(plan(List.of(need(IRON, 1)), Map.of(), Map.of(), Map.of()).cost() >= 10000, "missing recipe is an unknown material source, not zero cost");
        System.out.println("RecipeMaterialPlanTest: passed");
    }

    private static RecipeMaterialPlan.Result plan(List<Need> needs, Map<ResourceLocation, Long> stock,
                                                  Map<ResourceLocation, List<Recipe>> recipes, Map<ResourceLocation, Integer> costs) {
        return RecipeMaterialPlan.estimate(needs, stock, item -> recipes.getOrDefault(item, List.of()),
                item -> new RecipeMaterialPlan.Source(costs.containsKey(item), costs.getOrDefault(item, 10000)), Set.of());
    }
    private static Need need(ResourceLocation item, int count) { return new Need(List.of(item), count); }
    private static ResourceLocation id(String value) { return ResourceLocation.fromNamespaceAndPath("test", value); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
