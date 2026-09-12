// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionNativeEvidence;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionNativeEvidence.ObservationFreshness;

/** Finite read jobs grouped by position; a chosen request remains frozen until its original receipt settles. */
final class ProductionReadSchedule {
    record Read(String operation, BlockPos position, JsonObject body, List<String> nodes, List<String> ports, String subject, List<BlockPos> positions) {
        Read(String operation, BlockPos position, JsonObject body, List<String> nodes, List<String> ports, String subject) {
            this(operation, position, body, nodes, ports, subject, List.of(position));
        }
        Read {
            position = position.immutable(); body = body.deepCopy(); nodes = List.copyOf(nodes); ports = List.copyOf(ports);
            positions = positions.stream().map(BlockPos::immutable).toList();
        }
        @Override public JsonObject body() { return body.deepCopy(); }
        void apply(ProductionNativeEvidence evidence, JsonObject result) {
            if (operation.equals("machine.snapshot") && truncated(result)) throw new IllegalArgumentException("production_snapshot_truncated");
            nodes.forEach(id -> evidence.observeNode(id, result)); ports.forEach(id -> evidence.observePort(id, result));
            if (operation.equals("machine.recipe")) evidence.observeRecipe(subject, result);
            if (operation.equals("machine.configuration")) evidence.observeConfiguration(subject, result);
        }
        List<String> factKeys() {
            var keys = new ArrayList<String>(); nodes.forEach(id -> keys.add("NODE\n" + id)); ports.forEach(id -> keys.add("PORT\n" + id));
            if (operation.equals("machine.recipe")) keys.add("RECIPE\n" + subject);
            if (operation.equals("machine.configuration")) keys.add("CONFIGURATION\n" + subject);
            return keys;
        }
        boolean affects(ObservationFreshness fact) {
            return switch (fact.kind()) {
                case NODE -> nodes.contains(fact.id());
                case PORT -> ports.contains(fact.id());
                case RECIPE -> operation.equals("machine.recipe") && subject.equals(fact.id());
                case CONFIGURATION -> operation.equals("machine.configuration") && subject.equals(fact.id());
                case LINK -> false;
            };
        }
    }
    private static final class Snapshot {
        final List<String> nodes = new ArrayList<>(), ports = new ArrayList<>();
        final java.util.Set<String> faces = new LinkedHashSet<>();
    }
    private final List<Read> remaining;
    private int total;
    private final List<Read> all;
    private final ToDoubleFunction<BlockPos> nativeRadius;
    private final Map<Read, List<Read>> batches = new LinkedHashMap<>();
    private final java.util.Set<String> refreshedFacts = new java.util.HashSet<>();
    private Read active;
    private BlockPos lastPosition;
    private boolean pending;
    private boolean batched;
    private int refreshes, batchSplits;
    private int completed, nodes, ports, recipes, configurations;

    ProductionReadSchedule(ProductionRunPlan plan) {
        this(plan, ignored -> ProductionObservationRange.CREATE_RADIUS);
    }
    ProductionReadSchedule(ProductionRunPlan plan, ToDoubleFunction<BlockPos> nativeRadius) {
        this.nativeRadius = java.util.Objects.requireNonNull(nativeRadius);
        var sites = new LinkedHashMap<BlockPos, Snapshot>();
        for (var node : plan.manifest().nodes()) sites.computeIfAbsent(plan.at(node), ignored -> new Snapshot()).nodes.add(node.id());
        for (var port : plan.manifest().ports()) {
            Snapshot site = sites.computeIfAbsent(plan.at(port.offset()), ignored -> new Snapshot());
            site.ports.add(port.id()); site.faces.add(port.face());
        }
        remaining = new ArrayList<>();
        sites.forEach((position, site) -> {
            JsonObject body = plan.snapshotBody(position); JsonArray faces = new JsonArray(); site.faces.forEach(faces::add); body.add("faces", faces);
            remaining.add(new Read("machine.snapshot", position, body, site.nodes, site.ports, ""));
        });
        for (var node : plan.manifest().nodes()) if (node.kind().equals("process")) {
            JsonObject body = new JsonObject(); body.addProperty("recipe_id", node.recipeId());
            body.add("position", ProductionRunPlan.position(plan.at(node)));
            remaining.add(new Read("machine.recipe", plan.at(node), body, List.of(), List.of(), node.id()));
        }
        for (var configuration : plan.manifest().configurations())
            remaining.add(new Read("machine.configuration", plan.at(plan.node(configuration.node())), plan.configurationBody(configuration),
                    List.of(), List.of(), configuration.id()));
        total = remaining.size();
        all = new ArrayList<>(remaining);
    }

