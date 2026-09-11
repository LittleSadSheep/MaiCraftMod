// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import static org.maiwithu.maicraft.core.integration.machine.production.ProductionNativeJson.*;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence.*;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Resource;

/** Decodes machine.recipe v1, retaining opaque component-sensitive resource IDs and recipe completeness. */
final class ProductionNativeRecipes {
    private ProductionNativeRecipes() {}
    static Recipe decode(JsonObject result) {
        if (!"maicraft.machine_recipe.v1".equals(text(result,"schema"))) throw new IllegalArgumentException("Unknown native recipe schema");
        String id = text(result,"recipe_id"), source = text(result,"provenance");
        if (id == null || source == null || source.isBlank()) throw new IllegalArgumentException("Recipe identity/provenance missing");
        var inputs = new ArrayList<Ingredient>(); var outputs = new ArrayList<Output>();
        var power = new LinkedHashMap<Resource,Long>(); var conditions = new LinkedHashSet<String>();
        for (var value : array(result,"inputs")) {
            JsonObject input = value.getAsJsonObject(); var alternatives = new LinkedHashSet<Resource>();
            for (var alternative : array(input,"alternatives")) alternatives.add(resource(alternative.getAsJsonObject()));
            Long amount = number(input,"amount"); Boolean consumed = bool(input,"consumed");
            if (amount == null || consumed == null) throw new IllegalArgumentException("Recipe input quantity/consumption missing");
            inputs.add(new Ingredient(text(input,"id"), alternatives, amount, consumed));
        }
        for (var value : array(result,"outputs")) {
            JsonObject output = value.getAsJsonObject(); Long amount = number(output,"amount"); Double chance = decimal(output,"chance");
            if (amount == null || chance == null) throw new IllegalArgumentException("Recipe output quantity/chance missing");
            outputs.add(new Output(resource(object(output,"resource")), amount, chance));
        }
        for (var value : array(result,"minimum_power")) {
            JsonObject required = value.getAsJsonObject(); Long amount = number(required,"amount");
            if (amount == null || amount <= 0) throw new IllegalArgumentException("Invalid native power requirement");
            Resource resource = resource(object(required,"resource"));
            if (resource.medium().equals("energy") && (required.has("rate") || required.has("duration_ticks"))) {
                Long duration = number(required,"duration_ticks"); Double rate = decimal(required,"rate");
                if (duration == null || duration <= 0 || rate == null || rate < 0 || text(required,"rate_unit") == null
                        || required.get("rate").getAsBigDecimal().multiply(java.math.BigDecimal.valueOf(duration)).compareTo(java.math.BigDecimal.valueOf(amount)) != 0)
                    throw new IllegalArgumentException("Native energy amount must be per-operation rate times duration, in the same resource unit");
            }
            if (power.put(resource, amount) != null) throw new IllegalArgumentException("Duplicate native power requirement");
        }
        for (var condition : array(result,"conditions")) {
            if (!condition.isJsonPrimitive() || !condition.getAsJsonPrimitive().isString() || condition.getAsString().isBlank()) throw new IllegalArgumentException("Invalid recipe condition");
            conditions.add(condition.getAsString());
        }
        return new Recipe(id, inputs, outputs, power, conditions, Boolean.TRUE.equals(bool(result,"complete"))
                && !Boolean.TRUE.equals(bool(result,"truncated")) && array(result,"unknown").isEmpty(), source);
    }
    private static Resource resource(JsonObject row) {
        String medium = text(row,"medium"), id = text(row,"id");
        if (!ProductionManifest.MEDIA.contains(medium == null ? "" : medium) || id == null || id.isBlank()) throw new IllegalArgumentException("Native resource identity missing");
        JsonObject identity = object(row,"identity");
        if (identity != null && !id.equals(identityKey(identity))) throw new IllegalArgumentException("Native resource ID/components disagree");
        return new Resource(medium, id);
    }
}
