// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import java.math.BigDecimal;
import java.util.Map;
import org.maiwithu.maicraft.server.machine.ProductionEventJournal;
import static org.maiwithu.maicraft.core.integration.machine.runtime.ProductionObserverFixture.check;

public final class ProductionOutputMonitorTest {
    public static void main(String[] args) {
        requiresBothNativeProofs();
        largeMachineAndDuplicates();
        recipeAndIdentity();
        baselineAndJournalLoss();
        uniqueDeclaredChain();
        refillWaitsForObservationRound();
        completedBatchSurvivesDelayedObservation();
        System.out.println("ProductionOutputMonitorTest passed");
    }

    private static void requiresBothNativeProofs() {
        var external = new ProductionObserverFixture(ProductionObserverFixture.manifest(1)); external.baseline();
        external.stock = 100; external.tick = 132;
        check(!external.settle(), "External sink stock growth cannot prove either production or delivery");
        check(external.monitor.report().get("sink_net_growth").equals(BigDecimal.valueOf(100)), "External delta must remain visible separately");
        var produced = new ProductionObserverFixture(ProductionObserverFixture.manifest(1)); produced.delay = true; produced.baseline();
        for (int i = 0; i < 3; i++) produced.output(0, 110 + i * 10, "test:press");
        produced.stock = 3; produced.tick = 132;
        check(!produced.settle(), "Native production and stock growth still need native delivery evidence");
        produced.transfer(0, 100, 133, "sheet#plain", 3); produced.tick = 134;
        check(produced.settle(), "Actual production and compatible native delivery should verify");
        var transferOnly = new ProductionObserverFixture(ProductionObserverFixture.manifest(1)); transferOnly.baseline();
        transferOnly.transfer(0, 100, 110, "sheet#plain", 3); transferOnly.tick = 112;
        check(!transferOnly.settle(), "Moving old stock does not prove production");
    }

    private static void largeMachineAndDuplicates() {
        var fixture = new ProductionObserverFixture(ProductionObserverFixture.manifest(5)); fixture.baseline();
        for (int i = 0; i < 3; i++) {
            for (int producer = 0; producer < 5; producer++) fixture.output(producer * 20, 110 + i * 10, "test:press");
            for (int producer = 0; producer < 5; producer++) fixture.transfer(producer * 20, 100, 111 + i * 10, "sheet#plain", 1);
        }
        fixture.tick = 132; fixture.stock = 15;
        check(fixture.settle(), "Machine observation must support more than four distant points");
        var report = fixture.monitor.report(); check(((Number) report.get("observation_groups")).intValue() > 4, "Test requires multiple distant groups");
        Map<?, ?> production = (Map<?, ?>) report.get("production");
        check(((Number) production.get("native_events_total")).longValue() == 15, "Overlapping source/destination pages duplicated events");
        Map<?, ?> flow = (Map<?, ?>) report.get("flow");
        check(((Map<?, ?>) flow.get("native_delivery_by_identity")).get("sheet#plain").equals(BigDecimal.valueOf(15)), "One native delivery was counted for multiple pages");
    }

    private static void recipeAndIdentity() {
        var recipe = new ProductionObserverFixture(ProductionObserverFixture.manifest(1)); recipe.baseline();
        for (int i = 0; i < 3; i++) recipe.output(0, 110 + i * 10, "test:wrong_recipe");
        recipe.transfer(0, 100, 131, "sheet#plain", 3); recipe.tick = 132;
        try { recipe.settle(); throw new AssertionError("Wrong recipe cannot advance the declared production objective"); }
        catch (IllegalStateException expected) { /* immutable evidence failure ends the parent task */ }
        check(recipe.monitor.report().containsKey("unknown_reason"), "Recipe mismatch must be reported");
        var identity = new ProductionObserverFixture(ProductionObserverFixture.manifest(1)); identity.baseline();
        for (int i = 0; i < 3; i++) identity.output(0, 110 + i * 10, "test:press");
        identity.transfer(0, 100, 131, "sheet#different_components", 3); identity.tick = 132;
        check(!identity.settle(), "A different component identity cannot deliver the produced resource");
    }

