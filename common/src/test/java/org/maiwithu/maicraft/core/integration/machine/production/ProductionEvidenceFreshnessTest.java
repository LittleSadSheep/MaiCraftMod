// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.*;
import static org.maiwithu.maicraft.core.integration.machine.production.ProductionNativeEvidence.*;

/** Exercise real accumulators and final proof, retaining native row clocks and finite-run semantics. */
public final class ProductionEvidenceFreshnessTest {
    public static void main(String[] args) {
        rowClocksAndInvalidation();
        finalProofRejectsStaleTopology();
        finiteIdleAndWrongFacts();
        supplyHistorySurvivesFreshnessInspection();
        System.out.println("ProductionEvidenceFreshnessTest: native clocks, bounded diagnostics and finite final proof passed");
    }

    private static void rowClocksAndInvalidation() {
        var manifest = ProductionManifest.parse(ProductionNativeFixture.manifest());
        var evidence = new ProductionNativeEvidence(manifest); evidence.bind(ProductionNativeFixture.DIMENSION,ProductionNativeFixture.ANCHOR);
        check(evidence.freshness(0).stream().allMatch(value -> value.status() == FreshnessStatus.MISSING), "unread facts must be missing");
        Node node = manifest.nodes().getFirst();
        JsonObject envelope = current(ProductionNativeFixture.base(),1400); envelope.addProperty("schema","maicraft.machine_snapshot.v1");
        JsonObject row = ProductionNativeFixture.observation(node.offset(),null,0); row.remove("dimension");
        JsonArray observations = new JsonArray(); observations.add(row); envelope.add("observations",observations);
        evidence.observeNode(node.id(),envelope);
        var old = fact(evidence,ObservationKind.NODE,node.id(),1400);
        check(old.tick() == 100 && old.ageTicks() == 1300 && old.status() == FreshnessStatus.EXPIRED,
                "a new envelope tick cannot renew the older native position row");
        JsonObject wrong = current(ProductionNativeFixture.observation(node.offset(),null,0),1400);
        wrong.add("position",ProductionNativeFixture.absolute(new Point(900,0,0))); evidence.observeNode(node.id(),wrong);
        check(fact(evidence,ObservationKind.NODE,node.id(),1400).status() == FreshnessStatus.INVALID,
                "a rejected anchored observation is invalid, not a refreshable missing/expired fact");
        evidence.observeNode(node.id(),current(ProductionNativeFixture.observation(node.offset(),null,0),1500));
        Link link = manifest.links().getFirst(); evidence.observeLink(link.id(),current(ProductionNativeFixture.connection(link),1500));
        evidence.configured("finished_only",current(ProductionNativeFixture.configured(),1500));
        var invalidated = fact(evidence,ObservationKind.LINK,link.id(),1500);
        check(invalidated.status() == FreshnessStatus.INVALIDATED && invalidated.tick() == 1500,
                "same-tick configuration invalidates an old path while preserving its original timestamp");
        evidence.configured("finished_only",current(ProductionNativeFixture.configured(),1501));
        check(fact(evidence,ObservationKind.NODE,node.id(),1501).status() == FreshnessStatus.INVALIDATED,
                "positive native changes invalidate earlier reads even within the age limit");
        evidence.bind("minecraft:the_nether",ProductionNativeFixture.ANCHOR);
        check(evidence.freshness(0).stream().allMatch(value -> value.status() == FreshnessStatus.MISSING), "a different world clears all old observation metadata");
    }

    private static void finalProofRejectsStaleTopology() {
        var authored = ProductionNativeFixture.manifest(); var manifest = ProductionManifest.parse(authored);
        var evidence = ProductionNativeFixture.prepared(authored,1); Link old = manifest.links().getFirst();
        currentFacts(evidence,manifest,1401,true);
        evidence.observeLink(old.id(),ProductionNativeFixture.connection(old));
        check(ProductionDesignCompiler.compile(authored,evidence).valid()
                        && ProductionNativeFixture.connection(old).get("verified_connection").getAsBoolean(),
                "old final guard would accept a valid compilation and cached verified path boolean");
        check(evidence.finalVerification().status() != ProductionEvidence.Status.VERIFIED
                        && evidence.finalVerification().detail().contains(old.id()),
                "a previously verified but now stale native path cannot pass final verification");
        evidence.observeLink(old.id(),current(ProductionNativeFixture.connection(old),1401));
        check(evidence.finalVerification().status() == ProductionEvidence.Status.VERIFIED, "refreshing only the stale path restores final proof");
    }

