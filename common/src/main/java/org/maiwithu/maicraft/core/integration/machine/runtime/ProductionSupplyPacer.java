// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** One batch boundary at a time; only new native events from the first consumers release later batches. */
final class ProductionSupplyPacer {
    private final ProductionSupplyBudget budget;
    private final long batch, batches, initialObserved, initialAllowance, refillRounds;
    private final Map<String, Long> consumerBatches, baseline = new LinkedHashMap<>(), latest = new LinkedHashMap<>();
    private long nextTransferTick, waitingSince = -1;
    private String problem;
    private boolean blockedAttempt;

    ProductionSupplyPacer(ProductionSupplyBudget budget, long batch, Map<String, Long> consumerBatches,
                          long initialObserved, Map<String, Long> progress) {
        if (!budget.initialized() || batch < 1 || batch > budget.total() || consumerBatches.isEmpty()
                || consumerBatches.values().stream().anyMatch(count -> count == null || count < 1) || initialObserved < 0)
            throw new IllegalArgumentException("Invalid native production supply pacing hints");
        this.budget = budget; this.batch = batch; this.consumerBatches = Map.copyOf(consumerBatches);
        this.batches = budget.total() / batch + (budget.total() % batch == 0 ? 0 : 1);
        this.initialAllowance = Math.max(batch, budget.allocated());
        long remaining = budget.total() - initialAllowance;
        this.refillRounds = remaining / batch + (remaining % batch == 0 ? 0 : 1);
        this.initialObserved = initialObserved;
        consumerBatches.keySet().forEach(id -> {
            long count = progress.getOrDefault(id, 0L);
            if (count < 0) throw new IllegalArgumentException("Negative native processing event count");
            baseline.put(id, count); latest.put(id, count);
        });
    }

    void observe(Map<String, Long> progress, long tick) {
        for (String consumer : consumerBatches.keySet()) {
            long count = progress.getOrDefault(consumer, 0L), previous = latest.get(consumer);
            if (count < previous) throw new IllegalStateException("production_processing_progress_rewound: " + consumer);
            if (count > previous) { latest.put(consumer, count); waitingSince = tick; problem = null; }
        }
    }

    /** Independent consumers each advance a round; a process already at its authored count cannot block later rounds. */
    private long allowance() {
        long rounds = refillRounds;
        for (String consumer : consumerBatches.keySet()) {
            long completed = latest.get(consumer) - baseline.get(consumer);
            if (completed < consumerBatches.get(consumer)) rounds = Math.min(rounds, completed);
        }
        return rounds >= refillRounds ? budget.total() : Math.addExact(initialAllowance, Math.multiplyExact(batch, rounds));
    }

    int quoteAmount() {
        long allocated = budget.allocated();
        if (allocated >= budget.total()) return 0;
        long boundary = initialAllowance;
        if (allocated >= initialAllowance) {
            long completed = (allocated - initialAllowance) / batch;
            boundary = completed >= refillRounds - 1 ? budget.total()
                    : Math.addExact(initialAllowance, Math.multiplyExact(completed + 1, batch));
        }
        return (int) Math.min(64, Math.max(0, Math.min(allowance(), boundary) - allocated));
    }

    JsonObject quote(ProductionWork work, JsonObject body) {
        int requested = body.get("amount").getAsBigDecimal().intValueExact();
        if (requested < 1 || requested > quoteAmount()) throw new IllegalStateException("production_quote_exceeds_released_batch");
        return work.request("inventory.quote", body, false);
    }

    boolean ready(long tick) { return quoteAmount() > 0 && tick >= nextTransferTick; }
    void settled(long tick, int transferred) {
        nextTransferTick = Math.addExact(tick, 20);
        blockedAttempt = transferred == 0;
        if (transferred > 0) { waitingSince = -1; problem = null; }
    }

    String unverifiable(long tick, int minimumEvents, long maxIdleTicks) {
        boolean missingEvents = false;
        for (String consumer : consumerBatches.keySet()) {
            long required = Math.min(minimumEvents, Math.min(batches, consumerBatches.get(consumer)));
            if (latest.get(consumer) - baseline.get(consumer) < required) missingEvents = true;
        }
        boolean waiting = quoteAmount() == 0 && (budget.remaining() > 0 || missingEvents)
                || blockedAttempt && budget.remaining() > 0;
        if (!waiting) { waitingSince = -1; problem = null; return null; }
        if (waitingSince < 0) waitingSince = tick;
        if (tick - waitingSince <= maxIdleTicks) return null;
        problem = budget.remaining() == 0 ? "production_budget_exhausted_without_independent_processing_events"
                : blockedAttempt ? "production_supply_native_buffer_made_no_progress" : "production_supply_waiting_for_native_consumer_event";
        if (initialObserved > batch) problem += ": initial_unpaced_stock=" + initialObserved;
        return problem + "; consumers=" + consumerBatches.keySet();
    }

    Map<String, Object> report() {
        var result = new LinkedHashMap<String, Object>();
        result.put("first_batch", batch); result.put("released_total", allowance());
        result.put("pacing_consumers", consumerBatches.keySet()); result.put("native_consumer_events", Map.copyOf(latest));
        result.put("initial_unpaced_stock", initialObserved > batch ? initialObserved : 0L);
        result.put("initial_stock_excess_over_first_batch", Math.max(0, initialObserved - batch));
        result.put("pacing_can_limit_preexisting_stock", false); result.put("next_transfer_tick", nextTransferTick);
        result.put("waiting_for_native_event", quoteAmount() == 0 && budget.remaining() > 0);
        if (problem != null) result.put("unverifiable_reason", problem);
        return Map.copyOf(result);
    }
}
