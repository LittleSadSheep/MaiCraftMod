// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.*;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence.*;

public final class ProductionDesignCompilerTest {
    public static void main(String[] args) {
        JsonObject manifest = ProductionFixture.manifest();
        var result = ProductionDesignCompiler.compile(manifest, new ProductionFixture.Verified());
        check(result.valid() && result.ready(), "complete input/path/recipe/configuration evidence permits operation");
        check(!result.report().get("machine_production_verified").getAsBoolean() && !result.report().get("resource_transfer_verified").getAsBoolean(), "compilation is not production proof");
        check(result.report().getAsJsonArray("transfers").get(0).getAsJsonObject().get("suggested_refill").getAsLong() == 1, "one-item refill suggestion distinct from total budget");
        JsonObject supply = result.report().getAsJsonArray("supplies").get(0).getAsJsonObject();
        check(supply.get("first_batch").getAsLong() == 1 && supply.get("amount").getAsLong() == 3 && supply.getAsJsonArray("pacing_consumers").toString().equals("[\"press\"]"), "three-item window starts with one unit and waits on the first processing consumer");
        check(result.report().getAsJsonObject("observation").getAsJsonArray("producer_nodes").toString().equals("[\"press\"]"), "only target producers qualify");
        JsonObject copy = result.report(); copy.addProperty("ready", false); check(result.report().get("ready").getAsBoolean(), "detached report");
        var pending = ProductionDesignCompiler.compile(manifest, new ProductionFixture.Verified() {
            public Check configuration(Configuration c) { return new Check(Status.PLANNED, "test_adapter", "filter not applied"); }
        });
        check(pending.valid() && !pending.ready(), "unapplied configuration cannot be ready");
        var unknown = ProductionDesignCompiler.compile(manifest, new ProductionFixture.Verified() { public Recipe recipe(String id) { return null; } });
        check(unknown.valid() && !unknown.ready(), "incomplete recipe remains unresolved");
        var incompatible = ProductionDesignCompiler.compile(manifest, new ProductionFixture.Verified() {
            public Check port(Port p) { return new Check(Status.UNSUPPORTED, "test_adapter", "wrong side"); }
        });
        check(!incompatible.valid(), "native incompatible port cannot be overridden by manifest");
        JsonObject bad = ProductionFixture.manifest(); link(bad, 0).addProperty("amount", 2); rejects(bad, "recipe_inputs_underfunded");
        bad = ProductionFixture.manifest(); bad.getAsJsonArray("links").remove(3); rejects(bad, "missing_or_insufficient_power");
        bad = ProductionFixture.manifest(); bad.getAsJsonArray("ports").get(1).getAsJsonObject().addProperty("face", "east"); rejects(bad, "invalid_production_manifest");
        bad = ProductionFixture.manifest(); link(bad, 0).getAsJsonArray("path").remove(2); rejects(bad, "invalid_production_manifest");
        bad = ProductionFixture.manifest(); link(bad, 1).addProperty("resource", ProductionFixture.IRON.id()); rejects(bad, "process_output_overallocated");
        bad = ProductionFixture.manifest(); link(bad, 2).addProperty("amount", 4); rejects(bad, "transport_changes_resource_or_quantity");
        bad = ProductionFixture.manifest(); bad.getAsJsonObject("observation").addProperty("minimum_output", 4); rejects(bad, "target_underfunded");
        bad = ProductionFixture.manifest(); bad.getAsJsonObject("observation").addProperty("minimum_events", 4); rejects(bad, "insufficient_planned_production_events");
        bad = ProductionFixture.manifest(); bad.getAsJsonArray("configurations").get(0).getAsJsonObject().getAsJsonObject("arguments").add("position", JsonParser.parseString("[99,99,99]")); rejects(bad, "invalid_production_manifest");
        bad = ProductionFixture.manifest(); bad.getAsJsonArray("links").add(JsonParser.parseString("{\"id\":\"bypass\",\"from\":\"supply\",\"to\":\"sink\",\"medium\":\"items\",\"resource\":\"sheet#fixture\",\"amount\":3}")); rejects(bad, "target_has_unprocessed_supply");
        var byproduct = ProductionDesignCompiler.compile(manifest, new ProductionFixture.Verified() {
            public Recipe recipe(String id) { Recipe r = super.recipe(id); return new Recipe(id, r.inputs(), List.of(new Output(ProductionFixture.SHEET, 1, 1), new Output(new Resource("items", "slag"), 1, .2)), r.minimumPower(), r.conditions(), true, r.provenance()); }
        });
        check(!byproduct.valid() && byproduct.report().toString().contains("missing_output_or_byproduct_capacity"), "unrouted byproduct cannot silently clog output");
        var overflow = ProductionDesignCompiler.compile(manifest, new ProductionFixture.Verified() {
            public Recipe recipe(String id) { Recipe r = super.recipe(id); return new Recipe(id, List.of(new Ingredient("ingot", Set.of(ProductionFixture.IRON), Long.MAX_VALUE, true)), r.outputs(), r.minimumPower(), r.conditions(), true, r.provenance()); }
        });
        check(!overflow.valid(), "quantity multiplication cannot wrap");
        Resource copper = new Resource("items", "copper");
        List<Ingredient> overlapping = List.of(new Ingredient("flexible", Set.of(copper, ProductionFixture.IRON), 1, true), new Ingredient("iron_only", Set.of(ProductionFixture.IRON), 1, true));
        check(!ProductionRecipeBalance.covers(overlapping, Map.of(ProductionFixture.IRON, 1L), 1), "one supplied item cannot satisfy two slots");
        check(ProductionRecipeBalance.covers(overlapping, Map.of(ProductionFixture.IRON, 1L, copper, 1L), 1), "alternative allocation preserves scarce exact input");
        check(ProductionRecipeBalance.covers(List.of(new Ingredient("tool", Set.of(copper), 1, false)), Map.of(copper, 1L), 100), "catalysts not multiplied by batches");
        JsonObject energy = ProductionFixture.manifest();
        for (int i : new int[]{6,7}) energy.getAsJsonArray("ports").get(i).getAsJsonObject().addProperty("medium","energy");
        link(energy,3).addProperty("medium","energy"); link(energy,3).addProperty("resource","fe"); link(energy,3).addProperty("amount",1000);
        var units = new ProductionFixture.Verified() {
            public Recipe recipe(String id) { Recipe r = super.recipe(id); return new Recipe(id,r.inputs(),r.outputs(),Map.of(new Resource("energy","joules"),20L),r.conditions(),true,r.provenance()); }
        };
        check(!ProductionDesignCompiler.compile(energy,units).valid(), "FE and joules cannot be mixed merely because both are energy");
        link(energy,3).addProperty("resource","joules"); link(energy,3).addProperty("amount",2);
        check(!ProductionDesignCompiler.compile(energy,units).valid(), "2 J/t is not a 60 J three-operation budget");
        link(energy,3).addProperty("amount",60); check(ProductionDesignCompiler.compile(energy,units).ready(), "per-operation joules multiply by batch count");
        JsonObject buffered = ProductionFixture.manifest();
        buffered.getAsJsonArray("nodes").add(JsonParser.parseString("{\"id\":\"junction\",\"kind\":\"transport\",\"offset\":[-2,0,0]}"));
        buffered.getAsJsonArray("ports").add(JsonParser.parseString("{\"id\":\"junction_in\",\"node\":\"junction\",\"offset\":[-2,0,0],\"face\":\"west\",\"medium\":\"items\",\"direction\":\"input\"}"));
        buffered.getAsJsonArray("ports").add(JsonParser.parseString("{\"id\":\"junction_out\",\"node\":\"junction\",\"offset\":[-2,0,0],\"face\":\"east\",\"medium\":\"items\",\"direction\":\"output\"}"));
        link(buffered,0).addProperty("to","junction_in"); link(buffered,0).add("path",JsonParser.parseString("[[-4,0,0],[-3,0,0],[-2,0,0]]"));
        buffered.getAsJsonArray("links").add(JsonParser.parseString("{\"id\":\"after_junction\",\"from\":\"junction_out\",\"to\":\"input\",\"medium\":\"items\",\"resource\":\"iron#fixture\",\"amount\":3,\"path\":[[-2,0,0],[-1,0,0],[0,0,0]]}"));
        var throughBuffer = ProductionDesignCompiler.compile(buffered,new ProductionFixture.Verified());
        check(throughBuffer.ready() && throughBuffer.report().getAsJsonArray("supplies").get(0).getAsJsonObject().getAsJsonArray("pacing_consumers").toString().equals("[\"press\"]"), "pacing crosses transport buffers but stops at the first real process");
        System.out.println("ProductionDesignCompilerTest: passed");
    }
    private static JsonObject link(JsonObject manifest, int index) { return manifest.getAsJsonArray("links").get(index).getAsJsonObject(); }
    private static void rejects(JsonObject manifest, String code) { var r = ProductionDesignCompiler.compile(manifest, new ProductionFixture.Verified()); check(!r.valid() && r.report().getAsJsonArray("errors").toString().contains(code), "expected " + code + ": " + r.report()); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
