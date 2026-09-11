// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.List;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.*;

public final class ProductionSupplyBudgetTest {
    public static void main(String[] args) {
        var budget = new ProductionSupplyBudget(100);
        check(budget.initialize(20) == 20, "Only initial native stock is credited");
        check(budget.confirm(receipt("partial", 10, "opaque-components-A"), 64, "opaque-components-A") == 10,
                "Record the actual partial transfer");
        check(budget.remaining() == 70 && budget.injected() == 10, "Partial receipt cannot consume its requested amount");
        budget.confirm(receipt("no_change", 0, "opaque-components-A"), 64, "opaque-components-A");
        check(budget.remaining() == 70, "A full buffer preserves refill budget");
        reject(() -> budget.initialize(50), "A refill cannot credit replenished or leftover native stock again");
        reject(() -> budget.confirm(receipt("applied", 64, "opaque-components-B"), 64, "opaque-components-A"),
                "A different component identity invalidates the receipt");
        check(budget.remaining() == 70, "Rejected receipt must not silently alter budget");
        budget.confirm(receipt("applied", 64, "opaque-components-A"), 64, "opaque-components-A");
        reject(() -> budget.confirm(receipt("applied", 7, "opaque-components-A"), 7, "opaque-components-A"),
                "Cannot inject beyond the finite remaining amount");
        budget.confirm(receipt("applied", 6, "opaque-components-A"), 6, "opaque-components-A");
        check(budget.remaining() == 0 && budget.injected() == 80, "Initial stock plus real deposits equals the allocation");
        reject(() -> budget.confirm(receipt("applied", 1, "opaque-components-A"), 1, "opaque-components-A"),
                "A completed allocation cannot be refilled");

        var large = new ProductionSupplyBudget(Long.MAX_VALUE);
        large.initialize(Long.MAX_VALUE - 1);
        large.confirm(receipt("applied", 1, "exact"), 1, "exact");
        check(large.remaining() == 0, "Large finite windows must not overflow");
        var overstock = new ProductionSupplyBudget(5);
        check(overstock.initialize(500) == 5 && overstock.remaining() == 0, "Unused native stock is not allocated");
        var demands = ProductionSupplyBudget.demands(manifest());
        check(demands.size() == 2, "Only authored sources need player supply");
        check(demands.get(new ProductionSupplyBudget.Key("source", "items", "minecraft:iron_ingot")) == 100,
                "Source fan-out aggregates exact resource demand");
        check(demands.get(new ProductionSupplyBudget.Key("other", "items", "minecraft:iron_ingot")) == 7,
                "Distinct sources retain separate allocations");
        System.out.println("ProductionSupplyBudgetTest: passed");
    }

    private static ProductionManifest manifest() {
        Point point = new Point(0, 0, 0);
        var nodes = List.of(new Node("source", "source", point, null, 0, "inventory_only"),
                new Node("other", "source", point, null, 0, "ordinary"),
                new Node("process", "process", point, "create:pressing/iron_ingot", 2, "inventory_only"),
                new Node("sink", "sink", point, null, 0, "inventory_only"));
        var ports = List.of(new Port("source_out", "source", point, "up", "items", "output"),
                new Port("other_out", "other", point, "up", "items", "output"),
                new Port("process_in", "process", point, "up", "items", "input"),
                new Port("process_out", "process", point, "down", "items", "output"),
                new Port("sink_in", "sink", point, "up", "items", "input"));
        Resource iron = new Resource("items", "minecraft:iron_ingot");
        var links = List.of(new Link("a", "source_out", "process_in", iron, 40, List.of(), List.of()),
                new Link("b", "source_out", "process_in", iron, 60, List.of(), List.of()),
                new Link("c", "other_out", "process_in", iron, 7, List.of(), List.of()),
                new Link("intermediate", "process_out", "sink_in", iron, 200, List.of(), List.of()));
        return new ProductionManifest(nodes, ports, links, List.of(), new Target("sink", iron), new Observation(100, 2, 2, 100));
    }

    private static JsonObject receipt(String status, int moved, String identity) {
        var json = new JsonObject(); json.addProperty("status", status); json.addProperty("transferred", moved);
        json.addProperty("resource_id", identity); return json;
    }
    private static void reject(Runnable action, String message) {
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(message);
    }
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