    private static void finiteIdleAndWrongFacts() {
        var authored = ProductionNativeFixture.manifest(); var manifest = ProductionManifest.parse(authored);
        var evidence = ProductionNativeFixture.prepared(authored,0); currentFacts(evidence,manifest,500,false);
        check(!ProductionDesignCompiler.compile(authored,evidence).canEnter("observe")
                        && evidence.finalVerification().status() == ProductionEvidence.Status.VERIFIED,
                "a completed finite run can remain valid after source stock, fuel and rotation become unavailable");
        Link link = manifest.links().getFirst(); JsonObject wrong = current(ProductionNativeFixture.connection(link),501);
        wrong.addProperty("dimension","minecraft:the_nether"); evidence.observeLink(link.id(),wrong);
        check(fact(evidence,ObservationKind.LINK,link.id(),501).status() == FreshnessStatus.INVALID
                        && evidence.finalVerification().status() != ProductionEvidence.Status.VERIFIED,
                "a wrong-world refresh cannot inherit a previous positive path");
        wrong = current(ProductionNativeFixture.connection(link),501);
        wrong.getAsJsonArray("edges").get(0).getAsJsonObject().add("from",ProductionNativeFixture.absolute(new Point(900,0,0)));
        evidence.observeLink(link.id(),wrong);
        check(evidence.finalVerification().status() != ProductionEvidence.Status.VERIFIED,
                "fresh timestamps never turn an incorrectly anchored connection into final proof");
        evidence.observeLink(link.id(),current(ProductionNativeFixture.connection(link),501));
        var wrongRecipe = current(ProductionNativeFixture.recipe(),501); wrongRecipe.addProperty("compatible",false);
        evidence.observeRecipe("press",wrongRecipe);
        check(evidence.finalVerification().status() != ProductionEvidence.Status.VERIFIED, "idle allowance does not admit a replaced incompatible process");
    }

    private static void supplyHistorySurvivesFreshnessInspection() {
        var authored = ProductionNativeFixture.manifest(); var manifest = ProductionManifest.parse(authored);
        var evidence = ProductionNativeFixture.prepared(authored,1);
        Node source = manifest.nodes().stream().filter(node -> node.id().equals("ae_supply")).findFirst().orElseThrow();
        Port port = manifest.ports().stream().filter(value -> value.node().equals(source.id())).findFirst().orElseThrow();
        Resource item = new Resource("items","minecraft:iron_ingot");
        evidence.initialSupply(source.id(),item,1,ProductionNativeFixture.observation(port.offset(),port,1));
        JsonObject receipt = current(ProductionNativeFixture.base(),200); receipt.addProperty("status","applied");
        receipt.addProperty("requested",1); receipt.addProperty("transferred",1); receipt.addProperty("resource_id",ProductionNativeFixture.IRON_KEY);
        evidence.confirmedSourceSupply(source.id(),item,"existing-native-deposit",receipt);
        JsonObject before = evidence.confirmedSupplies(); evidence.freshness(1401); evidence.freshness(1402);
        check(before.equals(evidence.confirmedSupplies()) && before.get("receipt_count").getAsInt() == 1,
                "freshness inspection does not clear, recredit or duplicate legitimate existing stock and actual deposits");
    }

    private static void currentFacts(ProductionNativeEvidence evidence, ProductionManifest manifest, long tick, boolean running) {
        for (Node node : manifest.nodes()) {
            JsonObject row = current(ProductionNativeFixture.observation(node.offset(),null,0),tick);
            row.getAsJsonObject("native").getAsJsonObject("create").addProperty("getSpeed",running ? 16 : 0);
            evidence.observeNode(node.id(),row);
        }
        for (Port port : manifest.ports()) evidence.observePort(port.id(),current(ProductionNativeFixture.observation(port.offset(),port,0),tick));
        JsonObject recipe = current(ProductionNativeFixture.recipe(),tick);
        recipe.getAsJsonObject("condition_checks").addProperty("create:nonzero_rotation",running);
        recipe.getAsJsonObject("condition_checks").addProperty("mekanism:energy_available",running);
        evidence.observeRecipe("press",recipe);
        JsonObject configuration = current(ProductionNativeFixture.configured(),tick);
        configuration.addProperty("operation","machine.configuration"); configuration.addProperty("configuration_operation","machine.configure");
        configuration.addProperty("status","matched"); evidence.observeConfiguration("finished_only",configuration);
        for (Link link : manifest.links()) {
            JsonObject value = current(ProductionNativeFixture.connection(link),tick); value.addProperty("operational",running); evidence.observeLink(link.id(),value);
        }
    }
    private static ObservationFreshness fact(ProductionNativeEvidence evidence, ObservationKind kind, String id, long tick) {
        return evidence.freshness(tick).stream().filter(value -> value.kind() == kind && value.id().equals(id)).findFirst().orElseThrow();
    }
    private static JsonObject current(JsonObject row, long tick) { row.addProperty("tick",tick); return row; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
