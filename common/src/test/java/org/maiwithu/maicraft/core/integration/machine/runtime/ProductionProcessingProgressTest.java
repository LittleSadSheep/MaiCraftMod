// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import static org.maiwithu.maicraft.core.integration.machine.runtime.ProductionObserverFixture.check;

public final class ProductionProcessingProgressTest {
    public static void main(String[] args) {
        upstreamOutputAdvancesBeforeTarget(); sequenceAndCanonicalRecipe(); zeroYieldStillCompletesAConsumedBatch();
        System.out.println("ProductionProcessingProgressTest passed");
    }

    private static void upstreamOutputAdvancesBeforeTarget() {
        var fixture = fixture(); fixture.baseline();
        check(fixture.eventBodies.size() == 3, "A non-target upstream process must also have retained observation coverage");
        for (int i = 0; i < 4; i++) { fixture.tick += 20; fixture.settle(); }
        check(fixture.monitor.processingProgress().get("upstream") == 0, "Twenty ticks alone never completes another batch");
        fixture.journal.append(output(20, "test:upstream", fixture.tick + 1)); fixture.tick += 2;
        check(!fixture.settle(), "An intermediate output cannot finish the target-product goal");
        Map<String, Long> progress = fixture.monitor.processingProgress();
        check(progress.get("upstream") == 1 && progress.get("p0") == 0, "The first consumer can release its next input batch before downstream output exists");
        Map<?, ?> target = (Map<?, ?>) fixture.monitor.report().get("production");
        check(((Number) target.get("native_events_total")).longValue() == 0, "Intermediate completion must not count as final output");
        fixture.tick += 100; fixture.settle();
        check(fixture.monitor.processingProgress().get("upstream") == 1, "Repeated polling does not release extra batches");
        fixture.journal.append(output(20, "test:upstream", fixture.tick + 1)); fixture.tick += 2; fixture.settle();
        check(fixture.monitor.processingProgress().get("upstream") == 2, "A second real completion advances one additional batch");
        fixture.journal.append(output(20, "test:wrong", fixture.tick + 1)); fixture.tick += 2; fixture.settle();
        check(fixture.monitor.processingProgress().get("upstream") == 2, "A wrong upstream recipe does not advance pacing");
    }

    private static void sequenceAndCanonicalRecipe() {
        var fixture = fixture(); var progress = new ProductionProcessProgress(fixture.plan); progress.bind("native-world");
        JsonObject event = output(20, "test:upstream", 100); event.addProperty("scope", "native-world"); event.addProperty("sequence", 1);
        event.addProperty("operations", 64);
        progress.accept(event); progress.accept(event.deepCopy());
        check(progress.snapshot().get("upstream") == 1, "One native event remains one completion despite duplicate receipt, output amount or operations metadata");
        JsonObject unrelated = event.deepCopy(); unrelated.addProperty("producer", "77,0,0"); unrelated.addProperty("sequence", 2); progress.accept(unrelated);
        check(progress.snapshot().get("upstream") == 1, "An undeclared producer cannot release a batch");
        JsonObject foreign = event.deepCopy(); foreign.addProperty("scope", "different-world"); foreign.addProperty("sequence", 99); progress.accept(foreign);
        JsonObject next = event.deepCopy(); next.addProperty("sequence", 3); progress.accept(next);
        check(progress.snapshot().get("upstream") == 2, "A foreign scope cannot advance or suppress the current stream");
    }

    private static void zeroYieldStillCompletesAConsumedBatch() {
        var fixture = fixture(); fixture.baseline();
        JsonObject completed = output(20, "test:upstream", 110);
        JsonArray inputs = completed.getAsJsonArray("outputs").deepCopy();
        completed.add("outputs", new JsonArray()); completed.add("inputs", inputs); completed.addProperty("completed", true);
        fixture.journal.append(completed); fixture.tick = 111; fixture.settle();
        check(fixture.monitor.processingProgress().get("upstream") == 1, "A real consumed zero-yield completion permits the next finite batch");
        check(((Number) ((Map<?, ?>) fixture.monitor.report().get("production")).get("native_events_total")).longValue() == 0,
                "Zero yield must not inflate target output or its event window");
        completed.addProperty("tick", 120); completed.remove("completed"); fixture.journal.append(completed);
        fixture.tick = 121; fixture.settle();
        check(fixture.monitor.processingProgress().get("upstream") == 1, "Consumption without a confirmed completed callback cannot release another batch");
        completed.addProperty("tick", 130); completed.addProperty("completed", true); completed.add("inputs", new JsonArray()); fixture.journal.append(completed);
        fixture.tick = 131; fixture.settle();
        check(fixture.monitor.processingProgress().get("upstream") == 1, "An empty completion with no real input/output is not progress");
    }

    static ProductionObserverFixture fixture() {
        JsonObject manifest = ProductionObserverFixture.manifest(1);
        ProductionObserverFixture.node(manifest, "upstream", "process", 20);
        manifest.getAsJsonArray("nodes").get(2).getAsJsonObject().addProperty("recipe_id", "test:upstream");
        ProductionObserverFixture.port(manifest, "up-out", "upstream", 20, "output");
        ProductionObserverFixture.port(manifest, "p0-in", "p0", 0, "input");
        ProductionObserverFixture.link(manifest, "intermediate", "up-out", "p0-in");
        manifest.getAsJsonArray("links").get(1).getAsJsonObject().addProperty("resource", "test:intermediate");
        return new ProductionObserverFixture(manifest);
    }

    static JsonObject output(int producer, String recipe, long tick) {
        JsonObject event = new JsonObject(); event.addProperty("kind", "recipe_output"); event.addProperty("producer", producer + ",0,0");
        event.addProperty("recipe_id", recipe); event.addProperty("tick", tick); event.addProperty("provenance", "native_recipe_output");
        JsonObject intermediate = ProductionObserverFixture.resource("intermediate#plain", 64);
        intermediate.getAsJsonObject("identity").addProperty("id", "test:intermediate");
        JsonArray outputs = new JsonArray(); outputs.add(intermediate); event.add("outputs", outputs); return event;
    }
}
