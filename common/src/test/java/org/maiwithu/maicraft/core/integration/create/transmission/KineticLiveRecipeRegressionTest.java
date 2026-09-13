// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.maiwithu.maicraft.core.integration.create.transmission.KineticMaterialCosts.*;

/** Replays the live 63-shaft/3-gearbox failure with actual shipped recipe JSON, including tool-recycling fanout and tags. */
public final class KineticLiveRecipeRegressionTest {
    public static void main(String[] args) {
        JsonObject fixture;
        try (var stream = KineticLiveRecipeRegressionTest.class.getResourceAsStream("live-kinetic-recipes.json")) {
            if (stream == null) throw new AssertionError("missing live recipe regression fixture");
            fixture = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException failed) { throw new AssertionError(failed); }
        check(fixture.get("observed_native_recipe_count").getAsInt() == 4977 && fixture.get("observed_live_search_entries").getAsInt() == 8192,
                "fixture must retain the actual client's exhausted-budget provenance");
        Map<String, List<Recipe>> recipes = new LinkedHashMap<>();
        for (JsonElement element : fixture.getAsJsonArray("recipes")) {
            var row = element.getAsJsonObject(); var recipe = parse(row, fixture.getAsJsonObject("tag_items"));
            recipes.computeIfAbsent(recipe.outputId(), ignored -> new ArrayList<>()).add(recipe);
        }
        Map<String, Double> raw = new LinkedHashMap<>(); fixture.getAsJsonObject("raw_unit_values").entrySet().forEach(entry -> raw.put(entry.getKey(), entry.getValue().getAsDouble()));
        var snapshot = new Snapshot(recipes, raw, counts(fixture.getAsJsonObject("carried")), Set.of(), List.of(), new JsonObject());
        var quote = KineticMaterialCosts.quote(counts(fixture.getAsJsonObject("bom")), snapshot);
        equal(209.0 / 9, quote.materialValueUnits(), "regular installed recipes must not inflate this stocked BOM to its previous 528-unit unknown quote");
        equal(0, quote.acquisitionDeficitUnits(), "all final construction parts were already carried in the actual failing scenario");
        check(quote.issues().stream().noneMatch(issue -> issue.contains("recipe_search_budget") || issue.contains("recipe_cycle_or_no_acyclic_path")),
                "tool smelting, gearbox conversion and alloy block cycles cannot erase proven short recipes: " + quote.issues());
        check(quote.evidence().get("recipe_search_entries").getAsInt() < 1024, "shared dependency proofs avoid thousands of repeated tag/recycling visits");
        for (var item : quote.evidence().getAsJsonArray("items")) {
            var value = item.getAsJsonObject();
            equal(value.get("item_id").getAsString().equals("create:shaft") ? 5.0 / 27 : 104.0 / 27,
                    value.get("unit_material_value").getAsDouble(), "each ordinary part has its effective recipe-based unit price");
        }
        check(!quote.evidence().getAsJsonArray("effective_recipes_used").isEmpty(), "successful costs retain actual selected recipe identifiers and batch quantities");
    }
    private static Recipe parse(JsonObject row, JsonObject tags) {
        var json = row.getAsJsonObject("recipe"); String type = json.get("type").getAsString();
        JsonObject output = json.has("result") ? json.getAsJsonObject("result") : json.getAsJsonArray("results").get(0).getAsJsonObject();
        List<Ingredient> inputs = new ArrayList<>();
        if (json.has("ingredients")) json.getAsJsonArray("ingredients").forEach(value -> inputs.add(new Ingredient(alternatives(value, tags), 1)));
        else if (json.has("ingredient")) inputs.add(new Ingredient(alternatives(json.get("ingredient"), tags), 1));
        else for (var pattern : json.getAsJsonArray("pattern")) for (char key : pattern.getAsString().toCharArray())
            if (key != ' ') inputs.add(new Ingredient(alternatives(json.getAsJsonObject("key").get(String.valueOf(key)), tags), 1));
        boolean conditional = !Set.of("minecraft:crafting_shaped", "minecraft:crafting_shapeless", "minecraft:stonecutting", "create:item_application").contains(type);
        return new Recipe(row.get("recipe_id").getAsString(), output.get("id").getAsString(), output.has("count") ? output.get("count").getAsInt() : 1,
                inputs, "shipped_effective_recipe_json:" + type, conditional);
    }
    private static List<String> alternatives(JsonElement value, JsonObject tags) {
        List<String> ids = new ArrayList<>();
        if (value.isJsonArray()) value.getAsJsonArray().forEach(row -> ids.addAll(alternatives(row, tags)));
        else if (value.getAsJsonObject().has("item")) ids.add(value.getAsJsonObject().get("item").getAsString());
        else tags.getAsJsonArray(value.getAsJsonObject().get("tag").getAsString()).forEach(row -> ids.add(row.getAsString()));
        return ids;
    }
    private static Map<String, Integer> counts(JsonObject value) { Map<String, Integer> result = new LinkedHashMap<>(); value.entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue().getAsInt())); return result; }
    private static void equal(double expected, double actual, String detail) { check(Math.abs(expected - actual) < 1e-9, detail + "; actual=" + actual); }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
