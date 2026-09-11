// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Resource;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;

public final class ProductionRunPlanBindingTest {
    private static final String AUTHORED_RECIPE = "minecraft:iron_ingot_from_smelting_raw_iron";
    private static final String NATIVE_RECIPE = "mekanism:smelting/iron_ingot_from_smelting_raw_iron";

    public static void main(String[] args) {
        freezesNativeIdentity(); rejectsShapeChanges(); rejectsIncompleteOrInconsistentEvidence(); repeatedBinding();
        System.out.println("ProductionRunPlanBindingTest passed");
    }

    private static void freezesNativeIdentity() {
        JsonObject authored = manifest(), untouched = authored.deepCopy();
        BlockPos.MutableBlockPos anchor = new BlockPos.MutableBlockPos(10, 64, 20);
        ProductionRunPlan plan = new ProductionRunPlan(anchor, "minecraft:overworld", authored);
        Resource selected = new Resource("items", "minecraft:iron_ingot");
        check(!plan.bound() && plan.resolvedResource(selected).equals(selected), "Unbound authored selector must remain visible");
        rejects(() -> plan.registryId(selected));
        JsonObject report = report(authored, "plain"); plan.bindResolved(report);
        check(plan.bound() && plan.node("process").recipeId().equals(NATIVE_RECIPE), "Recipe alias must bind to canonical native ID");
        Resource resolved = plan.resolvedResource(selected);
        check(!resolved.id().equals(selected.id()) && plan.manifest().target().resource().equals(resolved), "Runtime target must use component-sensitive identity");
        check(plan.registryId(resolved).equals("minecraft:iron_ingot"), "Registry ID comes from the identity object");
        plan.resourceIdentity(selected).getAsJsonObject("components").addProperty("test:edited", true);
        check(!plan.resourceIdentity(resolved).getAsJsonObject("components").has("test:edited"), "Identity query must return a copy");
        plan.json().getAsJsonObject("target").addProperty("resource", "different");
        plan.authoredJson().getAsJsonArray("nodes").remove(0);
        authored.getAsJsonArray("nodes").remove(0); anchor.set(99, 99, 99);
        report.getAsJsonObject("resolved_manifest").getAsJsonArray("links").remove(0);
        check(plan.authoredJson().equals(untouched), "Original JSON must not drift after binding or caller mutation");
        check(plan.anchor().equals(new BlockPos(10, 64, 20)) && plan.dimension().equals("minecraft:overworld"), "Anchor and dimension remain frozen");
        check(plan.manifest().links().size() == 2 && plan.json().getAsJsonObject("target").get("resource").getAsString().equals(resolved.id()), "Bound JSON must remain detached");
    }

    private static void rejectsShapeChanges() {
        List<Consumer<JsonObject>> edits = List.of(
                value -> value.getAsJsonArray("nodes").get(1).getAsJsonObject().addProperty("batches", 4),
                value -> value.getAsJsonArray("nodes").get(0).getAsJsonObject().addProperty("material_policy", "ordinary"),
                value -> value.getAsJsonArray("nodes").get(2).getAsJsonObject().addProperty("id", "renamed-sink"),
                value -> value.getAsJsonArray("nodes").get(1).getAsJsonObject().getAsJsonArray("offset").set(0, new com.google.gson.JsonPrimitive(3)),
                value -> value.getAsJsonArray("ports").get(0).getAsJsonObject().addProperty("face", "up"),
                value -> value.getAsJsonArray("ports").get(0).getAsJsonObject().addProperty("direction", "input"),
                value -> value.getAsJsonArray("links").get(0).getAsJsonObject().addProperty("amount", 64),
                value -> value.getAsJsonArray("links").get(0).getAsJsonObject().getAsJsonArray("path").remove(1),
                value -> value.getAsJsonArray("links").remove(1),
                value -> value.getAsJsonArray("configurations").get(0).getAsJsonObject().getAsJsonObject("arguments").addProperty("enabled", true),
                value -> value.getAsJsonObject("observation").addProperty("minimum_output", 1));
        for (Consumer<JsonObject> edit : edits) {
            JsonObject authored = manifest(), compiled = report(authored, "plain");
            var plan = new ProductionRunPlan(BlockPos.ZERO, "minecraft:overworld", authored);
            edit.accept(compiled.getAsJsonObject("resolved_manifest")); rejects(() -> plan.bindResolved(compiled));
            check(!plan.bound() && plan.json().equals(authored), "Rejected binding must not partly mutate the plan");
        }
    }

    private static void rejectsIncompleteOrInconsistentEvidence() {
        JsonObject authored = manifest();
        JsonObject missing = report(authored, "plain"); missing.remove("recipe_bindings");
        rejects(() -> new ProductionRunPlan(BlockPos.ZERO, "minecraft:overworld", authored).bindResolved(missing));
        JsonObject components = report(authored, "plain");
        components.getAsJsonArray("resource_bindings").get(0).getAsJsonObject().getAsJsonObject("identity")
                .getAsJsonObject("components").addProperty("test:changed", true);
        rejects(() -> new ProductionRunPlan(BlockPos.ZERO, "minecraft:overworld", authored).bindResolved(components));
        JsonObject resources = report(authored, "plain"); resources.getAsJsonArray("resource_bindings").remove(0);
        rejects(() -> new ProductionRunPlan(BlockPos.ZERO, "minecraft:overworld", authored).bindResolved(resources));
        JsonObject unknown = report(authored, "plain"); unknown.getAsJsonArray("requirements").get(0).getAsJsonObject().addProperty("status", "unknown");
        rejects(() -> new ProductionRunPlan(BlockPos.ZERO, "minecraft:overworld", authored).bindResolved(unknown));
    }