    private static void baselineAndJournalLoss() {
        var fixture = new ProductionObserverFixture(ProductionObserverFixture.manifest(1));
        for (int i = 0; i < 3; i++) fixture.output(0, 70 + i * 10, "test:press");
        fixture.baseline(); fixture.tick = 120;
        check(!fixture.settle(), "Events before baseline cannot satisfy the new run");
        fixture.journal = new ProductionEventJournal("minecraft:overworld");
        try { fixture.settle(); throw new AssertionError("World journal reset was accepted"); }
        catch (IllegalStateException expected) { check(fixture.monitor.report().containsKey("unknown_reason"), "Loss must invalidate evidence"); }
    }

    private static void uniqueDeclaredChain() {
        var manifest = ProductionObserverFixture.manifest(1); manifest.getAsJsonArray("links").remove(0);
        ProductionObserverFixture.node(manifest, "transport", "transport", 50);
        ProductionObserverFixture.port(manifest, "transport-in", "transport", 50, "input");
        ProductionObserverFixture.port(manifest, "transport-out", "transport", 50, "output");
        ProductionObserverFixture.link(manifest, "first", "out0", "transport-in");
        ProductionObserverFixture.link(manifest, "second", "transport-out", "sink-in");
        var fixture = new ProductionObserverFixture(manifest); fixture.baseline();
        for (int i = 0; i < 3; i++) fixture.output(0, 110 + i * 10, "test:press");
        fixture.transfer(0, 100, 131, "sheet#plain", 3); fixture.tick = 132;
        check(fixture.settle(), "One native endpoint delivery may cover a unique declared transport chain");
        ProductionObserverFixture.link(manifest, "ambiguous-direct", "out0", "sink-in");
        var ambiguous = new ProductionObserverFixture(manifest); ambiguous.baseline();
        for (int i = 0; i < 3; i++) ambiguous.output(0, 110 + i * 10, "test:press");
        ambiguous.transfer(0, 100, 131, "sheet#plain", 3); ambiguous.tick = 132;
        check(!ambiguous.settle(), "An ambiguous route must not credit every branch");
    }

    private static void refillWaitsForObservationRound() {
        var fixture = new ProductionObserverFixture(ProductionObserverFixture.manifest(1)); fixture.baseline();
        check(!fixture.monitor.consumeRoundBoundary(), "Baseline does not finish a production observation round");
        fixture.navigationDelay = 80;
        int refills = 0;
        for (int elapsed = 1; elapsed <= 180; elapsed++) {
            fixture.tick++;
            fixture.monitor.tick();
            boolean boundary = fixture.monitor.consumeRoundBoundary();
            check(!fixture.monitor.consumeRoundBoundary(), "A completed observation boundary is single-use");
            if (elapsed <= 160) check(!boundary, "Far observations must not yield during either navigation leg");
            // A still-full source asks for another pass every 20 ticks; it must not pull us back early.
            boolean sourceNeedsAnotherPass = elapsed >= 20;
            if (boundary && sourceNeedsAnotherPass) { refills++; break; }
        }
        check(refills == 1 && fixture.eventRequests >= 4, "Finish every distant group and sink sample before refilling");
    }

    private static void completedBatchSurvivesDelayedObservation() {
        var fixture = new ProductionObserverFixture(ProductionObserverFixture.manifest(1)); fixture.baseline();
        for (int i = 0; i < 3; i++) fixture.output(0, 110 + i * 10, "test:press");
        fixture.transfer(0, 100, 131, "sheet#plain", 3); fixture.stock = 3; fixture.tick = 500;
        check(fixture.settle(), "Late polling must retain an already completed finite production and delivery window");
        Map<?, ?> production = (Map<?, ?>) fixture.monitor.report().get("production");
        check(production.get("status").equals("stalled"), "Historical proof must not claim current production");
        check(Boolean.TRUE.equals(production.get("machine_production_verified")), "The real completed window remains verified");
        check(((Map<?, ?>) production.get("verified_run")).get("through_tick").equals(130L), "Report the actual completed native interval");
        var oldStock = new ProductionObserverFixture(ProductionObserverFixture.manifest(1)); oldStock.baseline();
        oldStock.stock = 100; oldStock.tick = 500;
        check(!oldStock.settle(), "Delayed stock growth still cannot invent a completed native window");
    }
}
