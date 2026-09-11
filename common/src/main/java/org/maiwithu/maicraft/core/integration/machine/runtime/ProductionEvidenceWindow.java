// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Measures a finite run of native production events; an inventory increase alone is not production. */
public final class ProductionEvidenceWindow {
    public enum Provenance { NATIVE_RECIPE_OUTPUT, OBSERVED_STORAGE_DELTA }
    public enum Status { AWAITING_EVIDENCE, OBSERVING, STALLED, VERIFIED, INVALIDATED }

    public record Requirement(String resourceKey, BigDecimal minimumOutput, int minimumEvents,
                              long windowTicks, long maxIdleTicks) {
        public Requirement {
            if (resourceKey == null || resourceKey.isBlank() || minimumOutput == null
                    || minimumOutput.signum() <= 0 || minimumEvents < 2 || windowTicks < 1 || maxIdleTicks < 1)
                throw new IllegalArgumentException("Production requires a resource, positive amount, two events and a finite window");
        }
    }

    /** Sequence belongs to one server observation stream, not the client receive time. */
    public record Event(String scope, long sequence, String producer, String resourceKey,
                        BigDecimal amount, long gameTick, Provenance provenance) {
        public Event {
            Objects.requireNonNull(scope); Objects.requireNonNull(producer);
            Objects.requireNonNull(resourceKey); Objects.requireNonNull(provenance);
            if (sequence < 1 || gameTick < 0 || amount == null || amount.signum() <= 0)
                throw new IllegalArgumentException("Invalid production event");
        }
    }

    private final String scope;
    private final Set<String> producers;
    private final Requirement requirement;
    private long lastSequence, latestEventTick = -1, firstRunTick = -1, lastRunTick = -1;
    private long runEvents, totalEvents, interruptedRuns;
    private BigDecimal runOutput = BigDecimal.ZERO, totalOutput = BigDecimal.ZERO;
    private BigDecimal observedGrowth = BigDecimal.ZERO;
    private String invalidation;

    public ProductionEvidenceWindow(String scope, Set<String> producers, Requirement requirement) {
        if (scope == null || scope.isBlank() || producers == null || producers.isEmpty()
                || producers.stream().anyMatch(value -> value == null || value.isBlank()))
            throw new IllegalArgumentException("A production window needs a scope and observed producers");
        this.scope = scope; this.producers = Set.copyOf(producers);
        this.requirement = Objects.requireNonNull(requirement);
    }

    /** Returns false for stale, foreign or unrelated events. Only native output advances the proof. */
    public boolean accept(Event event) {
        if (invalidation != null || !scope.equals(event.scope()) || event.sequence() <= lastSequence) return false;
        if (event.gameTick() < latestEventTick) {
            invalidate("server_event_order_changed");
            return false;
        }
        lastSequence = event.sequence(); latestEventTick = event.gameTick();
        if (!producers.contains(event.producer()) || !requirement.resourceKey().equals(event.resourceKey())) return false;
        if (event.provenance() == Provenance.OBSERVED_STORAGE_DELTA) {
            observedGrowth = observedGrowth.add(event.amount());
            return false;
        }
        // A long interruption starts a new continuous run, retaining the actual total already made.
        if (lastRunTick >= 0 && event.gameTick() - lastRunTick > requirement.maxIdleTicks()) {
            interruptedRuns++; firstRunTick = -1; runEvents = 0; runOutput = BigDecimal.ZERO;
        }
        if (firstRunTick < 0) firstRunTick = event.gameTick();
        lastRunTick = event.gameTick(); runEvents++; totalEvents++;
        runOutput = runOutput.add(event.amount()); totalOutput = totalOutput.add(event.amount());
        return true;
    }

    /** A changed machine identity or incomplete native event history must not silently preserve a proof. */
    public void invalidate(String reason) {
        if (invalidation == null) invalidation = Objects.requireNonNull(reason);
    }

    public Status status(long serverTick) {
        if (invalidation != null) return Status.INVALIDATED;
        if (lastRunTick < 0) return Status.AWAITING_EVIDENCE;
        if (serverTick < latestEventTick) return Status.INVALIDATED;
        if (serverTick - lastRunTick > requirement.maxIdleTicks()) return Status.STALLED;
        if (runEvents >= requirement.minimumEvents()
                && lastRunTick - firstRunTick >= requirement.windowTicks()
                && runOutput.compareTo(requirement.minimumOutput()) >= 0) return Status.VERIFIED;
        return Status.OBSERVING;
    }

    public Map<String, Object> report(long serverTick) {
        Map<String, Object> result = new LinkedHashMap<>();
        Status status = status(serverTick);
        result.put("status", status.name().toLowerCase(java.util.Locale.ROOT));
        result.put("machine_production_verified", status == Status.VERIFIED);
        result.put("evidence_source", "native_recipe_output_events");
        result.put("scope", scope); result.put("resource_key", requirement.resourceKey());
        result.put("observed_server_tick", serverTick); result.put("last_event_sequence", lastSequence);
        result.put("native_output_total", totalOutput); result.put("native_events_total", totalEvents);
        result.put("continuous_run_output", runOutput); result.put("continuous_run_events", runEvents);
        result.put("continuous_run_span_ticks", firstRunTick < 0 ? 0 : lastRunTick - firstRunTick);
        result.put("required_window_ticks", requirement.windowTicks());
        result.put("required_output", requirement.minimumOutput());
        result.put("required_events", requirement.minimumEvents());
        result.put("max_idle_ticks", requirement.maxIdleTicks());
        result.put("interrupted_runs", interruptedRuns);
        result.put("unattributed_storage_growth", observedGrowth);
        result.put("proof_scope", "finite observed run; does not guarantee future output or delivery to a sink");
        if (invalidation != null) result.put("invalidation_reason", invalidation);
        else if (serverTick < latestEventTick) result.put("invalidation_reason", "server_clock_moved_backwards");
        return Map.copyOf(result);
    }
}