    private static void repeatedBinding() {
        JsonObject authored = manifest(), first = report(authored, "plain");
        var plan = new ProductionRunPlan(BlockPos.ZERO, "minecraft:overworld", authored); plan.bindResolved(first);
        JsonObject repeated = first.deepCopy(); repeated.addProperty("tick", 999); repeated.remove("recipe_bindings");
        for (var raw : repeated.getAsJsonArray("resource_bindings")) {
            JsonObject row = raw.getAsJsonObject(); row.add("resource", row.get("resource_id"));
        }
        plan.bindResolved(repeated);
        JsonObject before = plan.json(); rejects(() -> plan.bindResolved(report(authored, "named_variant")));
        check(plan.json().equals(before) && plan.authoredJson().equals(authored), "A second component variant cannot silently replace a frozen run");
        rejects(() -> plan.registryId(new Resource("items", "minecraft:diamond")));
    }

    static JsonObject report(JsonObject authored, String variant) {
        JsonObject result = new JsonObject(); result.addProperty("valid", true);
        result.add("requirements", JsonParser.parseString("[{\"category\":\"resources\",\"status\":\"verified\",\"gate\":\"before_supply\"}]"));
        JsonObject resolved = authored.deepCopy(); JsonArray rows = new JsonArray();
        var bindings = new LinkedHashMap<String, String>();
        for (var raw : authored.getAsJsonArray("links")) {
            JsonObject link = raw.getAsJsonObject(); String selector = link.get("resource").getAsString();
            if (bindings.containsKey(selector)) continue;
            JsonObject identity = ResourceIdentity.base("items", selector);
            if (!variant.equals("plain")) identity.getAsJsonObject("components").addProperty("test:variant", variant);
            String exact = ResourceIdentity.key(identity); bindings.put(selector, exact);
            JsonObject row = new JsonObject(); row.addProperty("medium", "items"); row.addProperty("resource", selector);
            row.addProperty("resource_id", exact); row.add("identity", identity); rows.add(row);
        }
        for (var raw : resolved.getAsJsonArray("links")) { JsonObject link = raw.getAsJsonObject(); link.addProperty("resource", bindings.get(link.get("resource").getAsString())); }
        resolved.getAsJsonObject("target").addProperty("resource", bindings.get(resolved.getAsJsonObject("target").get("resource").getAsString()));
        resolved.getAsJsonArray("nodes").get(1).getAsJsonObject().addProperty("recipe_id", NATIVE_RECIPE);
        result.add("resolved_manifest", resolved); result.add("resource_bindings", rows);
        JsonArray supplies = new JsonArray(); JsonObject supply = new JsonObject();
        supply.addProperty("node", "source"); supply.addProperty("medium", "items");
        supply.addProperty("resource", bindings.get("minecraft:raw_iron")); supply.addProperty("amount", 3);
        supply.addProperty("first_batch", 1); supply.add("pacing_consumers", JsonParser.parseString("[\"process\"]"));
        supplies.add(supply); result.add("supplies", supplies);
        JsonArray recipes = new JsonArray(); JsonObject recipe = new JsonObject(); recipe.addProperty("node", "process");
        recipe.addProperty("requested_recipe_id", AUTHORED_RECIPE); recipe.addProperty("recipe_id", NATIVE_RECIPE);
        recipes.add(recipe); result.add("recipe_bindings", recipes); return result;
    }

    static JsonObject manifest() {
        return JsonParser.parseString("""
                {"schema_version":1,"nodes":[
                  {"id":"source","kind":"source","offset":[0,0,0],"material_policy":"inventory_only"},
                  {"id":"process","kind":"process","offset":[2,0,0],"recipe_id":"minecraft:iron_ingot_from_smelting_raw_iron","batches":3},
                  {"id":"sink","kind":"sink","offset":[4,0,0]}],
                 "ports":[
                  {"id":"s","node":"source","offset":[0,0,0],"face":"east","medium":"items","direction":"output"},
                  {"id":"in","node":"process","offset":[2,0,0],"face":"west","medium":"items","direction":"input"},
                  {"id":"out","node":"process","offset":[2,0,0],"face":"east","medium":"items","direction":"output"},
                  {"id":"t","node":"sink","offset":[4,0,0],"face":"west","medium":"items","direction":"input"}],
                 "links":[
                  {"id":"supply","from":"s","to":"in","medium":"items","resource":"minecraft:raw_iron","amount":3,"path":[[0,0,0],[1,0,0],[2,0,0]]},
                  {"id":"delivery","from":"out","to":"t","medium":"items","resource":"minecraft:iron_ingot","amount":3,"path":[[2,0,0],[3,0,0],[4,0,0]]}],
                 "configurations":[{"id":"start","node":"process","operation":"machine.configure","stage":"start","arguments":{"action":"mekanism.redstone","mode":"high"}}],
                 "target":{"node":"sink","medium":"items","resource":"minecraft:iron_ingot"},
                 "observation":{"window_ticks":20,"minimum_output":3,"minimum_events":3,"max_idle_ticks":20}}
                """).getAsJsonObject();
    }

    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; } throw new AssertionError("Invalid binding was accepted"); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
