// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import java.math.BigDecimal;
import java.util.Map;
import org.maiwithu.maicraft.server.machine.ProductionEventJournal;
import static org.maiwithu.maicraft.core.integration.machine.runtime.ProductionObserverFixture.check;

public final class ProductionTransitBaselineTest {
    public static void main(String[] args) {
        oldInFlightCargoIsNotNewDelivery(); sameTickOrdering(); legacyAndForeignMarkersRemainUnattributed();
        System.out.println("ProductionTransitBaselineTest passed");
    }

    private static void oldInFlightCargoIsNotNewDelivery() {
        var fixture = fixture(); var old = fixture.journal.markExtraction(90); fixture.baseline(); produce(fixture);
        fixture.transfer(0, 100, 131, "sheet#plain", 3, old); fixture.tick = 132; fixture.stock = 3;
        check(!fixture.settle(), "Old in-flight stock arriving beside new production cannot prove this window's delivery");
        Map<?, ?> flow = (Map<?, ?>) fixture.monitor.report().get("flow");
        check(((Map<?, ?>) flow.get("unattributed_delivery_by_identity")).get("sheet#plain").equals(BigDecimal.valueOf(3)), "Real old delivery stays visible without receiving new-window credit");
        check(((Map<?, ?>) flow.get("excluded_delivery_events")).containsKey("prebaseline_extraction"), "Explain the old extraction boundary");
        fixture.transfer(0, 100, 133, "sheet#plain", 3); fixture.tick = 134; fixture.stock = 6;
        check(fixture.settle(), "A later actual post-baseline extraction and delivery can satisfy the finite goal");
    }

    private static void sameTickOrdering() {
        var before = fixture(); var old = before.journal.markExtraction(100); before.baseline(); produce(before);
        before.transfer(0, 100, 131, "sheet#plain", 3, old); before.tick = 132;
        check(!before.settle(), "Extraction at the baseline tick but earlier sequence remains old cargo");
        var after = fixture(); after.baseline(); var fresh = after.journal.markExtraction(100); produce(after);
        after.transfer(0, 100, 131, "sheet#plain", 3, fresh); after.tick = 132;
        check(after.settle(), "Sequence ordering can prove post-baseline extraction even within the same server tick");
    }

    private static void legacyAndForeignMarkersRemainUnattributed() {
        var missing = fixture(); missing.baseline(); produce(missing);
        missing.transfer(0, 100, 131, "sheet#plain", 3, null); missing.tick = 132;
        check(!missing.settle(), "A delivery timestamp alone does not prove when the cargo was extracted");
        var foreign = fixture(); foreign.baseline(); produce(foreign);
        var marker = foreign.journal.markExtraction(130);
        foreign.transfer(0, 100, 131, "sheet#plain", 3, new ProductionEventJournal.OrderingMarker("different-world", marker.sequence(), 130)); foreign.tick = 132;
        check(!foreign.settle(), "Extraction from another world stream cannot be stitched into this baseline");
        var future = fixture(); future.baseline(); produce(future);
        var at = future.journal.markExtraction(130);
        future.transfer(0, 100, 131, "sheet#plain", 3, new ProductionEventJournal.OrderingMarker(at.scope(), at.sequence() + 100, 130)); future.tick = 132;
        check(!future.settle(), "An extraction marker after delivery is not valid causal order");
    }

    private static ProductionObserverFixture fixture() { return new ProductionObserverFixture(ProductionObserverFixture.manifest(1)); }
    private static void produce(ProductionObserverFixture fixture) {
        for (int i = 0; i < 3; i++) fixture.output(0, 110 + 10 * i, "test:press");
    }
}
