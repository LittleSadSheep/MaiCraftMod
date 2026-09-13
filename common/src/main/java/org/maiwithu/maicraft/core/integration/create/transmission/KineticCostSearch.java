// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.maiwithu.maicraft.core.integration.create.transmission.KineticMaterialCosts.*;

/** Bounded recipe search; batch leftovers and carried stock are shared across one BOM, never between candidates. */
final class KineticCostSearch {
    private final Snapshot snapshot;
    private final Set<String> issues = new LinkedHashSet<>(), usedRecipes = new LinkedHashSet<>();
    private int visited;
    private record Unit(double value, Set<String> recipes, Set<String> issues) {}
    private static final class Stock {
        final Map<String, Long> available = new LinkedHashMap<>(), deficits = new LinkedHashMap<>();
        final Set<String> recipes = new LinkedHashSet<>(), issues = new LinkedHashSet<>(); double cost;
        Stock() {}
        Stock(Stock other) { available.putAll(other.available); deficits.putAll(other.deficits); recipes.addAll(other.recipes); issues.addAll(other.issues); cost = other.cost; }
        void need(String id, long count, double units) { deficits.merge(id, count, Long::sum); cost += count * units; }
    }
    KineticCostSearch(Snapshot snapshot) { this.snapshot = snapshot; issues.addAll(snapshot.issues()); }
    Quote quote(Map<String, Integer> bom) {
        Stock stock = new Stock(); for (var entry : snapshot.carried().entrySet()) stock.available.put(entry.getKey(), entry.getValue().longValue());
        Map<String, Long> missing = new LinkedHashMap<>(); var rows = new JsonArray(); double value = 0;
        for (String id : bom.keySet().stream().sorted().toList()) {
            long required = bom.get(id), held = Math.min(required, stock.available.getOrDefault(id, 0L));
            stock.available.put(id, stock.available.getOrDefault(id, 0L) - held);
            if (required > held) missing.put(id, required - held);
            Unit unit = unit(id, new LinkedHashSet<>(), 0);
            if (unit == null) unit = unknown(id, "recipe_cycle_or_no_acyclic_path");
            value += required * unit.value; usedRecipes.addAll(unit.recipes); issues.addAll(unit.issues);
            var row = new JsonObject(); row.addProperty("item_id", id); row.addProperty("count", required); row.addProperty("carried_reserved", held);
            row.addProperty("unit_material_value", unit.value); row.addProperty("material_value", required * unit.value); rows.add(row);
        }
        // Direct building stock is reserved first so a recipe cannot accidentally consume another final BOM item.
        for (var demand : missing.entrySet()) {
            Stock acquired = acquire(demand.getKey(), demand.getValue(), stock, new LinkedHashSet<>(), 0);
            if (acquired == null) { acquired = new Stock(stock); acquired.need(demand.getKey(), demand.getValue(), UNKNOWN_UNIT_VALUE); acquired.issues.add("recipe_cycle_or_no_acyclic_path:" + demand.getKey()); }
            stock = acquired;
        }
        issues.addAll(stock.issues); usedRecipes.addAll(stock.recipes);
        var evidence = new JsonObject(); evidence.add("snapshot", snapshot.evidence()); evidence.add("items", rows);
        var recipes = new JsonArray(); snapshot.recipes().values().stream().flatMap(List::stream).filter(recipe -> usedRecipes.contains(recipe.id())).limit(64).forEach(recipe -> recipes.add(recipe.json()));
        evidence.add("effective_recipes_used", recipes); evidence.addProperty("recipe_trace_truncated", usedRecipes.size() > 64);
        evidence.addProperty("batch_yields_applied", true); evidence.addProperty("recipe_search_entries", visited);
        evidence.addProperty("valuation_scope", "Relative raw-resource model; material value is amortized, acquisition rounds whole batches and reuses leftovers. Machine access, fuel, travel and crafting execution remain unverified.");
        evidence.addProperty("stock_allocation", "reserve final BOM first; bounded deterministic greedy ingredient choices, not a global crafting optimizer");
        evidence.addProperty("unknown_unit_estimate", UNKNOWN_UNIT_VALUE);
        return new Quote(value, stock.cost, missing, stock.deficits, issues.stream().sorted().limit(64).toList(), evidence);
    }
    private Unit unit(String id, Set<String> path, int depth) {
        if (path.contains(id)) return null;
        if (!budget(depth)) return unknown(id, "recipe_search_budget");
        Double base = snapshot.rawUnitValues().get(id);
        if (base != null) return new Unit(base, Set.of(), Set.of());
        List<Recipe> recipes = snapshot.recipes().getOrDefault(id, List.of());
        if (recipes.isEmpty()) return unknown(id, "no_interpretable_recipe_or_raw_unit");
        Set<String> next = new LinkedHashSet<>(path); next.add(id); Unit best = null;
        for (Recipe recipe : recipes) {
            if (visited >= MAX_SEARCH_ENTRIES) break;
            double cost = 0; boolean valid = true; Set<String> trace = new LinkedHashSet<>(), unknown = new LinkedHashSet<>();
            trace.add(recipe.id());
            if (recipe.conditional()) unknown.add("recipe_conditions_unverified:" + recipe.id());
            if (snapshot.unknownOutputs().contains(id)) unknown.add("uninterpreted_recipe_alternatives:" + id);
            for (Ingredient ingredient : recipe.ingredients()) {
                Unit choice = null;
                for (String alternative : ingredient.alternatives()) {
                    if (visited >= MAX_SEARCH_ENTRIES) break;
                    Unit candidate = unit(alternative, next, depth + 1);
                    if (candidate != null && (choice == null || candidate.value < choice.value)) choice = candidate;
                }
                if (choice == null) { valid = false; break; }
                cost += choice.value * ingredient.count(); trace.addAll(choice.recipes); unknown.addAll(choice.issues);
            }
            if (valid && (best == null || cost / recipe.outputCount() < best.value)) best = new Unit(cost / recipe.outputCount(), trace, unknown);
        }
        return best;
    }
    private Stock acquire(String id, long count, Stock prior, Set<String> path, int depth) {
        Stock initial = new Stock(prior); long held = Math.min(count, initial.available.getOrDefault(id, 0L));
        initial.available.put(id, initial.available.getOrDefault(id, 0L) - held); long missing = count - held;
        if (missing == 0) return initial;
        if (path.contains(id)) return null;
        if (!budget(depth) || missing > 1_000_000_000L) { initial.need(id, missing, UNKNOWN_UNIT_VALUE); initial.issues.add("recipe_search_budget:" + id); return initial; }
        Double base = snapshot.rawUnitValues().get(id); Stock best = null;
        if (base != null) { best = new Stock(initial); best.need(id, missing, base); }
        List<Recipe> recipes = snapshot.recipes().getOrDefault(id, List.of());
        if (recipes.isEmpty() && best == null) { best = initial; best.need(id, missing, UNKNOWN_UNIT_VALUE); best.issues.add("no_interpretable_recipe_or_raw_unit:" + id); return best; }
        Set<String> next = new LinkedHashSet<>(path); next.add(id);
        for (Recipe recipe : recipes) {
            if (visited >= MAX_SEARCH_ENTRIES) break;
            Stock candidate = new Stock(initial); long batches = (missing + recipe.outputCount() - 1) / recipe.outputCount();
            for (Ingredient ingredient : recipe.ingredients()) {
                Stock chosen = null;
                for (String alternative : ingredient.alternatives()) {
                    if (visited >= MAX_SEARCH_ENTRIES) break;
                    Stock attempt = acquire(alternative, batches * ingredient.count(), candidate, next, depth + 1);
                    if (attempt != null && (chosen == null || attempt.cost < chosen.cost)) chosen = attempt;
                }
                candidate = chosen; if (candidate == null) break;
            }
            if (candidate == null) continue;
            candidate.available.merge(id, batches * recipe.outputCount() - missing, Long::sum); candidate.recipes.add(recipe.id());
            if (recipe.conditional()) candidate.issues.add("recipe_conditions_unverified:" + recipe.id());
            if (snapshot.unknownOutputs().contains(id)) candidate.issues.add("uninterpreted_recipe_alternatives:" + id);
            if (best == null || candidate.cost < best.cost) best = candidate;
        }
        return best;
    }
    private boolean budget(int depth) {
        if (depth >= MAX_DEPTH || visited >= MAX_SEARCH_ENTRIES) { issues.add("recipe_search_budget"); return false; }
        visited++; return true;
    }
    private static Unit unknown(String id, String reason) { return new Unit(UNKNOWN_UNIT_VALUE, Set.of(), Set.of(reason + ":" + id)); }
}
