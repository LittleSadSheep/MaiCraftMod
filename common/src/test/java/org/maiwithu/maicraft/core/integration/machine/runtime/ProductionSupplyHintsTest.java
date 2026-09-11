// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Resource;

public final class ProductionSupplyHintsTest {
    public static void main(String[] args) {
        boundHintsAreImmutable(); invalidHintsAreRejected(); consumersCrossTransportButNotProcessing();
        System.out.println("ProductionSupplyHintsTest passed");
    }

    private static void boundHintsAreImmutable() {
        JsonObject authored = ProductionRunPlanBindingTest.manifest(), report = ProductionRunPlanBindingTest.report(authored, "plain");
        var plan = new ProductionRunPlan(BlockPos.ZERO, "minecraft:overworld", authored); plan.bindResolved(report);
        Resource selector = new Resource("items", "minecraft:raw_iron"), exact = plan.resolvedResource(selector);
        check(plan.supplyBatch("source", selector) == 1 && plan.supplyBatch("source", exact) == 1, "Authored and exact selectors share one batch hint");
        check(plan.supplyConsumers("source", selector).equals(Set.of("process")), "Supply waits for the first actual process");
        try { plan.supplyConsumers("source", selector).clear(); throw new AssertionError("Hint consumer set was mutable"); }
        catch (UnsupportedOperationException expected) { }
        report.getAsJsonArray("supplies").get(0).getAsJsonObject().addProperty("first_batch", 2);
        check(plan.supplyBatch("source", selector) == 1 && plan.authoredJson().equals(authored), "Metadata mutation cannot drift the frozen plan");
        rejects(() -> plan.bindResolved(report));
    }

    private static void invalidHintsAreRejected() {
        List<Consumer<JsonObject>> changes = List.of(
                row -> row.addProperty("first_batch", 0), row -> row.addProperty("first_batch", 4),
                row -> row.addProperty("first_batch", 1.5), row -> row.addProperty("amount", 4),
                row -> row.add("pacing_consumers", JsonParser.parseString("[\"sink\"]")),
                row -> row.add("pacing_consumers", JsonParser.parseString("[\"process\",\"process\"]")),
                row -> row.add("pacing_consumers", JsonParser.parseString("[]")));
        for (Consumer<JsonObject> change : changes) {
            JsonObject authored = ProductionRunPlanBindingTest.manifest(), report = ProductionRunPlanBindingTest.report(authored, "plain");
            change.accept(report.getAsJsonArray("supplies").get(0).getAsJsonObject());
            var plan = new ProductionRunPlan(BlockPos.ZERO, "minecraft:overworld", authored);
            rejects(() -> plan.bindResolved(report)); check(!plan.bound(), "Invalid pacing hints must not partially bind execution");
        }
    }

    private static void consumersCrossTransportButNotProcessing() {
        JsonObject authored = ProductionRunPlanBindingTest.manifest();
        authored.getAsJsonArray("nodes").add(JsonParser.parseString("{\"id\":\"pipe\",\"kind\":\"transport\",\"offset\":[1,0,0]}"));
        authored.getAsJsonArray("ports").add(JsonParser.parseString("{\"id\":\"pipe-in\",\"node\":\"pipe\",\"offset\":[1,0,0],\"face\":\"west\",\"medium\":\"items\",\"direction\":\"input\"}"));
        authored.getAsJsonArray("ports").add(JsonParser.parseString("{\"id\":\"pipe-out\",\"node\":\"pipe\",\"offset\":[1,0,0],\"face\":\"east\",\"medium\":\"items\",\"direction\":\"output\"}"));
        JsonObject supply = authored.getAsJsonArray("links").get(0).getAsJsonObject();
        supply.addProperty("to", "pipe-in"); supply.add("path", JsonParser.parseString("[[0,0,0],[1,0,0]]"));
        authored.getAsJsonArray("links").add(JsonParser.parseString("{\"id\":\"pipe-feed\",\"from\":\"pipe-out\",\"to\":\"in\",\"medium\":\"items\",\"resource\":\"minecraft:raw_iron\",\"amount\":3,\"path\":[[1,0,0],[2,0,0]]}"));
        var plan = new ProductionRunPlan(BlockPos.ZERO, "minecraft:overworld", authored);
        plan.bindResolved(ProductionRunPlanBindingTest.report(authored, "plain"));
        check(plan.supplyConsumers("source", new Resource("items", "minecraft:raw_iron")).equals(Set.of("process")), "Transport nodes do not consume a recipe batch");
        // A declared but non-first process must never replace the actual input consumer in metadata.
        JsonObject unrelated = authored.deepCopy();
        unrelated.getAsJsonArray("nodes").add(JsonParser.parseString("{\"id\":\"later\",\"kind\":\"process\",\"offset\":[6,0,0],\"recipe_id\":\"test:later\",\"batches\":3}"));
        JsonObject invalid = ProductionRunPlanBindingTest.report(unrelated, "plain");
        invalid.getAsJsonArray("supplies").get(0).getAsJsonObject().add("pacing_consumers", JsonParser.parseString("[\"later\"]"));
        rejects(() -> new ProductionRunPlan(BlockPos.ZERO, "minecraft:overworld", unrelated).bindResolved(invalid));
    }

    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; } throw new AssertionError("Invalid supply hint accepted"); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
