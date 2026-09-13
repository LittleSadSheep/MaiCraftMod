// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.maiwithu.maicraft.core.integration.create.transmission.KineticMaterialCosts.*;

/** Real batch ratios, stock opportunity cost, tag alternatives and cycles, with no game instance needed. */
public final class KineticMaterialCostsTest {
    public static void main(String[] args) {
        Map<String, List<Recipe>> recipes = new LinkedHashMap<>();
        recipes.put("create:shaft", List.of(recipe("test:craft_shafts", "create:shaft", 8, ingredient("create:andesite_alloy", 2))));
        Snapshot normal = snapshot(recipes, Map.of("create:andesite_alloy", 1.0), Map.of());
        var one = quote(Map.of("create:shaft", 1), normal);
        equal(.25, one.materialValueUnits(), "2 alloy -> 8 shafts amortizes material value");
        equal(2, one.acquisitionDeficitUnits(), "one requested shaft still needs one complete batch");
        var nine = quote(Map.of("create:shaft", 9), normal);
        equal(2.25, nine.materialValueUnits(), "material value does not inflate to leftover outputs");
        equal(4, nine.acquisitionDeficitUnits(), "nine shafts require two full batches");
        recipes.put("create:gearbox", List.of(recipe("test:gearbox", "create:gearbox", 1, ingredient("create:shaft", 4))));
        var stocked = quote(Map.of("create:gearbox", 1), snapshot(recipes, Map.of("create:andesite_alloy", 1.0), Map.of("create:gearbox", 1)));
        equal(1, stocked.materialValueUnits(), "carried expensive parts retain their complete material value");
        equal(0, stocked.acquisitionDeficitUnits(), "carried finished part has no additional acquisition deficit");
        var ingredientsCarried = quote(Map.of("create:gearbox", 1), snapshot(recipes, Map.of("create:andesite_alloy", 1.0), Map.of("create:andesite_alloy", 2)));
        equal(0, ingredientsCarried.acquisitionDeficitUnits(), "actual carried ingredients reduce acquisition deficit");
        var shared = quote(Map.of("create:shaft", 1, "create:gearbox", 1), snapshot(recipes, Map.of("create:andesite_alloy", 1.0), Map.of()));
        equal(2, shared.acquisitionDeficitUnits(), "one shaft batch supplies both BOM demands using its leftovers");
        var protectedFinal = quote(Map.of("create:shaft", 4, "create:gearbox", 1), snapshot(recipes, Map.of("create:andesite_alloy", 1.0), Map.of("create:shaft", 4)));
        equal(2, protectedFinal.acquisitionDeficitUnits(), "final BOM reserves are not double-counted as crafting ingredients");

        recipes.put("minecraft:chain", List.of(recipe("test:tag_chain", "minecraft:chain", 1,
                new Ingredient(List.of("create:zinc_ingot", "minecraft:iron_ingot"), 2))));
        var alternatives = quote(Map.of("minecraft:chain", 2), snapshot(recipes,
                Map.of("minecraft:iron_ingot", 3.0, "create:zinc_ingot", 1.0), Map.of()));
        equal(4, alternatives.materialValueUnits(), "tag alternatives choose the lowest raw-resource equivalent");
        check(alternatives.acquisitionDeficits().get("create:zinc_ingot") == 4, "chosen tag alternatives retain exact ingredient counts");
        recipes.put("create:shaft", List.of(recipes.get("create:shaft").getFirst(),
                new Recipe("test:saw_shafts", "create:shaft", 6, List.of(ingredient("create:andesite_alloy", 1)), "audited_deterministic_saw", true)));
        var cutting = quote(Map.of("create:shaft", 6), snapshot(recipes, Map.of("create:andesite_alloy", 1.0), Map.of()));
        equal(1, cutting.materialValueUnits(), "alternate installed one-alloy -> six-shaft recipe changes the value");
        check(cutting.issues().contains("recipe_conditions_unverified:test:saw_shafts"), "machine prerequisites are not claimed available");
        equal(1, cutting.acquisitionDeficitUnits(), "alternate batch yield is used for acquisition too");
        equal(2, quote(Map.of("create:shaft", 8), normal).materialValueUnits(), "earlier immutable snapshot is not rewritten by a later recipe map");

        Map<String, List<Recipe>> cycle = Map.of(
                "minecraft:iron_nugget", List.of(recipe("test:to_nuggets", "minecraft:iron_nugget", 9, ingredient("minecraft:iron_ingot", 1))),
                "minecraft:iron_ingot", List.of(recipe("test:to_ingot", "minecraft:iron_ingot", 1, ingredient("minecraft:iron_nugget", 9))));
        var iron = quote(Map.of("minecraft:iron_ingot", 1), snapshot(cycle, Map.of("minecraft:iron_ingot", 1.0), Map.of("minecraft:iron_nugget", 9)));
        equal(1, iron.materialValueUnits(), "base commodity anchor prevents ingot/nugget valuation recursion");
        equal(0, iron.acquisitionDeficitUnits(), "carried nine nuggets legitimately satisfy the ingot recipe");
        var nugget = quote(Map.of("minecraft:iron_nugget", 9), snapshot(cycle, Map.of("minecraft:iron_ingot", 1.0), Map.of()));
        equal(1, nugget.materialValueUnits(), "nugget value retains the native nine-to-one ratio");
        var unsupportedCycle = quote(Map.of("minecraft:iron_ingot", 1), snapshot(cycle, Map.of(), Map.of()));
        check(unsupportedCycle.materialValueUnits() > 0 && unsupportedCycle.acquisitionDeficitUnits() > 0 && unsupportedCycle.hasUnknowns(),
                "unanchored cycles terminate with explicit positive unknown cost");
        var unknown = quote(Map.of("test:dynamic_part", 3), snapshot(Map.of(), Map.of(), Map.of()));
        equal(3 * UNKNOWN_UNIT_VALUE, unknown.materialValueUnits(), "unknown parts never receive zero material value");
        check(unknown.hasUnknowns() && !unknown.json().get("acquisition_plan_verified").getAsBoolean(), "unknown estimates cannot authorize crafting");

        Map<String, List<Recipe>> deep = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) deep.put("test:part" + i, List.of(recipe("test:depth" + i, "test:part" + i, 1, ingredient("test:part" + (i + 1), 1))));
        var bounded = quote(Map.of("test:part0", 1), snapshot(deep, Map.of(), Map.of()));
        check(bounded.hasUnknowns() && bounded.evidence().get("recipe_search_entries").getAsInt() <= MAX_SEARCH_ENTRIES, "depth/search budgets produce bounded diagnostics");
        check(quote(Map.of("create:shaft", 8), normal).json().equals(quote(Map.of("create:shaft", 8), normal).json()), "same BOM/snapshot quotation is deterministic");
        try { quote(Map.of("create:shaft", -1), normal); throw new AssertionError("negative demand accepted"); } catch (IllegalArgumentException expected) { }
    }
    private static Snapshot snapshot(Map<String, List<Recipe>> recipes, Map<String, Double> units, Map<String, Integer> stock) {
        return new Snapshot(recipes, units, stock, Set.of(), List.of(), new JsonObject());
    }
    private static Ingredient ingredient(String id, int count) { return new Ingredient(List.of(id), count); }
    private static Recipe recipe(String id, String output, int count, Ingredient... inputs) { return new Recipe(id, output, count, List.of(inputs), "injected_effective_recipe", false); }
    private static void equal(double expected, double actual, String detail) { check(Math.abs(expected - actual) < 1e-9, detail + "; expected=" + expected + " actual=" + actual); }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
