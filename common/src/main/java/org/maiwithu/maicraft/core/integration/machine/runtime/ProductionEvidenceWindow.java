// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;

/** 测量有限区间内的原生生产事件；背包数量增加本身不能证明机器进行了生产。 */
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

    /** 序号属于单一服务器观察流，而不是客户端接收时间。 */
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
    private Map<String, Object> verifiedRun;

    public ProductionEvidenceWindow(String scope, Set<String> producers, Requirement requirement) {
        if (scope == null || scope.isBlank() || producers == null || producers.isEmpty()
                || producers.stream().anyMatch(value -> value == null || value.isBlank()))
            throw new IllegalArgumentException("A production window needs a scope and observed producers");
        this.scope = scope; this.producers = Set.copyOf(producers);
        this.requirement = Objects.requireNonNull(requirement);
    }

    /** 过期、外来或不相关事件返回 false；只有原生产物能推进生产证明。 */
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
        // 长时间中断会开始新的连续运行区间，同时保留此前实际产出的总量。
        if (lastRunTick >= 0 && event.gameTick() - lastRunTick > requirement.maxIdleTicks()) {
            interruptedRuns++; firstRunTick = -1; runEvents = 0; runOutput = BigDecimal.ZERO;
        }
        if (firstRunTick < 0) firstRunTick = event.gameTick();
        lastRunTick = event.gameTick(); runEvents++; totalEvents++;
        runOutput = runOutput.add(event.amount()); totalOutput = totalOutput.add(event.amount());
        if (verifiedRun == null && qualifies()) verifiedRun = Map.of("from_tick", firstRunTick,
                "through_tick", lastRunTick, "output", runOutput, "events", runEvents);
        return true;
    }

    /** 机器身份发生变化或原生事件历史不完整时，不能静默保留已有证明。 */
    public void invalidate(String reason) {
        if (invalidation == null) invalidation = Objects.requireNonNull(reason);
    }

    public Status status(long serverTick) {
        if (invalidation != null) return Status.INVALIDATED;
        if (lastRunTick < 0) return Status.AWAITING_EVIDENCE;
        if (serverTick < latestEventTick) return Status.INVALIDATED;
        if (serverTick - lastRunTick > requirement.maxIdleTicks()) return Status.STALLED;
        if (qualifies()) return Status.VERIFIED;
        return Status.OBSERVING;
    }

    /** 有界批次完成后，有限观察到的生产运行仍是有效证据；当前是否空闲应单独报告。 */
    public boolean hasVerifiedRun() { return invalidation == null && verifiedRun != null; }

    private boolean qualifies() {
        return runEvents >= requirement.minimumEvents() && lastRunTick - firstRunTick >= requirement.windowTicks()
                && runOutput.compareTo(requirement.minimumOutput()) >= 0;
    }

    public Map<String, Object> report(long serverTick) {
        Map<String, Object> result = new LinkedHashMap<>();
        Status status = status(serverTick);
        result.put("status", status.name().toLowerCase(Locale.ROOT));
        result.put("machine_production_verified", hasVerifiedRun());
        if (verifiedRun != null) result.put("verified_run", verifiedRun);
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
