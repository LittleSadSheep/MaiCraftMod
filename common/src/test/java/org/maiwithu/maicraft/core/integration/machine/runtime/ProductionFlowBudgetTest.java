// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.Map;
import static org.maiwithu.maicraft.core.integration.machine.runtime.ProductionObserverFixture.check;

public final class ProductionFlowBudgetTest {
    public static void main(String[] args) {
        minimumYieldDoesNotRequireCapacity(); everyIntendedBranchNeedsEvidence();
        System.out.println("ProductionFlowBudgetTest passed");
    }

    private static void minimumYieldDoesNotRequireCapacity() {
        JsonObject manifest = ProductionObserverFixture.manifest(1);
        manifest.getAsJsonArray("links").get(0).getAsJsonObject().addProperty("amount", 100);
        manifest.getAsJsonArray("nodes").get(0).getAsJsonObject().addProperty("batches", 100);
        var fixture = new ProductionObserverFixture(manifest); fixture.baseline();
        for (int i = 0; i < 3; i++) fixture.output(0, 110 + i * 10, "test:press");
        fixture.transfer(0, 100, 131, "sheet#plain", 3); fixture.tick = 132;
        check(fixture.settle(), "Actual minimum yield 3 must satisfy a route with maximum/capacity 100");
        Map<?, ?> flow = (Map<?, ?>) fixture.monitor.report().get("flow");
        Map<?, ?> link = (Map<?, ?>) ((Map<?, ?>) flow.get("declared_links")).get("delivery0");
        check(link.get("capacity_budget").equals(100L) && !link.containsKey("required"), "Do not label an upper capacity as a required stochastic output");
        check(flow.get("covered_resources").toString().contains("target resource subgraph"), "The report must not claim unobserved upstream flow");
    }

    private static void everyIntendedBranchNeedsEvidence() {
        var fixture = new ProductionObserverFixture(ProductionObserverFixture.manifest(2)); fixture.baseline();
        for (int i = 0; i < 3; i++) fixture.output(0, 110 + i * 10, "test:press");
        fixture.transfer(0, 100, 131, "sheet#plain", 3); fixture.tick = 132;
        check(!fixture.settle(), "One active branch cannot stand in for the other declared branch");
        fixture.output(20, 133, "test:press"); fixture.tick = 134;
        check(!fixture.settle(), "The second branch needs actual flow as well as native processing");
        fixture.transfer(20, 100, 135, "sheet#plain", 1); fixture.tick = 136;
        check(fixture.settle(), "Each branch has real activity and the total minimum is met without requiring all route capacities");

        var oldStock = new ProductionObserverFixture(ProductionObserverFixture.manifest(2)); oldStock.baseline();
        for (int i = 0; i < 3; i++) oldStock.output(0, 110 + i * 10, "test:press");
        oldStock.transfer(0, 100, 131, "sheet#plain", 3); oldStock.transfer(20, 100, 132, "sheet#plain", 1); oldStock.tick = 133;
        check(!oldStock.settle(), "Flow of pre-existing contents cannot claim an unrun branch's recipe completed");
    }
}
