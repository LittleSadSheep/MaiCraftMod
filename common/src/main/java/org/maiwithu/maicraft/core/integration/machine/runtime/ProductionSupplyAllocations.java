// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared native membership is charged once even when several authored sources are aliases. */
final class ProductionSupplyAllocations {
    private final Map<ProductionSupplyBudget.Key, ProductionSupplyStock.View> sources = new LinkedHashMap<>();
    private final Map<String, Long> charged = new LinkedHashMap<>();

    long observe(ProductionSupplyBudget.Key source, ProductionSupplyStock.View view) {
        var previous = sources.putIfAbsent(source, view);
        if (previous != null && (!previous.fingerprint().equals(view.fingerprint())
                || !previous.allocationKey().equals(view.allocationKey())))
            throw new IllegalStateException("production_source_membership_or_identity_changed: " + source.source());
        return Math.max(0, view.amount() - charged.getOrDefault(view.allocationKey(), 0L));
    }

    void charge(ProductionSupplyBudget.Key source, long amount) {
        if (amount < 0) throw new IllegalArgumentException("Cannot return production allocation by a negative charge");
        var view = java.util.Objects.requireNonNull(sources.get(source), "Source must be observed before allocation");
        charged.merge(view.allocationKey(), amount, Math::addExact);
    }

    String membership(ProductionSupplyBudget.Key source) {
        return sources.containsKey(source) ? sources.get(source).allocationKey() : "unobserved";
    }
}
