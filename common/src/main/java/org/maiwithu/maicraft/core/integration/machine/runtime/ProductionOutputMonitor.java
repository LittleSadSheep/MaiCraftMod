// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Port;

/** Baselines the world journal before supply, then requires native production and matching native delivery. */
final class ProductionOutputMonitor {
    private final ProductionRunPlan plan;
    private final ProductionWork work;
    private final ProductionFlowPaths paths;
    private final ProductionFlowEvidence flow;
    private final ProductionProcessProgress processing;
    private final ProductionEventCursor cursor;
    private final List<List<BlockPos>> groups;
    private final Port sink;
    private final Map<String, Set<String>> recipes = new LinkedHashMap<>();
    private final Set<Integer> requestedWatches = new java.util.LinkedHashSet<>();
    private ProductionEvidenceWindow window;
    private Map<String, BigDecimal> initialStock, stock;
    private long serverTick, baselineTick, baselineSequence, sinkBaselineTick;
    private long observationStartedTick = -1;
    private int groupIndex, baselineGroup;
    private boolean readSink, caughtUp, roundBoundary, releasedWatches, completedRound;
    private String unknown;

    ProductionOutputMonitor(ProductionRunPlan plan, ProductionWork work) {
        this.plan = plan; this.work = work; paths = new ProductionFlowPaths(plan); flow = new ProductionFlowEvidence(plan, paths);
        sink = plan.manifest().ports().stream().filter(port -> port.node().equals(plan.manifest().target().node())
                && port.direction().equals("input") && port.medium().equals(plan.manifest().target().resource().medium()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("production_sink_has_no_declared_input"));
        processing = new ProductionProcessProgress(plan);
        Set<BlockPos> positions = new java.util.LinkedHashSet<>(paths.observationPositions());
        plan.manifest().nodes().stream().filter(node -> node.kind().equals("process")).forEach(node -> positions.add(plan.at(node)));
        groups = group(positions); cursor = new ProductionEventCursor(groups.size());
        for (String id : paths.producers) {
            var node = plan.node(id);
            recipes.computeIfAbsent(ProductionFlowPaths.key(plan.at(node)), ignored -> new java.util.LinkedHashSet<>()).add(node.recipeId());
        }
        if (recipes.isEmpty()) unknown = "no_native_target_producer_declared";
    }

    boolean baseline() {
        try {
            if (releasedWatches) throw new IllegalStateException("production_watch_released");
            if (window != null) return true;
            if (initialStock == null) {
                if (!readStock()) return false;
                initialStock = stock; sinkBaselineTick = serverTick; return false;
            }
            List<BlockPos> group = groups.get(baselineGroup);
            if (!work.observe(group.getFirst())) return false;
            JsonObject body = eventBody(group);
            if (baselineGroup == 0) body.addProperty("baseline", true);
            requestedWatches.add(baselineGroup);
            JsonObject result = work.request("machine.production_events", body, false);
            if (result == null) return false;
            requireRetention(result);
            if (baselineGroup == 0) {
                cursor.baseline(result);
                if (!cursor.scope().startsWith(plan.dimension() + ":")) throw new IllegalStateException("production_journal_wrong_world");
                if (cursor.tick() < serverTick) throw new IllegalStateException("production_baseline_clock_changed");
                baselineTick = cursor.tick(); baselineSequence = cursor.sequence();
            } else cursor.baselineGroup(result);
            serverTick = Math.max(serverTick, ProductionEventCursor.number(result, "tick"));
            if (++baselineGroup < groups.size()) return false;
            processing.bind(cursor.scope());
            flow.baseline(cursor.scope(), baselineSequence, baselineTick);
            var require = plan.manifest().observation();
            window = new ProductionEvidenceWindow(cursor.scope(), recipes.keySet(), new ProductionEvidenceWindow.Requirement(
                    resourceKey(), BigDecimal.valueOf(require.minimumOutput()), require.minimumEvents(), require.windowTicks(), require.maxIdleTicks()));
            work.extendDeadlineTo(Math.addExact(serverTick, require.windowTicks() + require.maxIdleTicks() + 1200));
            return true;
        } catch (RuntimeException invalid) { throw fail(invalid); }
    }

    boolean tick() {
        roundBoundary = false; completedRound = false;
        try {
            if (releasedWatches) throw new IllegalStateException("production_watch_released");
            if (window == null) throw new IllegalStateException("production_baseline_required");
            if (readSink) {
                if (!readStock()) return false;
                readSink = false; roundBoundary = true; completedRound = true;
                if (observationStartedTick < 0) observationStartedTick = cursor.tick();
                return unknown == null && caughtUp && window.hasVerifiedRun()
                        && window.status(serverTick) != ProductionEvidenceWindow.Status.INVALIDATED && flow.verified(processingProgress());
            }
            List<BlockPos> group = groups.get(groupIndex);
            if (!work.observe(group.getFirst())) return false;
            JsonObject result = work.request("machine.production_events", eventBody(group), false);
            if (result == null) return false;
            ProductionEventCursor.Batch batch = cursor.page(groupIndex, result);
            groupIndex++;
            if (batch != null) {
                groupIndex = 0; serverTick = Math.max(serverTick, batch.tick()); caughtUp = batch.complete();
                for (JsonObject event : batch.events()) accept(event);
                readSink = true;
            }
            return false;
        } catch (RuntimeException invalid) { throw fail(invalid); }
    }

    /** Consume immediately after tick: navigation and pending reads are never refill handoff points. */
    boolean consumeRoundBoundary() {
        boolean completed = roundBoundary; roundBoundary = false; return completed;
    }

    Map<String, Long> processingProgress() { return processing.snapshot(); }

    /** -1 outside a completed, caught-up observation round; setup time does not count as a process stall. */
    long processingIdleTicks() {
        if (!completedRound || !caughtUp || releasedWatches || observationStartedTick < 0) return -1;
        return Math.max(0, cursor.tick() - Math.max(observationStartedTick, processing.latestTick()));
    }
    long latestProcessingTick() { return processing.latestTick(); }
    long observedServerTick() { return serverTick; }
    long processingCoverageTick() { return cursor.tick(); }
    long observationStartedTick() { return observationStartedTick; }
    long lastAttributedDeliveryTick() { return flow.latestDeliveryTick(); }
    boolean productionWindowVerified() {
        return unknown == null && window != null && window.hasVerifiedRun()
                && window.status(serverTick) != ProductionEvidenceWindow.Status.INVALIDATED;
    }

    /** Root submits these read-only metadata requests with owner=null after cancelling its active request. */
    List<JsonObject> releaseBodies() {
        if (releasedWatches) return List.of();
        releasedWatches = true;
        List<JsonObject> releases = new ArrayList<>();
        for (int index : requestedWatches) {
            JsonObject body = eventBody(groups.get(index)); body.remove("after_sequence");
            body.addProperty("release_watch", true); releases.add(body);
        }
        return List.copyOf(releases);
    }

    private static void requireRetention(JsonObject page) {
        if (!page.has("retention") || !page.get("retention").isJsonObject()
                || !page.getAsJsonObject("retention").has("retained")
                || !page.getAsJsonObject("retention").get("retained").getAsBoolean())
            throw new IllegalStateException("production_watch_not_retained");
    }

    private void accept(JsonObject event) {
        processing.accept(event);
        String kind = ProductionFlowPaths.text(event, "kind");
        if (kind.equals("resource_transferred")) { flow.accept(event); return; }
        String producer = ProductionFlowPaths.text(event, "producer");
        if (!kind.equals("recipe_output") || !recipes.containsKey(producer)
                || !ProductionFlowPaths.text(event, "provenance").equals("native_recipe_output")) return;
        if (!recipes.get(producer).contains(ProductionFlowPaths.text(event, "recipe_id"))) {
            throw new IllegalStateException("production_producer_recipe_changed");
        }
        BigDecimal amount = BigDecimal.ZERO;
        for (var raw : event.getAsJsonArray("outputs")) {
            JsonObject output = raw.getAsJsonObject();
            if (!ProductionFlowPaths.matches(plan.manifest().target().resource(), output)) continue;
            BigDecimal count = output.get("amount").getAsBigDecimal();
            if (count.signum() <= 0) throw new IllegalStateException("production_invalid_native_output_amount");
            amount = amount.add(count); flow.produced(output.get("resource_id").getAsString(), count);
        }
        if (amount.signum() > 0) window.accept(new ProductionEvidenceWindow.Event(cursor.scope(),
                ProductionEventCursor.number(event, "sequence"), producer, resourceKey(), amount,
                ProductionEventCursor.number(event, "tick"), ProductionEvidenceWindow.Provenance.NATIVE_RECIPE_OUTPUT));
    }

    private boolean readStock() {
        BlockPos position = plan.at(sink.offset());
        if (!work.observe(position)) return false;
        JsonObject body = plan.snapshotBody(position); JsonArray faces = new JsonArray(); faces.add(sink.face()); body.add("faces", faces);
        JsonObject result = work.request("machine.snapshot", body, false);
        if (result == null) return false;
        if (!plan.dimension().equals(ProductionFlowPaths.text(result, "dimension"))) throw new IllegalStateException("production_sink_wrong_world");
        stock = ProductionFlowStock.read(result, position, sink.face(), plan.manifest().target().resource());
        serverTick = Math.max(serverTick, ProductionEventCursor.number(result, "tick"));
        return true;
    }

    private JsonObject eventBody(List<BlockPos> group) {
        JsonObject body = new JsonObject(); JsonArray positions = new JsonArray();
        group.forEach(position -> positions.add(ProductionRunPlan.position(position))); body.add("positions", positions);
        if (cursor.scope() != null) { body.addProperty("scope", cursor.scope()); body.addProperty("after_sequence", cursor.sequence()); }
        return body;
    }

    private String resourceKey() { return plan.manifest().target().resource().medium() + ":" + plan.manifest().target().resource().id(); }

    private IllegalStateException fail(RuntimeException error) {
        unknown = error.getMessage() == null ? "native_observation_unreadable" : error.getMessage();
        if (window != null) window.invalidate(unknown);
        return new IllegalStateException(unknown, error);
    }

    Map<String, Object> report() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("production", window == null ? Map.of("status", "baselining") : window.report(serverTick));
        result.put("flow", flow.report(processingProgress())); result.put("journal_cursor", cursor.sequence()); result.put("observation_groups", groups.size());
        result.put("process_completed_events", processingProgress());
        result.put("latest_processing_tick", latestProcessingTick()); result.put("processing_coverage_tick", processingCoverageTick());
        result.put("observation_started_tick", observationStartedTick); result.put("last_attributed_delivery_tick", lastAttributedDeliveryTick());
        result.put("journal_caught_up", caughtUp); result.put("observed_server_tick", serverTick);
        result.put("journal_baseline_sequence", baselineSequence); result.put("baseline_server_tick", baselineTick);
        result.put("sink_baseline_tick", sinkBaselineTick);
        result.put("retained_baseline_groups", baselineGroup); result.put("watch_release_requested", releasedWatches);
        result.put("sink_view", Map.of("position", ProductionFlowPaths.key(plan.at(sink.offset())), "side", sink.face()));
        if (initialStock != null && stock != null) {
            result.put("sink_initial_by_identity", initialStock); result.put("sink_current_by_identity", stock);
            result.put("sink_net_growth", ProductionFlowStock.total(stock).subtract(ProductionFlowStock.total(initialStock)));
            result.put("sink_external_change_attribution", "unknown; stock growth alone is neither production nor delivery proof");
        }
        if (unknown != null) result.put("unknown_reason", unknown);
        else if (window != null && window.status(serverTick) == ProductionEvidenceWindow.Status.AWAITING_EVIDENCE)
            result.put("unknown_reason", "native_production_events_missing_or_unsupported");
        return result;
    }

    static List<List<BlockPos>> group(Set<BlockPos> positions) {
        List<List<BlockPos>> groups = new ArrayList<>();
        for (BlockPos position : positions) {
            List<BlockPos> group = groups.stream().filter(values -> values.size() < 4
                    && values.getFirst().distSqr(position) <= 16).findFirst().orElse(null);
            // Work.observe reaches within 12 blocks of the first point; the others are at most 4 farther.
            if (group == null) { group = new ArrayList<>(); groups.add(group); }
            group.add(position.immutable());
        }
        return groups.stream().map(List::copyOf).collect(Collectors.toUnmodifiableList());
    }
}
