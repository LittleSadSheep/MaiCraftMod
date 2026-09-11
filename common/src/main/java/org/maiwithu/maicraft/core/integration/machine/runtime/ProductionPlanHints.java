// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.*;

/** Immutable compiler hints checked against the resolved source budgets and their immediate recipe consumers. */
final class ProductionPlanHints {
    private record Key(String source, Resource resource) {}
    private record Hint(long amount, long firstBatch, Set<String> consumers) {}
    private final Map<Key, Hint> hints = new LinkedHashMap<>();

    ProductionPlanHints(ProductionManifest manifest, ProductionPlanBindings bindings, JsonObject report) {
        Map<String, Node> nodes = new LinkedHashMap<>(); manifest.nodes().forEach(node -> nodes.put(node.id(), node));
        Map<String, Port> ports = new LinkedHashMap<>(); manifest.ports().forEach(port -> ports.put(port.id(), port));
        Map<String, List<Link>> outgoing = new LinkedHashMap<>();
        Map<Key, Long> budgets = new LinkedHashMap<>();
        for (Link link : manifest.links()) {
            String source = ports.get(link.from()).node();
            outgoing.computeIfAbsent(source, ignored -> new java.util.ArrayList<>()).add(link);
            if (!nodes.get(source).kind().equals("source")) continue;
            budgets.merge(new Key(source, link.resource()), link.amount(), link.resource().medium().equals("kinetic") ? Math::max : Math::addExact);
        }
        if (!report.has("supplies") || !report.get("supplies").isJsonArray()) throw invalid("missing_supplies");
        for (var raw : report.getAsJsonArray("supplies")) {
            JsonObject row = raw.getAsJsonObject();
            Key key = new Key(text(row, "node"), bindings.resolve(new Resource(text(row, "medium"), text(row, "resource"))));
            long amount = number(row, "amount"), first = number(row, "first_batch");
            if (!budgets.containsKey(key) || amount != budgets.get(key) || first < 1 || first > amount) throw invalid("source_budget_mismatch");
            if (!row.has("pacing_consumers") || !row.get("pacing_consumers").isJsonArray()) throw invalid("missing_consumers");
            Set<String> consumers = new LinkedHashSet<>();
            for (var consumer : row.getAsJsonArray("pacing_consumers")) {
                if (!consumer.isJsonPrimitive() || !consumer.getAsJsonPrimitive().isString()
                        || !consumers.add(consumer.getAsString())) throw invalid("invalid_consumer");
            }
            Set<String> expected = consumers(key, outgoing, nodes, ports);
            if (!expected.equals(consumers)) throw invalid("consumer_topology_mismatch");
            if (hints.putIfAbsent(key, new Hint(amount, first, Set.copyOf(consumers))) != null) throw invalid("duplicate_supply");
        }
        if (!hints.keySet().equals(budgets.keySet())) throw invalid("missing_source_hint");
    }

    long batch(String source, Resource resource) { return hint(source, resource).firstBatch(); }
    Set<String> consumers(String source, Resource resource) { return hint(source, resource).consumers(); }
    boolean same(ProductionPlanHints other) { return hints.equals(other.hints); }

    private Hint hint(String source, Resource resource) {
        Hint hint = hints.get(new Key(source, resource));
        if (hint == null) throw invalid("source_not_declared");
        return hint;
    }

    private static Set<String> consumers(Key key, Map<String, List<Link>> outgoing, Map<String, Node> nodes, Map<String, Port> ports) {
        Set<String> result = new LinkedHashSet<>(), seen = new LinkedHashSet<>();
        ArrayDeque<String> next = new ArrayDeque<>(); next.add(key.source());
        while (!next.isEmpty()) {
            String id = next.removeFirst(); if (!seen.add(id)) continue;
            if (nodes.get(id).kind().equals("process")) { result.add(id); continue; }
            if (!id.equals(key.source()) && !nodes.get(id).kind().equals("transport")) continue;
            for (Link link : outgoing.getOrDefault(id, List.of())) if (link.resource().equals(key.resource()))
                next.addLast(ports.get(link.to()).node());
        }
        return result;
    }

    private static long number(JsonObject row, String key) {
        if (!row.has(key) || !row.get(key).isJsonPrimitive() || !row.getAsJsonPrimitive(key).isNumber()) throw invalid("invalid_" + key);
        try { return row.get(key).getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException malformed) { throw invalid("invalid_" + key); }
    }
    private static String text(JsonObject row, String key) {
        if (!row.has(key) || !row.get(key).isJsonPrimitive() || !row.getAsJsonPrimitive(key).isString()
                || row.get(key).getAsString().isBlank()) throw invalid("invalid_" + key);
        return row.get(key).getAsString();
    }
    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException("production_supply_hint_" + code); }
}
