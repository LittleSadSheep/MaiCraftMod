// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Relative material value and additional acquisition are separate estimates, never currency or a crafting permission. */
public final class KineticMaterialCosts {
    public static final int MAX_DEPTH = 12, MAX_SEARCH_ENTRIES = 8192, MAX_RECIPES_PER_ITEM = 8, MAX_ALTERNATIVES = 32;
    public static final double UNKNOWN_UNIT_VALUE = 8;
    public record Ingredient(List<String> alternatives, int count) {
        public Ingredient {
            alternatives = alternatives.stream().distinct().sorted().toList();
            if (alternatives.isEmpty() || alternatives.size() > MAX_ALTERNATIVES || count < 1 || count > 64) throw bad("ingredient bounds");
            alternatives.forEach(KineticMaterialCosts::identifier);
        }
    }
    public record Recipe(String id, String outputId, int outputCount, List<Ingredient> ingredients, String provenance, boolean conditional) {
        public Recipe {
            identifier(id); identifier(outputId); ingredients = List.copyOf(ingredients);
            if (outputCount < 1 || outputCount > 64 || ingredients.isEmpty() || ingredients.size() > 16) throw bad("recipe bounds");
            if (provenance == null || provenance.length() > 256) throw bad("recipe provenance");
        }
        public JsonObject json() {
            var result = new JsonObject(); result.addProperty("recipe_id", id); result.addProperty("output_id", outputId);
            result.addProperty("output_count", outputCount); result.addProperty("provenance", provenance); result.addProperty("conditions_unverified", conditional);
            var inputs = new JsonArray(); for (var ingredient : ingredients) {
                var row = new JsonObject(); var ids = new JsonArray(); ingredient.alternatives.forEach(ids::add);
                row.add("alternative_item_ids", ids); row.addProperty("count", ingredient.count); inputs.add(row);
            }
            result.add("ingredients", inputs); return result;
        }
    }
    public record Snapshot(Map<String, List<Recipe>> recipes, Map<String, Double> rawUnitValues,
                           Map<String, Integer> carried, Set<String> unknownOutputs, List<String> issues, JsonObject evidence) {
        public Snapshot {
            if (recipes.size() > 512 || rawUnitValues.size() > 4096 || carried.size() > 256 || unknownOutputs.size() > 512 || issues.size() > 64) throw bad("snapshot bounds");
            Map<String, List<Recipe>> copy = new LinkedHashMap<>();
            recipes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                identifier(entry.getKey()); var values = entry.getValue().stream().sorted(java.util.Comparator.comparing(Recipe::id)).toList();
                if (values.size() > MAX_RECIPES_PER_ITEM || values.stream().anyMatch(recipe -> !recipe.outputId.equals(entry.getKey()))) throw bad("recipe index");
                copy.put(entry.getKey(), values);
            });
            recipes = java.util.Collections.unmodifiableMap(copy); rawUnitValues = Map.copyOf(rawUnitValues); carried = Map.copyOf(carried);
            rawUnitValues.forEach((id, value) -> { identifier(id); if (value == null || !Double.isFinite(value) || value < 1e-9 || value > 1_000_000) throw bad("raw unit value"); });
            carried.forEach((id, count) -> { identifier(id); if (count == null || count < 0 || count > 1_000_000) throw bad("carried count"); });
            unknownOutputs = Set.copyOf(unknownOutputs); issues = List.copyOf(issues); evidence = evidence == null ? new JsonObject() : evidence.deepCopy();
        }
        @Override public JsonObject evidence() { return evidence.deepCopy(); }
    }
    public record Quote(double materialValueUnits, double acquisitionDeficitUnits, Map<String, Long> missingFinalItems,
                        Map<String, Long> acquisitionDeficits, List<String> issues, JsonObject evidence) {
        public Quote { missingFinalItems = Map.copyOf(missingFinalItems); acquisitionDeficits = Map.copyOf(acquisitionDeficits); issues = List.copyOf(issues); evidence = evidence.deepCopy(); }
        @Override public JsonObject evidence() { return evidence.deepCopy(); }
        public boolean estimated() { return true; }
        public boolean hasUnknowns() { return !issues.isEmpty(); }
        public JsonObject json() {
            JsonObject result = evidence(); result.addProperty("material_value_units", materialValueUnits);
            result.addProperty("acquisition_deficit_units", acquisitionDeficitUnits); result.addProperty("relative_resource_units", true);
            result.addProperty("estimated", true); result.addProperty("has_unknowns", hasUnknowns());
            result.addProperty("carried_materials_are_not_free", true); result.addProperty("acquisition_plan_verified", false);
            var missing = new JsonObject(); missingFinalItems.forEach(missing::addProperty); result.add("missing_final_items", missing);
            var deficits = new JsonObject(); acquisitionDeficits.forEach(deficits::addProperty); result.add("acquisition_deficits", deficits);
            var problems = new JsonArray(); issues.forEach(problems::add); result.add("issues", problems); return result;
        }
    }
    private KineticMaterialCosts() {}
    public static Quote quote(Map<String, Integer> billOfMaterials, Snapshot snapshot) {
        if (billOfMaterials == null || snapshot == null || billOfMaterials.size() > 64) throw bad("BOM bounds");
        billOfMaterials.forEach((id, count) -> { identifier(id); if (count == null || count < 1 || count > 1_000_000) throw bad("BOM quantity"); });
        return new KineticCostSearch(snapshot).quote(billOfMaterials);
    }
    static void identifier(String id) { if (id == null || id.length() > 256 || !id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw bad("item/recipe identifier"); }
    private static IllegalArgumentException bad(String reason) { return new IllegalArgumentException("kinetic_material_cost_" + reason); }
}
