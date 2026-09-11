// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Resource;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;

/** Verifies the only permitted refinements of a reviewed manifest: native resource identities and recipe aliases. */
final class ProductionPlanBindings {
    private record Binding(Resource resource, JsonObject identity) {}
    private final Map<Resource, Binding> resources = new LinkedHashMap<>();

    ProductionPlanBindings(ProductionManifest authored, JsonObject report, ProductionPlanBindings previous) {
        Set<Resource> selectors = new LinkedHashSet<>();
        authored.links().forEach(link -> selectors.add(link.resource())); selectors.add(authored.target().resource());
        Map<Resource, JsonObject> rows = new LinkedHashMap<>();
        for (var raw : array(report, "resource_bindings")) {
            JsonObject row = raw.getAsJsonObject(); Resource selector = new Resource(text(row, "medium"), text(row, "resource"));
            if (rows.putIfAbsent(selector, row) != null) throw invalid("duplicate_resource_binding");
        }
        Set<Resource> used = new LinkedHashSet<>();
        for (Resource selector : selectors) {
            Resource rowKey = selector;
            if (!rows.containsKey(rowKey) && previous != null) rowKey = previous.resolve(selector);
            JsonObject row = rows.get(rowKey);
            if (row == null) throw invalid("missing_resource_binding");
            used.add(rowKey);
            Resource resolved = new Resource(selector.medium(), text(row, "resource_id"));
            JsonObject identity = row.has("identity") && row.get("identity").isJsonObject() ? row.getAsJsonObject("identity") : null;
            if (selector.medium().equals("kinetic") && selector.id().equals("rpm")) {
                if (!resolved.equals(selector) || identity != null) throw invalid("changed_rotation_unit");
            } else {
                if (identity == null || !selector.medium().equals(text(identity, "kind"))
                        || !identity.has("components") || !identity.get("components").isJsonObject()) throw invalid("missing_component_identity");
                String id = text(identity, "id");
                if (!selector.id().equals(id) && !selector.id().equals(resolved.id())) throw invalid("resource_selector_changed");
                if (!ResourceIdentity.key(identity).equals(resolved.id())) throw invalid("resource_identity_mismatch");
            }
            resources.put(selector, new Binding(resolved, identity == null ? null : identity.deepCopy()));
        }
        if (!used.equals(rows.keySet())) throw invalid("unexpected_resource_binding");
    }

    void verifyManifest(JsonObject authoredJson, ProductionManifest authored, JsonObject resolvedJson,
                        ProductionManifest resolved, JsonObject report, boolean previouslyBound) {
        if (authored.nodes().size() != resolved.nodes().size() || authored.links().size() != resolved.links().size())
            throw invalid("manifest_shape_changed");
        JsonObject shape = resolvedJson.deepCopy();
        Map<String, JsonObject> recipes = new LinkedHashMap<>();
        if (report.has("recipe_bindings")) for (var raw : array(report, "recipe_bindings")) {
            JsonObject row = raw.getAsJsonObject();
            if (recipes.putIfAbsent(text(row, "node"), row) != null) throw invalid("duplicate_recipe_binding");
        }
        Set<String> processIds = new LinkedHashSet<>();
        for (int i = 0; i < authored.nodes().size(); i++) {
            var original = authored.nodes().get(i); var bound = resolved.nodes().get(i);
            if (!original.kind().equals("process")) continue;
            processIds.add(original.id());
            JsonObject row = recipes.get(original.id());
            if (!Objects.equals(original.recipeId(), bound.recipeId())) {
                if (row == null && !previouslyBound) throw invalid("unverified_recipe_alias");
                if (row != null && (!text(row, "recipe_id").equals(bound.recipeId())
                        || !(text(row, "requested_recipe_id").equals(original.recipeId())
                        || previouslyBound && text(row, "requested_recipe_id").equals(bound.recipeId())))) throw invalid("unverified_recipe_alias");
            } else if (row != null && (!text(row, "recipe_id").equals(original.recipeId())
                    || !text(row, "requested_recipe_id").equals(original.recipeId()))) throw invalid("recipe_binding_mismatch");
            shape.getAsJsonArray("nodes").get(i).getAsJsonObject().addProperty("recipe_id", original.recipeId());
        }
        if (!processIds.containsAll(recipes.keySet())) throw invalid("unexpected_recipe_binding");
        for (int i = 0; i < authored.links().size(); i++) {
            var original = authored.links().get(i);
            if (!resolve(original.resource()).equals(resolved.links().get(i).resource())) throw invalid("link_binding_mismatch");
            shape.getAsJsonArray("links").get(i).getAsJsonObject().addProperty("resource", original.resource().id());
        }
        if (!resolve(authored.target().resource()).equals(resolved.target().resource())) throw invalid("target_binding_mismatch");
        shape.getAsJsonObject("target").addProperty("resource", authored.target().resource().id());
        if (!shape.equals(authoredJson)) throw invalid("manifest_shape_changed");
    }

    Resource resolve(Resource selector) { return lookup(selector).resource(); }
    JsonObject identity(Resource selector) { var identity = lookup(selector).identity(); return identity == null ? null : identity.deepCopy(); }
    String registryId(Resource selector) {
        Binding binding = lookup(selector);
        if (binding.identity() == null && binding.resource().equals(new Resource("kinetic", "rpm"))) return "rpm";
        return text(Objects.requireNonNull(binding.identity(), "Missing native identity"), "id");
    }
    boolean same(ProductionPlanBindings other) { return resources.equals(other.resources); }

    private Binding lookup(Resource selector) {
        Binding binding = resources.get(selector);
        if (binding != null) return binding;
        return resources.values().stream().filter(value -> value.resource().equals(selector)).findFirst()
                .orElseThrow(() -> invalid("resource_outside_bound_plan"));
    }

    private static JsonArray array(JsonObject value, String name) {
        if (!value.has(name) || !value.get(name).isJsonArray()) throw invalid("missing_" + name);
        return value.getAsJsonArray(name);
    }
    private static String text(JsonObject value, String name) {
        if (!value.has(name) || !value.get(name).isJsonPrimitive() || !value.getAsJsonPrimitive(name).isString()
                || value.get(name).getAsString().isBlank()) throw invalid("invalid_" + name);
        return value.get(name).getAsString();
    }
    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException("production_binding_" + code); }
}
