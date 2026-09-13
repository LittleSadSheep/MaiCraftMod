// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.core.task.acquire.ObservedRecipeStockCost.*;

public final class ObservedRecipeStockCostTest {
    private static final ResourceLocation LOG = id("oak_log"), PLANK = id("oak_planks"), BAMBOO = id("bamboo"), STICK = id("stick"), COBBLE = id("cobblestone");
    public static void main(String[] args) {
        var recipes = Map.of(PLANK, List.of(new Recipe(4, List.of(need(LOG, 1)))), STICK, List.of(new Recipe(4, List.of(need(PLANK, 2)))));
        var observed = Map.of(LOG, 16L);
        var carried = Map.of(COBBLE, 192L);
        int wooden = ObservedRecipeStockCost.priority(true, observed, carried, List.of(need(PLANK, 2)), item -> recipes.getOrDefault(item, List.of()), Set.of(STICK));
        int bamboo = ObservedRecipeStockCost.priority(true, observed, carried, List.of(need(BAMBOO, 4)), item -> recipes.getOrDefault(item, List.of()), Set.of(STICK));
        check(wooden < bamboo, "observed warehouse logs prefer the finite oak-to-planks stick path over unknown bamboo");
        check(ObservedRecipeStockCost.priority(true, observed, carried, List.of(need(COBBLE, 1), need(STICK, 2)), item -> recipes.getOrDefault(item, List.of()), Set.of()) == 0,
                "carried cobblestone and observed logs together cover the complete ordinary shovel ingredient tree");
        check(ObservedRecipeStockCost.priority(false, observed, carried, List.of(need(PLANK, 2)), item -> recipes.getOrDefault(item, List.of()), Set.of()) == 1,
                "forbidden STORAGE contributes no recipe preference");
        check(ObservedRecipeStockCost.priority(true, Map.of(), carried, List.of(need(PLANK, 2)), item -> recipes.getOrDefault(item, List.of()), Set.of()) == 1,
                "expired or invalidated warehouse hints leave the existing structural ordering unchanged");
        check(ObservedRecipeStockCost.priority(true, Map.of(LOG, 1L), Map.of(), List.of(need(PLANK, 5)), item -> recipes.getOrDefault(item, List.of()), Set.of()) == 1,
                "one observed log cannot cover two four-plank crafting batches");
        check(ObservedRecipeStockCost.priority(true, Map.of(LOG, 1L), Map.of(), List.of(need(PLANK, 3), need(PLANK, 2)), item -> recipes.getOrDefault(item, List.of()), Set.of()) == 1,
                "one shared stock item cannot be spent twice across separate ingredient groups");
        check(ObservedRecipeStockCost.priority(true, Map.of(LOG, 1L), Map.of(PLANK, 1L), List.of(need(PLANK, 5)), item -> recipes.getOrDefault(item, List.of()), Set.of()) == 0,
                "carried and externally observed quantities combine without losing real batch leftovers");
        check(observed.equals(Map.of(LOG, 16L)) && carried.equals(Map.of(COBBLE, 192L)), "ranking never mutates inventory or warehouse evidence");
        System.out.println("ObservedRecipeStockCostTest: authorized observed wood, quantity accounting and unchanged fallback priority passed");
    }
    private static Need need(ResourceLocation item, int amount) { return new Need(List.of(item), amount); }
    private static ResourceLocation id(String path) { return ResourceLocation.withDefaultNamespace(path); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
