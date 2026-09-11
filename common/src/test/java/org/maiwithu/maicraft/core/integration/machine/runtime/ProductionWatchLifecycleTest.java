// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import static org.maiwithu.maicraft.core.integration.machine.runtime.ProductionObserverFixture.check;

public final class ProductionWatchLifecycleTest {
    public static void main(String[] args) {
        allGroupsShareEarlyCursor(); releaseOnlyOwnWatches(); pendingBaselineCanRelease(); laterGapCannotBecomeBaseline();
        System.out.println("ProductionWatchLifecycleTest passed");
    }

    private static void allGroupsShareEarlyCursor() {
        var fixture = new ProductionObserverFixture(ProductionObserverFixture.manifest(5));
        check(!fixture.monitor.baseline(), "Sink baseline is sampled first");
        check(!fixture.monitor.baseline(), "The first group alone cannot finish baseline");
        fixture.output(20, 101, "test:press"); fixture.tick = 102;
        fixture.baseline();
        check(fixture.eventBodies.size() == 6, "All five producer groups and sink must be retained before supply");
        String scope = null;
        for (int i = 0; i < fixture.eventBodies.size(); i++) {
            JsonObject body = fixture.eventBodies.get(i);
            check(body.getAsJsonArray("positions").size() <= 4, "Retention must preserve the public point bound");
            check(fixture.journal.retention(fixture.connection, ProductionObserverFixture.endpoints(body)).get("retained").getAsBoolean(), "Every baseline group must be retained");
            if (i > 0) {
                check(body.get("after_sequence").getAsLong() == 0, "Later registration must keep the earliest cursor");
                if (scope == null) scope = body.get("scope").getAsString();
                check(scope.equals(body.get("scope").getAsString()), "All groups belong to one world observation scope");
            }
        }
        fixture.settle();
        Map<?, ?> production = (Map<?, ?>) fixture.monitor.report().get("production");
        check(production.get("native_output_total").equals(BigDecimal.ONE), "Output during group registration must not be skipped");
    }

    private static void releaseOnlyOwnWatches() {
        var fixture = new ProductionObserverFixture(ProductionObserverFixture.manifest(2)); fixture.baseline();
        Object anotherConnection = new Object(); Set<String> shared = Set.of("0,0,0"); fixture.journal.retain(anotherConnection, shared);
        var bodies = fixture.monitor.releaseBodies(); check(bodies.size() == 3, "Release every registered group exactly once");
        check(fixture.monitor.releaseBodies().isEmpty(), "Cleanup bodies must be single-use");
        fixture.observed = new BlockPos(9999, 9999, 9999);
        for (JsonObject body : bodies) {
            check(body.get("release_watch").getAsBoolean() && body.has("scope"), "Release must retain its known world scope");
            JsonObject result = fixture.request("machine.production_events", body, false);
            check(result.get("released_endpoints").getAsInt() > 0, "Metadata release must work after leaving observation range");
        }
        check(fixture.journal.retention(anotherConnection, shared).get("retained").getAsBoolean(), "One player must not release another connection's watch");
        check(!fixture.journal.retention(fixture.connection, shared).get("retained").getAsBoolean(), "This connection's watch must be released");
        try { fixture.monitor.tick(); throw new AssertionError("Closed observer resumed retention"); }
        catch (IllegalStateException expected) { /* cleanup is terminal for this observer */ }
    }

    private static void pendingBaselineCanRelease() {
        var fixture = new ProductionObserverFixture(ProductionObserverFixture.manifest(1));
        fixture.monitor.baseline(); fixture.delay = true;
        check(!fixture.monitor.baseline() && fixture.pending != null, "Baseline must still be awaiting a receipt");
        var bodies = fixture.monitor.releaseBodies();
        check(bodies.size() == 1 && bodies.getFirst().getAsJsonArray("positions").size() == 1,
                "Cleanup must include a submitted group even before its baseline receipt arrives");
    }

    private static void laterGapCannotBecomeBaseline() {
        var fixture = new ProductionObserverFixture(ProductionObserverFixture.manifest(2));
        fixture.monitor.baseline(); fixture.monitor.baseline();
        for (int i = 0; i < 160; i++) fixture.output(20, 100 + i, "test:press");
        fixture.tick = 300;
        try { fixture.baseline(); throw new AssertionError("A later retained group hid missing pre-registration history"); }
        catch (IllegalStateException expected) { check(fixture.monitor.report().containsKey("unknown_reason"), "Baseline loss must be explicit"); }
    }
}
