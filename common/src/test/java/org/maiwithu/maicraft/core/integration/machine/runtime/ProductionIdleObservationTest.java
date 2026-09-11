// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import static org.maiwithu.maicraft.core.integration.machine.runtime.ProductionObserverFixture.check;

public final class ProductionIdleObservationTest {
    public static void main(String[] args) {
        setupAndNavigationDoNotTimeOut(); upstreamCompletionResetsIdle(); onlyAttributedDeliveryAdvancesTime();
        System.out.println("ProductionIdleObservationTest passed");
    }

    private static void setupAndNavigationDoNotTimeOut() {
        var fixture = ProductionProcessingProgressTest.fixture(); fixture.baseline();
        check(fixture.monitor.processingIdleTicks() == -1, "Baseline is not an observation timeout checkpoint");
        fixture.tick = 10_000; round(fixture);
        check(fixture.monitor.processingIdleTicks() == 0, "Long material acquisition before observation must not count as a stall");
        check(fixture.monitor.observationStartedTick() == 10_000, "Watchdog starts from completed observation coverage");
        fixture.tick += 40; round(fixture);
        check(fixture.monitor.processingIdleTicks() == 40, "Root can bound actual observed inactivity");
        fixture.navigationDelay = 80; fixture.tick++;
        fixture.monitor.tick();
        check(fixture.monitor.processingIdleTicks() == -1, "Do not make timeout decisions while navigating to the next group");
    }

    private static void upstreamCompletionResetsIdle() {
        var fixture = ProductionProcessingProgressTest.fixture(); fixture.baseline(); round(fixture);
        fixture.tick = 150;
        fixture.journal.append(ProductionProcessingProgressTest.output(20, "test:upstream", 149)); round(fixture);
        check(fixture.monitor.latestProcessingTick() == 149 && fixture.monitor.processingIdleTicks() == 1,
                "Upstream work must keep the multi-stage process alive before target output exists");
        check(!fixture.monitor.productionWindowVerified(), "An intermediate completion is not final production proof");
        check(fixture.monitor.processingCoverageTick() == 150 && fixture.monitor.observedServerTick() == 150,
                "Expose the server time actually covered by the observation");
    }

    private static void onlyAttributedDeliveryAdvancesTime() {
        var fixture = new ProductionObserverFixture(ProductionObserverFixture.manifest(1));
        var old = fixture.journal.markExtraction(90); fixture.baseline();
        for (int i = 0; i < 3; i++) fixture.output(0, 110 + 10 * i, "test:press");
        fixture.transfer(0, 100, 131, "sheet#plain", 3, old); fixture.tick = 132; round(fixture);
        check(fixture.monitor.productionWindowVerified(), "A finite native window remains available independently of delivery");
        check(fixture.monitor.lastAttributedDeliveryTick() == -1, "Old/unattributed cargo must not keep the delivery watchdog alive");
        fixture.transfer(0, 100, 200, "sheet#plain", 3); fixture.tick = 201; round(fixture);
        check(fixture.monitor.lastAttributedDeliveryTick() == 200, "Only valid post-baseline endpoint delivery advances its activity time");
    }

    private static void round(ProductionObserverFixture fixture) {
        for (int i = 0; i < 100; i++) {
            fixture.monitor.tick();
            if (fixture.monitor.consumeRoundBoundary()) return;
        }
        throw new AssertionError("Expected a complete observation round");
    }
}
