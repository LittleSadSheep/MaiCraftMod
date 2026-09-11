// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence.*;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.*;

public final class ProductionNativeEvidenceTest {
    public static void main(String[] args) {
        JsonObject authored = ProductionNativeFixture.manifest();
        var manifest = ProductionManifest.parse(authored); var evidence = ProductionNativeFixture.prepared(authored,1);
        var result = ProductionDesignCompiler.compile(authored,evidence);
        check(result.valid() && result.ready(), "registry selectors resolve against real-shaped opaque recipe/stock identities: " + result.report());
        check(result.report().getAsJsonObject("resolved_manifest").getAsJsonArray("links").get(0).getAsJsonObject().get("resource").getAsString().equals(ProductionNativeFixture.IRON_KEY), "resolved manifest freezes full components");
        check(result.canEnter("supply") && result.canEnter("start") && result.canEnter("observe"), "one-item source buffer admits first batch of a three-batch window");
        check(!result.report().get("machine_production_verified").getAsBoolean(), "native snapshots are still not production evidence");

        Port input = port(manifest,"supply");
        evidence.observePort(input.id(),ProductionNativeFixture.observation(input.offset(),input,0));
        var empty = ProductionDesignCompiler.compile(authored,evidence);
        check(empty.valid() && empty.canEnter("supply") && !empty.canEnter("start"), "empty source can advance to supply, not start");
        evidence.observePort(input.id(),ProductionNativeFixture.observation(input.offset(),input,1));
        JsonObject stopped = ProductionNativeFixture.recipe(); stopped.getAsJsonObject("condition_checks").addProperty("create:nonzero_rotation",false); evidence.observeRecipe("press",stopped);
        var stoppedResult = ProductionDesignCompiler.compile(authored,evidence);
        check(stoppedResult.canEnter("supply") && stoppedResult.canEnter("start") && !stoppedResult.canEnter("observe"), "starting conditions do not deadlock preparation");
        evidence.observeRecipe("press",ProductionNativeFixture.recipe());
        JsonObject route = ProductionNativeFixture.connection(manifest.links().get(0)); route.addProperty("resource_compatibility","unknown"); evidence.observeLink("feed",route);
        var probe = ProductionDesignCompiler.compile(authored,evidence);
        check(probe.canEnter("observe") && !probe.ready(), "controlled observation may resolve resource feasibility; it is not marked verified");
        route.addProperty("verified_connection",false); evidence.observeLink("feed",route);
        check(!ProductionDesignCompiler.compile(authored,evidence).canEnter("supply"), "unknown native topology cannot enter supply via valid-only gating");

        var ambiguous = ProductionNativeFixture.prepared(authored,1); JsonObject named = ProductionNativeFixture.IRON.deepCopy();
        named.getAsJsonObject("components").addProperty("minecraft:custom_name","named ingot");
        JsonObject twoVariants = ProductionNativeFixture.observation(input.offset(),input,1);
        twoVariants.getAsJsonArray("resources").add(ProductionNativeFixture.stock(named,1,input.face(),"shared-grid")); ambiguous.observePort(input.id(),twoVariants);
        var choice = ProductionDesignCompiler.compile(authored,ambiguous);
        check(choice.valid() && !choice.canEnter("supply"), "same registry ID with multiple components is ambiguous");
        JsonObject exact = authored.deepCopy(); exact.getAsJsonArray("links").get(0).getAsJsonObject().addProperty("resource",ProductionNativeFixture.IRON_KEY);
        var exactEvidence = ProductionNativeFixture.prepared(exact,1); exactEvidence.observePort(input.id(),twoVariants);
        check(ProductionDesignCompiler.compile(exact,exactEvidence).ready(), "explicit native resource ID selects one component variant in a new authored plan");
        var pinned = ProductionNativeFixture.prepared(authored,1); ProductionDesignCompiler.compile(authored,pinned);
        JsonObject changed = ProductionNativeFixture.observation(input.offset(),input,0); changed.getAsJsonArray("resources").remove(0); changed.getAsJsonArray("resources").add(ProductionNativeFixture.stock(named,3,input.face(),"shared-grid")); pinned.observePort(input.id(),changed);
        var pinnedResult = ProductionDesignCompiler.compile(authored,pinned);
        check(!pinnedResult.canEnter("start") && pinnedResult.report().getAsJsonObject("resolved_manifest").getAsJsonArray("links").get(0).getAsJsonObject().get("resource").getAsString().equals(ProductionNativeFixture.IRON_KEY), "later components do not silently replace frozen identity");

        var supplied = ProductionNativeFixture.prepared(authored,1); ProductionDesignCompiler.compile(authored,supplied);
        Resource iron = new Resource("items","minecraft:iron_ingot");
        JsonObject initial = ProductionNativeFixture.observation(input.offset(),input,1); supplied.initialSupply("ae_supply",iron,1,initial);
        JsonObject receipt = JsonParser.parseString("{\"status\":\"applied\",\"requested\":1,\"transferred\":1,\"tick\":101}").getAsJsonObject(); receipt.addProperty("resource_id",ProductionNativeFixture.IRON_KEY);
        supplied.confirmedSourceSupply("ae_supply",iron,"receipt-1",receipt); supplied.confirmedSupply("feed","receipt-1",receipt);
        check(supplied.confirmedSupplies().get("receipt_count").getAsInt() == 1, "source and legacy link callbacks share one receipt ledger");
        long injected = supplied.confirmedSupplies().getAsJsonArray("sources").asList().stream().mapToLong(v -> v.getAsJsonObject().get("confirmed_injected").getAsLong()).sum();
        check(injected == 1, "duplicate raw result cannot create another injection");
        JsonObject excess = receipt.deepCopy(); excess.addProperty("requested",2); excess.addProperty("transferred",2);
        rejects(() -> supplied.confirmedSourceSupply("ae_supply",iron,"receipt-2",excess), "initial plus injections stay within total budget");

        JsonObject aliasInput = authored.deepCopy(); aliasInput.getAsJsonArray("nodes").get(1).getAsJsonObject().addProperty("recipe_id","minecraft:smelting/test_alias");
        var aliasEvidence = ProductionNativeFixture.prepared(aliasInput,1); JsonObject canonical = ProductionNativeFixture.recipe();
        canonical.addProperty("requested_recipe_id","minecraft:smelting/test_alias"); canonical.addProperty("recipe_id","mekanism:smelting/test_native"); aliasEvidence.observeRecipe("press",canonical);
        var aliasResult = ProductionDesignCompiler.compile(aliasInput,aliasEvidence);
        check(aliasResult.ready(), "requested recipe alias binds to the native processing identity");
        check(aliasResult.report().getAsJsonArray("recipe_bindings").get(0).getAsJsonObject().get("requested_recipe_id").getAsString().equals("minecraft:smelting/test_alias")
                && aliasResult.report().getAsJsonObject("resolved_manifest").getAsJsonArray("nodes").get(1).getAsJsonObject().get("recipe_id").getAsString().equals("mekanism:smelting/test_native"), "explicit recipe binding accompanies canonical manifest");

        JsonObject invalid = authored.deepCopy(); invalid.getAsJsonArray("links").get(0).getAsJsonObject().addProperty("from","input");
        check(!ProductionDesignCompiler.compile(invalid,id -> null).valid(), "raw direction errors are not hidden by absent resource bindings");
        invalid = authored.deepCopy(); JsonObject returning = invalid.getAsJsonArray("links").get(2).getAsJsonObject(); returning.addProperty("to","input"); returning.remove("path");
        check(!ProductionDesignCompiler.compile(invalid,id -> null).valid(), "raw material cycles remain invalid before identity discovery");
        var stale = ProductionNativeFixture.prepared(authored,1); stale.advance(1400);
        check(!ProductionDesignCompiler.compile(authored,stale).canEnter("supply"), "old native evidence expires");
        stale.bind("minecraft:the_nether",ProductionNativeFixture.ANCHOR); check(!ProductionDesignCompiler.compile(authored,stale).canEnter("supply"), "world rebinding discards resource and recipe selections");
        for (String key : new String[]{"player_slot","slot","click"}) {
            JsonObject unsafe = authored.deepCopy(); unsafe.getAsJsonArray("configurations").get(0).getAsJsonObject().getAsJsonObject("arguments").addProperty(key,0);
            check(!ProductionDesignCompiler.compile(unsafe,evidence).valid(), "configuration cannot smuggle " + key);
        }
        JsonObject operation = authored.deepCopy(); operation.getAsJsonArray("configurations").get(0).getAsJsonObject().addProperty("operation","inventory.transfer");
        check(!ProductionDesignCompiler.compile(operation,evidence).valid(), "configuration cannot smuggle another advertised mutation operation");
        JsonObject joules = ProductionNativeFixture.identity("mekanism:joules"); joules.addProperty("kind","energy");
        JsonObject power = new JsonObject(); JsonObject powerResource = new JsonObject(); powerResource.addProperty("medium","energy");
        powerResource.addProperty("id",ProductionNativeJson.identityKey(joules)); powerResource.add("identity",joules); power.add("resource",powerResource);
        power.addProperty("rate",2); power.addProperty("rate_unit","J/t"); power.addProperty("duration_ticks",10); power.addProperty("amount",2);
        JsonObject energyRecipe = ProductionNativeFixture.recipe(); energyRecipe.getAsJsonArray("minimum_power").remove(0); energyRecipe.getAsJsonArray("minimum_power").add(power);
        rejects(() -> ProductionNativeRecipes.decode(energyRecipe), "J/t cannot masquerade as total J");
        power.addProperty("amount",20);
        check(ProductionNativeRecipes.decode(energyRecipe).minimumPower().values().iterator().next() == 20, "native per-operation energy remains in joules");
        aliasesAndSharedStock();
        semanticPatternConfiguration();
        configurationReadbackAfterLongSupply();
        System.out.println("ProductionNativeEvidenceTest: passed");
    }
    private static void aliasesAndSharedStock() {
        JsonObject input = ProductionNativeFixture.manifest(); input.getAsJsonArray("links").get(0).getAsJsonObject().addProperty("resource",ProductionNativeFixture.IRON_KEY);
        input.getAsJsonArray("nodes").add(JsonParser.parseString("{\"id\":\"second_terminal\",\"kind\":\"source\",\"offset\":[-4,0,1]}"));
        input.getAsJsonArray("ports").add(JsonParser.parseString("{\"id\":\"second_supply\",\"node\":\"second_terminal\",\"offset\":[-4,0,1],\"face\":\"east\",\"medium\":\"items\",\"direction\":\"output\"}"));
        JsonObject second = input.getAsJsonArray("links").get(0).getAsJsonObject().deepCopy(); second.addProperty("id","second_feed"); second.addProperty("from","second_supply"); second.remove("path"); input.getAsJsonArray("links").add(second);
        ProductionManifest manifest = ProductionManifest.parse(input); var ledger = new ProductionSupplyEvidence(manifest);
        Node a = manifest.nodes().get(0), b = manifest.nodes().get(manifest.nodes().size()-1); Resource resource = new Resource("items",ProductionNativeFixture.IRON_KEY);
        JsonObject bank = new JsonObject(); var rows = new com.google.gson.JsonArray(); rows.add(ProductionNativeFixture.stock(ProductionNativeFixture.IRON,1,"east","one-grid"));
        rows.add(ProductionNativeFixture.stock(ProductionNativeFixture.IRON,1,"north","one-grid")); bank.add("resources",rows);
        Map<String,JsonObject> snapshots = Map.of(a.id(),bank,b.id(),bank);
        rejects(() -> ledger.initial(a,resource,2,snapshots), "two sided views are not two inventory copies");
        ledger.initial(a,resource,1,snapshots);
        rejects(() -> ledger.initial(b,resource,1,snapshots), "two terminals cannot credit the same grid stock twice");
        check(ledger.inspect(a,resource,3,snapshots,Map.of()).status() == Status.PLANNED, "shared stock cannot meet both source first-batch allocations");
        rows.forEach(v -> v.getAsJsonObject().addProperty("amount",2)); ledger.initial(b,resource,1,snapshots);
        check(ledger.inspect(a,resource,3,snapshots,Map.of()).status() == Status.VERIFIED, "two real items fund two initial one-item allocations");
    }
    private static void semanticPatternConfiguration() {
        JsonObject input = ProductionNativeFixture.manifest(); JsonObject args = input.getAsJsonArray("configurations").get(0).getAsJsonObject().getAsJsonObject("arguments");
        args.remove("item_id"); args.addProperty("action","ae2.pattern_install"); args.addProperty("recipe_id","minecraft:stick"); args.addProperty("mode","crafting");
        ProductionManifest manifest = ProductionManifest.parse(input); var evidence = ProductionNativeFixture.prepared(input,1);
        JsonObject result = ProductionNativeFixture.configured(); result.addProperty("action","ae2.pattern_install"); result.addProperty("recipe_id","minecraft:stick"); result.addProperty("mode","crafting");
        evidence.configured("finished_only",result);
        check(evidence.configuration(manifest.configurations().getFirst()).status() == Status.VERIFIED, "semantic pattern recipe/mode needs no model-authored slot");
        result.addProperty("recipe_id","minecraft:wrong_recipe"); evidence.configured("finished_only",result);
        check(evidence.configuration(manifest.configurations().getFirst()).status() != Status.VERIFIED, "a different pattern receipt cannot satisfy the requested recipe");
    }
    private static void configurationReadbackAfterLongSupply() {
        JsonObject input = ProductionNativeFixture.manifest(); var manifest = ProductionManifest.parse(input);
        var evidence = ProductionNativeFixture.prepared(input,1);
        check(ProductionDesignCompiler.compile(input,evidence).canEnter("start"), "initial configuration fixture is ready");
        for (Node node : manifest.nodes()) evidence.observeNode(node.id(),current(ProductionNativeFixture.observation(node.offset(),null,0)));
        for (Port port : manifest.ports()) evidence.observePort(port.id(),current(ProductionNativeFixture.observation(port.offset(),port,port.node().equals("ae_supply") ? 1 : 0)));
        evidence.observeRecipe("press",current(ProductionNativeFixture.recipe()));
        for (Link link : manifest.links()) evidence.observeLink(link.id(),current(ProductionNativeFixture.connection(link)));
        check(!ProductionDesignCompiler.compile(input,evidence).canEnter("start") && evidence.configurationActionConfirmed("finished_only"),
                "old action remains historical fact, but not current configuration proof");
        JsonObject read = current(ProductionNativeFixture.configured()); read.addProperty("tick",1402); read.addProperty("operation","machine.configuration");
        read.addProperty("configuration_operation","machine.configure"); read.addProperty("status","matched");
        evidence.observeConfiguration("finished_only",read);
        check(ProductionDesignCompiler.compile(input,evidence).canEnter("start"), "readback restores readiness without replaying writer or expiring freshly read links");
        read.addProperty("status","mismatch"); read.addProperty("verified_configuration",false); evidence.observeConfiguration("finished_only",read);
        check(!ProductionDesignCompiler.compile(input,evidence).canEnter("start") && evidence.configurationActionConfirmed("finished_only"), "changed configuration overrides old success without erasing history");
        check(ProductionNativeJson.sameScalar(new com.google.gson.JsonPrimitive(16),new com.google.gson.JsonPrimitive(16.0)), "numeric readback uses numeric equality");
        check(!ProductionNativeJson.sameScalar(new com.google.gson.JsonPrimitive(16),new com.google.gson.JsonPrimitive("16")), "numeric string is not a numeric value");
    }
    private static JsonObject current(JsonObject value) { value.addProperty("tick",1401); return value; }
    private static Port port(ProductionManifest manifest, String id) { return manifest.ports().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(); }
    private static void rejects(Runnable action, String message) { try { action.run(); throw new AssertionError(message); } catch (IllegalArgumentException expected) { } }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
