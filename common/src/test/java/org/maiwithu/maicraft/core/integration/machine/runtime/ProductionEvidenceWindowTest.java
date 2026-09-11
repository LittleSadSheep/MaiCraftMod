// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import java.math.BigDecimal;
import java.util.Set;
import static org.maiwithu.maicraft.core.integration.machine.runtime.ProductionEvidenceWindow.*;

public final class ProductionEvidenceWindowTest {
    public static void main(String[] args) {
        var window = window();
        check(!window.accept(event("world-A", 1, 0, Provenance.OBSERVED_STORAGE_DELTA)), "growth must not prove processing");
        check(window.status(0) == Status.AWAITING_EVIDENCE, "storage is only supporting evidence");
        check(window.accept(event("world-A", 2, 10, Provenance.NATIVE_RECIPE_OUTPUT)), "first native event");
        check(!window.accept(event("world-A", 2, 10, Provenance.NATIVE_RECIPE_OUTPUT)), "replay must not count");
        check(!window.accept(event("world-B", 3, 110, Provenance.NATIVE_RECIPE_OUTPUT)), "foreign world must not count");
        check(window.status(110) == Status.OBSERVING, "elapsed time alone cannot establish a sustained run");
        window.accept(event("world-A", 3, 110, Provenance.NATIVE_RECIPE_OUTPUT));
        check(window.status(110) == Status.VERIFIED, "native outputs across the required window");
        var frozen = window.report(110);
        check(window.status(231) == Status.STALLED, "old output cannot prove current operation");
        window.accept(event("world-A", 4, 240, Provenance.NATIVE_RECIPE_OUTPUT));
        check(window.status(240) == Status.OBSERVING, "restart must prove another continuous run");
        check(window.report(240).get("native_output_total").equals(BigDecimal.valueOf(3)), "retain real partial progress");
        check(Boolean.TRUE.equals(frozen.get("machine_production_verified")), "past report must be immutable");
        window.accept(event("world-A", 5, 340, Provenance.NATIVE_RECIPE_OUTPUT));
        check(window.status(340) == Status.VERIFIED, "recovered machine can establish a new proof");
        window.invalidate("output_history_truncated");
        check(window.status(340) == Status.INVALIDATED, "missing events invalidate completeness");

        var wrong = window();
        wrong.accept(new Event("world-A", 1, "unrelated-press", "items:create:iron_sheet", BigDecimal.TEN, 0,
                Provenance.NATIVE_RECIPE_OUTPUT));
        wrong.accept(new Event("world-A", 2, "press", "items:minecraft:gold_ingot", BigDecimal.TEN, 100,
                Provenance.NATIVE_RECIPE_OUTPUT));
        check(wrong.status(100) == Status.AWAITING_EVIDENCE, "other machines and resources cannot satisfy output");
        wrong.accept(event("world-A", 3, 90, Provenance.NATIVE_RECIPE_OUTPUT));
        check(wrong.status(100) == Status.INVALIDATED, "out of order stream must not invent chronology");
        System.out.println("ProductionEvidenceWindowTest: passed");
    }

    private static ProductionEvidenceWindow window() {
        return new ProductionEvidenceWindow("world-A", Set.of("press"), new Requirement(
                "items:create:iron_sheet", BigDecimal.valueOf(2), 2, 100, 120));
    }

    private static Event event(String scope, long sequence, long tick, Provenance provenance) {
        return new Event(scope, sequence, "press", "items:create:iron_sheet", BigDecimal.ONE, tick, provenance);
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
