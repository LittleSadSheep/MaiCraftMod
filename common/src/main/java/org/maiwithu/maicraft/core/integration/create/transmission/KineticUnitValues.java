// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.maiwithu.maicraft.core.integration.create.transmission.KineticMaterialCosts.*;

/** Reuses finite acyclic price proofs by dependency depth instead of recursively revisiting recycling recipes. */
final class KineticUnitValues {
    private static final int MAX_VALUE_ITEMS = 1024;
    record Value(double value, Set<String> recipes, Set<String> issues, Set<String> dependencies, int depth) {}
    private final Snapshot snapshot;
    private final Map<String, Value> values = new LinkedHashMap<>();
    private int evaluations;
    private boolean limited;

    KineticUnitValues(Snapshot snapshot, Set<String> roots) {
        this.snapshot = snapshot;
        Set<String> reachable = reachable(roots);
        for (String id : reachable) {
            Double raw = snapshot.rawUnitValues().get(id);
            if (raw != null) values.put(id, new Value(raw, Set.of(), Set.of(), Set.of(id), 0));
            else if (snapshot.recipes().getOrDefault(id, List.of()).isEmpty()) values.put(id, unknown(id, "no_interpretable_recipe_or_raw_unit"));
        }
        for (int depth = 1; depth < MAX_DEPTH; depth++) {
            Map<String, Value> previous = Map.copyOf(values); boolean changed = false;
            for (String id : reachable.stream().sorted().toList()) {
                if (snapshot.rawUnitValues().containsKey(id)) continue;
                Value best = previous.get(id);
                for (Recipe recipe : snapshot.recipes().getOrDefault(id, List.of())) {
                    if (evaluations >= MAX_SEARCH_ENTRIES / 2) { limited = true; break; }
                    evaluations++;
                    Value candidate = evaluate(recipe, previous);
                    if (better(candidate, best)) best = candidate;
                }
                if (best != null && !best.equals(previous.get(id))) { values.put(id, best); changed = true; }
            }
            if (evaluations >= MAX_SEARCH_ENTRIES / 2 || !changed) break;
            if (depth == MAX_DEPTH - 1) limited = true;
        }
    }
    Value get(String id) {
        return values.getOrDefault(id, unknown(id, limited ? "recipe_search_budget" : "recipe_cycle_or_no_acyclic_path"));
    }
    int evaluations() { return evaluations; }
    List<Recipe> orderedRecipes(String id) {
        return snapshot.recipes().getOrDefault(id, List.of()).stream().sorted(java.util.Comparator
                .comparingInt((Recipe recipe) -> { Value value = evaluate(recipe, values); return value == null ? Integer.MAX_VALUE : value.depth(); })
                .thenComparingDouble(recipe -> { Value value = evaluate(recipe, values); return value == null ? Double.MAX_VALUE : value.value(); })
                .thenComparing(Recipe::id)).toList();
    }
    private Set<String> reachable(Set<String> roots) {
        Set<String> seen = new LinkedHashSet<>(); var pending = new ArrayDeque<String>(roots.stream().sorted().toList());
        while (!pending.isEmpty()) {
            if (seen.size() >= MAX_VALUE_ITEMS) { limited = true; break; }
            String id = pending.removeFirst(); if (!seen.add(id)) continue;
            // Anchors define material value; decompression/recycling only belongs to the separate real-stock calculation.
            if (snapshot.rawUnitValues().containsKey(id)) continue;
            for (Recipe recipe : snapshot.recipes().getOrDefault(id, List.of()))
                for (Ingredient ingredient : recipe.ingredients()) for (String alternative : ingredient.alternatives())
                    if (!seen.contains(alternative) && !pending.contains(alternative)) {
                        if (seen.size() + pending.size() >= MAX_VALUE_ITEMS) limited = true;
                        else pending.addLast(alternative);
                    }
        }
        return seen;
    }
    private Value evaluate(Recipe recipe, Map<String, Value> known) {
        double cost = 0; int depth = 0; Set<String> trace = new LinkedHashSet<>(), issues = new LinkedHashSet<>(), dependencies = new LinkedHashSet<>();
        trace.add(recipe.id()); dependencies.add(recipe.outputId());
        if (recipe.conditional()) issues.add("recipe_conditions_unverified:" + recipe.id());
        if (snapshot.unknownOutputs().contains(recipe.outputId())) issues.add("uninterpreted_recipe_alternatives:" + recipe.outputId());
        for (Ingredient ingredient : recipe.ingredients()) {
            Value choice = null;
            for (String alternative : ingredient.alternatives()) {
                Value candidate = known.get(alternative);
                if (candidate != null && !candidate.dependencies().contains(recipe.outputId()) && better(candidate, choice)) choice = candidate;
            }
            if (choice == null) return null;
            cost += choice.value() * ingredient.count(); depth = Math.max(depth, choice.depth());
            trace.addAll(choice.recipes()); issues.addAll(choice.issues()); dependencies.addAll(choice.dependencies());
        }
        return depth + 1 >= MAX_DEPTH ? null : new Value(cost / recipe.outputCount(), Set.copyOf(trace), Set.copyOf(issues), Set.copyOf(dependencies), depth + 1);
    }
    private static boolean better(Value candidate, Value current) {
        return candidate != null && (current == null || candidate.value() < current.value()
                || Double.compare(candidate.value(), current.value()) == 0 && (candidate.depth() < current.depth()
                || candidate.depth() == current.depth() && candidate.issues().size() < current.issues().size()));
    }
    private static Value unknown(String id, String reason) {
        return new Value(UNKNOWN_UNIT_VALUE, Set.of(), Set.of(reason + ":" + id), Set.of(id), 0);
    }
}
