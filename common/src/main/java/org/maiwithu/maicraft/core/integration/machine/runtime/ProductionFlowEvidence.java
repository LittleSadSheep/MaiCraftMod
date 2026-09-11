// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Link;

/** Keeps exact resource identity separate from authored registry selectors and unattributed sink growth. */
final class ProductionFlowEvidence {
    private final ProductionRunPlan plan;
    private final ProductionFlowPaths paths;
    private final Map<String, Map<String, BigDecimal>> byLink = new LinkedHashMap<>();
    private final Map<String, BigDecimal> produced = new LinkedHashMap<>(), delivered = new LinkedHashMap<>();
    private final Map<String, BigDecimal> observedDeliveries = new LinkedHashMap<>(), unattributedDeliveries = new LinkedHashMap<>();
    private final Map<String, Long> excluded = new LinkedHashMap<>();
    private String scope;
    private long baselineSequence, baselineTick;
    private long latestDeliveryTick = -1;
    private int rejectedEvents;

    ProductionFlowEvidence(ProductionRunPlan plan, ProductionFlowPaths paths) { this.plan = plan; this.paths = paths; }

    void baseline(String scope, long sequence, long tick) {
        if (this.scope != null || scope == null || scope.isBlank() || sequence < 0 || tick < 0)
            throw new IllegalStateException("production_flow_baseline_invalid");
        this.scope = scope; baselineSequence = sequence; baselineTick = tick;
    }

    void produced(String resourceId, BigDecimal amount) { produced.merge(resourceId, amount, BigDecimal::add); }

    void accept(JsonObject event) {
        if (!ProductionFlowPaths.text(event, "provenance").equals("mekanism.transporter.native_forward_delivery")
                || !ProductionFlowPaths.matches(plan.manifest().target().resource(), event)) return;
        var route = paths.unique(ProductionFlowPaths.text(event, "source"), ProductionFlowPaths.text(event, "destination"));
        if (route.isEmpty()) { rejectedEvents++; return; }
        BigDecimal amount = event.get("amount").getAsBigDecimal();
        if (amount.signum() <= 0) throw new IllegalStateException("production_invalid_native_delivery_amount");
        String resource = event.get("resource_id").getAsString();
        boolean sink = plan.port(route.getLast().to()).node().equals(plan.manifest().target().node());
        if (sink) observedDeliveries.merge(resource, amount, BigDecimal::add);
        String exclusion = extractionExclusion(event);
        if (exclusion != null) {
            excluded.merge(exclusion, 1L, Math::addExact);
            if (sink) unattributedDeliveries.merge(resource, amount, BigDecimal::add);
            return;
        }
        latestDeliveryTick = Math.max(latestDeliveryTick, ProductionEventCursor.number(event, "tick"));
        for (Link link : route) byLink.computeIfAbsent(link.id(), ignored -> new LinkedHashMap<>()).merge(resource, amount, BigDecimal::add);
        if (sink) delivered.merge(resource, amount, BigDecimal::add);
    }

    long latestDeliveryTick() { return latestDeliveryTick; }

    private String extractionExclusion(JsonObject event) {
        if (scope == null || !event.has("extraction_sequence") || !event.has("extraction_tick")
                || !event.has("extraction_scope")) return "missing_extraction_order";
        if (!scope.equals(ProductionFlowPaths.text(event, "extraction_scope"))) return "extraction_scope_changed";
        try {
            long sequence = ProductionEventCursor.number(event, "extraction_sequence"), tick = ProductionEventCursor.number(event, "extraction_tick");
            if (sequence <= baselineSequence || tick < baselineTick) return "prebaseline_extraction";
            if (sequence >= ProductionEventCursor.number(event, "sequence") || tick > ProductionEventCursor.number(event, "tick"))
                return "invalid_extraction_order";
            return null;
        } catch (RuntimeException malformed) { return "invalid_extraction_order"; }
    }

    BigDecimal provenDelivery() {
        BigDecimal total = BigDecimal.ZERO;
        for (var resource : produced.entrySet()) {
            BigDecimal compatible = delivered.getOrDefault(resource.getKey(), BigDecimal.ZERO).min(resource.getValue());
            total = total.add(compatible);
        }
        return total;
    }

    boolean verified(Map<String, Long> processingProgress) {
        if (paths.links.isEmpty() || provenDelivery().compareTo(BigDecimal.valueOf(plan.manifest().observation().minimumOutput())) < 0) return false;
        if (paths.producers.stream().anyMatch(id -> processingProgress.getOrDefault(id, 0L) < 1)) return false;
        return paths.links.stream().allMatch(this::observed);
    }

    private boolean observed(Link link) {
        return produced.keySet().stream().anyMatch(identity -> byLink.getOrDefault(link.id(), Map.of())
                .getOrDefault(identity, BigDecimal.ZERO).signum() > 0);
    }

    Map<String, Object> report(Map<String, Long> processingProgress) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("delivery_verified", verified(processingProgress)); result.put("native_delivery_by_identity", Map.copyOf(delivered));
        result.put("observed_native_delivery_by_identity", Map.copyOf(observedDeliveries));
        result.put("unattributed_delivery_by_identity", Map.copyOf(unattributedDeliveries)); result.put("excluded_delivery_events", Map.copyOf(excluded));
        result.put("produced_and_delivered", provenDelivery()); result.put("unmatched_or_ambiguous_delivery_events", rejectedEvents);
        Map<String, Object> links = new LinkedHashMap<>();
        for (Link link : paths.links) links.put(link.id(), Map.of("capacity_budget", link.amount(),
                "evidence", "endpoint_delivery_compatible_with_unique_declared_chain",
                "native_delivery_by_identity", Map.copyOf(byLink.getOrDefault(link.id(), Map.of()))));
        result.put("declared_links", links);
        result.put("covered_resources", "target resource subgraph only; upstream resources are not proven by this report");
        result.put("unproven_producer_branches", paths.producers.stream().filter(id -> processingProgress.getOrDefault(id, 0L) < 1).toList());
        result.put("unproven_flow_links", paths.links.stream().filter(link -> !observed(link)).map(Link::id).toList());
        result.put("minimum_target_delivery", plan.manifest().observation().minimumOutput());
        result.put("proof_scope", "post-baseline native extraction and endpoint delivery compatible with one declared chain; actual traversed route and individual-item production causality are not observed");
        if (!verified(processingProgress)) result.put("unknown_reason", "insufficient_native_target_delivery_or_branch_evidence");
        return result;
    }
}