    Read next(Vec3 feet) {
        if (!batched) batchSnapshots();
        if (active != null) return active;
        active = remaining.stream().filter(read -> read.position().equals(lastPosition)).findFirst().orElse(null);
        if (active == null) active = remaining.stream().min(Comparator.comparingDouble(read -> feet.distanceToSqr(read.position().getCenter()))).orElse(null);
        return active;
    }
    boolean pending() { return pending; }
    void submitted() {
        if (active == null) throw new IllegalStateException("production_read_missing_active_job");
        pending = true;
    }
    void complete(ProductionNativeEvidence evidence, JsonObject result) {
        if (!pending || active == null) throw new IllegalStateException("production_read_without_pending_request");
        if (truncated(result) && batches.containsKey(active)) {
            // The 128-row/resource and envelope budgets are shared. Re-read each original site once; never relabel partial rows as complete.
            var singles = batches.remove(active); remaining.remove(active); remaining.addAll(singles);
            all.remove(active); all.addAll(singles); total += singles.size(); batchSplits++;
            lastPosition = active.position(); active = null; pending = false; completed++; return;
        }
        active.apply(evidence, result);
        nodes += active.nodes().size(); ports += active.ports().size();
        if (active.operation().equals("machine.recipe")) recipes++;
        if (active.operation().equals("machine.configuration")) configurations++;
        lastPosition = active.position(); remaining.remove(active); active = null; pending = false; completed++;
    }
    void requireSettled() {
        if (pending) throw new IllegalStateException("production_read_refresh_before_receipt_consumed");
    }
    boolean refresh(ObservationFreshness fact) {
        requireSettled();
        if (fact.status() != ProductionNativeEvidence.FreshnessStatus.EXPIRED
                && fact.status() != ProductionNativeEvidence.FreshnessStatus.INVALIDATED) return false;
        Read read = all.stream().filter(value -> value.affects(fact)).findFirst().orElse(null);
        if (read == null || remaining.contains(read) || read.factKeys().stream().anyMatch(refreshedFacts::contains)) return false;
        // Every co-located fact shares this one refresh allowance, even if only one had expired.
        if (remaining.isEmpty()) lastPosition = null;
        refreshedFacts.addAll(read.factKeys()); refreshes++; remaining.add(read); return true;
    }
    Map<String, Object> progress() {
        var result = new LinkedHashMap<String, Object>();
        result.put("nodes", nodes); result.put("ports", ports); result.put("recipes", recipes); result.put("configurations", configurations);
        result.put("read_requests_completed", completed); result.put("read_requests_total", total + refreshes); result.put("read_request_pending", pending);
        result.put("read_refreshes_used", refreshes); result.put("read_refreshes_remaining", all.stream().filter(read -> read.factKeys().stream().noneMatch(refreshedFacts::contains)).count());
        result.put("snapshot_batch_splits", batchSplits);
        if (active != null) result.put("read_position_count", active.positions().size());
        if (active != null) { result.put("read_operation", active.operation()); if (!active.subject().isEmpty()) result.put("read_subject", active.subject()); }
        result.put("read_order", "nearby_positions_with_shared_snapshots");
        return Map.copyOf(result);
    }

    private void batchSnapshots() {
        // This runs at the first PREPARE read, after construction/configuration, not while the planned cells may still be air.
        var singles = new ArrayList<>(remaining.stream().filter(read -> read.operation().equals("machine.snapshot")).toList());
        var grouped = new ArrayList<Read>();
        while (!singles.isEmpty()) {
            Read first = singles.removeFirst(); var group = new ArrayList<Read>(); group.add(first);
            for (Read candidate : singles.stream().sorted(Comparator.comparingDouble(read -> first.position().distSqr(read.position()))).toList()) {
                if (group.size() == 4) break;
                var positions = new ArrayList<>(group.stream().map(Read::position).toList()); positions.add(candidate.position());
                try { if (ProductionObservationRange.goalRadius(positions, nativeRadius) < 1) continue; }
                catch (IllegalArgumentException incompatible) { continue; }
                group.add(candidate); singles.remove(candidate);
            }
            if (group.size() == 1) { grouped.add(first); continue; }
            JsonObject body = first.body(); JsonArray positions = new JsonArray(), faces = new JsonArray(); var union = new LinkedHashSet<String>();
            group.forEach(read -> { positions.add(ProductionRunPlan.position(read.position())); read.body().getAsJsonArray("faces").forEach(face -> union.add(face.getAsString())); });
            union.forEach(faces::add); body.add("positions", positions); body.add("faces", faces);
            Read batch = new Read(first.operation(), first.position(), body, group.stream().flatMap(read -> read.nodes().stream()).toList(),
                    group.stream().flatMap(read -> read.ports().stream()).toList(), "", group.stream().map(Read::position).toList());
            batches.put(batch, List.copyOf(group)); grouped.add(batch);
        }
        grouped.addAll(remaining.stream().filter(read -> !read.operation().equals("machine.snapshot")).toList());
        remaining.clear(); remaining.addAll(grouped); all.clear(); all.addAll(grouped); total = remaining.size(); batched = true;
    }

    private static boolean truncated(JsonObject result) {
        return result.has("truncated") && result.get("truncated").isJsonPrimitive()
                && result.getAsJsonPrimitive("truncated").isBoolean() && result.get("truncated").getAsBoolean();
    }
}
