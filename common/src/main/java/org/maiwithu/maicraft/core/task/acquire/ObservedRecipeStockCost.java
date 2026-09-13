// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;

/** Resource-only ranking hint. Its hypothetical ledger can never make CraftOps executable or withdraw stock. */
public final class ObservedRecipeStockCost {
    public record Need(List<ResourceLocation> alternatives, int count) {
        public Need { alternatives = List.copyOf(alternatives); if (count < 1 || count > 32768) throw new IllegalArgumentException("recipe_hint_quantity"); }
    }
    public record Recipe(int outputCount, List<Need> ingredients) {
        public Recipe { ingredients = List.copyOf(ingredients); if (outputCount < 1 || ingredients.isEmpty()) throw new IllegalArgumentException("recipe_hint_recipe"); }
    }
    private static final class Budget { int remaining = 2048; boolean spend() { return remaining-- > 0; } }
    private ObservedRecipeStockCost() {}
    /** Zero means the complete ingredient tree fits observed stock; one retains the existing structural ordering. */
    public static int priority(boolean storageAllowed, Map<ResourceLocation, Long> observed, Map<ResourceLocation, Long> carried,
            List<Need> ingredients, Function<ResourceLocation, List<Recipe>> recipes, Set<ResourceLocation> blocked) {
        if (!storageAllowed || observed.isEmpty() || ingredients.isEmpty()) return 1;
        Map<ResourceLocation, Long> pool = new HashMap<>(); carried.forEach((item, amount) -> { if (amount > 0) pool.put(item, amount); });
        observed.forEach((item, amount) -> { if (amount > 0) pool.merge(item, amount, (a, b) -> a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b); });
        try { return ingredients(ingredients, pool, recipes, blocked, new HashSet<>(), 6, new Budget()) ? 0 : 1; }
        catch (IllegalArgumentException | ArithmeticException incomplete) { return 1; }
    }
    private static boolean ingredients(List<Need> requirements, Map<ResourceLocation, Long> pool,
            Function<ResourceLocation, List<Recipe>> recipes, Set<ResourceLocation> blocked, Set<ResourceLocation> visiting, int depth, Budget budget) {
        if (requirements.size() > 64) return false;
        List<Need> ordered = requirements.stream().sorted(Comparator.comparingInt(need -> need.alternatives.size())).toList();
        for (Need need : ordered) if (!take(need, pool, recipes, blocked, visiting, depth, budget)) return false;
        return true;
    }
    private static boolean take(Need need, Map<ResourceLocation, Long> pool, Function<ResourceLocation, List<Recipe>> recipes,
            Set<ResourceLocation> blocked, Set<ResourceLocation> visiting, int depth, Budget budget) {
        if (!budget.spend() || need.alternatives.isEmpty() || need.alternatives.size() > 128) return false;
        int missing = need.count;
        for (ResourceLocation item : need.alternatives) {
            if (blocked.contains(item)) continue;
            long available = Math.max(0, pool.getOrDefault(item, 0L)); int use = (int) Math.min(missing, available);
            pool.put(item, available - use); missing -= use; if (missing == 0) return true;
        }
        if (depth <= 0) return false;
        for (ResourceLocation item : need.alternatives) {
            if (blocked.contains(item) || visiting.contains(item)) continue;
            for (Recipe recipe : recipes.apply(item).stream().limit(64).toList()) {
                if (!budget.spend()) return false;
                int batches = (missing + recipe.outputCount - 1) / recipe.outputCount;
                Map<ResourceLocation, Long> trial = new HashMap<>(pool); List<Need> expanded = new ArrayList<>();
                for (Need ingredient : recipe.ingredients) expanded.add(new Need(ingredient.alternatives, Math.multiplyExact(ingredient.count, batches)));
                visiting.add(item);
                boolean supplied;
                try { supplied = ingredients(expanded, trial, recipes, blocked, visiting, depth - 1, budget); }
                finally { visiting.remove(item); }
                if (!supplied) continue;
                long produced = Math.multiplyExact((long) batches, recipe.outputCount);
                trial.merge(item, produced, Math::addExact); trial.put(item, trial.get(item) - missing);
                pool.clear(); pool.putAll(trial); return true;
            }
        }
        return false;
    }
}
