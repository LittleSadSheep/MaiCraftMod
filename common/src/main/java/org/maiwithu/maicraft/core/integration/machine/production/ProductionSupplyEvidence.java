// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import static org.maiwithu.maicraft.core.integration.machine.production.ProductionNativeJson.*;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.*;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence.*;

/** Current stock and confirmed window injections remain separate; aliased sided views are never added together. */
final class ProductionSupplyEvidence {
    private record Injection(String link, String resource, long amount) {}
    private record Stock(long amount, String membership) {}
    private final ProductionGraph graph;
    private final Function<Resource,Resource> resolve;
    private final Map<String, Injection> receipts = new LinkedHashMap<>();
    private final Map<String, Long> delivered = new LinkedHashMap<>();
    private final Map<String,Long> initial = new LinkedHashMap<>(), initialByAlias = new LinkedHashMap<>();
    ProductionSupplyEvidence(ProductionManifest manifest) { this(manifest,r -> r); }
    ProductionSupplyEvidence(ProductionManifest manifest, Function<Resource,Resource> resolve) { graph = new ProductionGraph(manifest); this.resolve = resolve; }
    void clear() { receipts.clear(); delivered.clear(); initial.clear(); initialByAlias.clear(); }

    void initial(Node source, Resource resource, long credited, Map<String,JsonObject> observations) {
        Stock stock = stock(observations.get(source.id()),resource); String key = source.id()+"\n"+resource;
        if (initial.containsKey(key)) { if (initial.get(key) != credited) throw new IllegalArgumentException("Initial source credit cannot be revised"); return; }
        long budget = graph.outgoing.get(source.id()).stream().filter(l -> resolve.apply(l.resource()).equals(resource)).mapToLong(Link::amount).reduce(0,Math::addExact);
        String alias = (stock.membership == null ? source.id() : stock.membership)+"\n"+resource;
        long shared = Math.addExact(initialByAlias.getOrDefault(alias,0L),credited);
        if (credited < 0 || credited > budget || shared > stock.amount) throw new IllegalArgumentException("Initial source credit exceeds actual stock or shared storage allocation");
        initial.put(key,credited); initialByAlias.put(alias,shared);
    }

    /** Caller already binds a successful native DEPOSIT response to its request/player/world and exact link ingress. */
    void confirmed(String linkId, String requestId, JsonObject result) {
        Link link = graph.outgoing.values().stream().flatMap(java.util.Collection::stream).filter(l -> l.id().equals(linkId)).findFirst().orElseThrow();
        confirmedSource(graph.nodes.get(graph.ports.get(link.from()).node()),resolve.apply(link.resource()),requestId,result);
    }
    void confirmedSource(Node source, Resource resource, String requestId, JsonObject result) {
        if (!source.kind().equals("source")) throw new IllegalArgumentException("Only external source admission is credited");
        Long amount = number(result,"transferred"), requested = number(result,"requested");
        if (requestId == null || requestId.isBlank() || amount == null || requested == null || amount < 0 || amount > requested
                || !("applied".equals(text(result,"status")) || "partial".equals(text(result,"status")) || "no_change".equals(text(result,"status"))) || !resource.id().equals(text(result,"resource_id")))
            throw new IllegalArgumentException("Supply result lacks a confirmed exact-resource transfer");
        String key = source.id()+"\n"+resource;
        Injection injection = new Injection(source.id(),resource.id(),amount);
        Injection previous = receipts.get(requestId);
        if (previous != null) { if (!previous.equals(injection)) throw new IllegalArgumentException("Supply receipt identity reused with different effects"); return; }
        long total = Math.addExact(delivered.getOrDefault(key,0L),amount);
        long budget = graph.outgoing.get(source.id()).stream().filter(l -> resolve.apply(l.resource()).equals(resource)).mapToLong(Link::amount).reduce(0,Math::addExact);
        if (Math.addExact(total,initial.getOrDefault(key,0L)) > budget) throw new IllegalArgumentException("Confirmed injection exceeds this window's declared source budget");
        receipts.put(requestId,injection); delivered.put(key,total);
    }
    Check inspect(Node source, Resource resource, long amount, Map<String,JsonObject> observations, Map<String,Recipe> recipes) {
        Stock stock = stock(observations.get(source.id()), resource);
        long reserved = 0, injected = 0, firstBatch = 0, credited = 0;
        for (Node node : graph.nodes.values()) {
            if (!node.kind().equals("source")) continue;
            Stock other = stock(observations.get(node.id()), resource);
            boolean same = node.id().equals(source.id()) || stock.membership != null && stock.membership.equals(other.membership);
            if (!same) continue;
            credited = Math.addExact(credited,initial.getOrDefault(node.id()+"\n"+resource,0L));
            injected = Math.addExact(injected,delivered.getOrDefault(node.id()+"\n"+resource,0L));
            for (Link link : graph.outgoing.get(node.id())) if (resolve.apply(link.resource()).equals(resource)) {
                reserved = Math.addExact(reserved,link.amount());
                firstBatch = Math.addExact(firstBatch,ProductionRefill.firstBatch(link,graph,recipes,resolve));
            }
        }
        reserved = Math.max(amount,reserved);
        String detail = "current_stock_lower_bound=" + stock.amount + "; shared_window_budget=" + reserved + "; confirmed_window_injection=" + injected
                + "; initial_stock_credited_once=" + credited + "; first_batch_requirement=" + firstBatch
                + "; remaining_injection_budget=" + Math.max(0,reserved-injected-credited) + "; admitted inputs are not future inventory";
        // Historical admission is bounded separately. Never add it to the current stock view.
        return new Check(Math.addExact(injected,credited) >= firstBatch || stock.amount >= firstBatch ? Status.VERIFIED : Status.PLANNED,
                "server_native_stock_and_confirmed_deposit_receipts",detail);
    }
    JsonObject report() {
        JsonObject result = new JsonObject(); var sources = new com.google.gson.JsonArray();
        for (Node node : graph.nodes.values()) if (node.kind().equals("source")) for (Resource resource : graph.outgoing.get(node.id()).stream().map(l -> resolve.apply(l.resource())).distinct().toList()) {
            String key = node.id()+"\n"+resource; JsonObject row = ProductionDesignCompiler.resource(resource); row.addProperty("source",node.id());
            row.addProperty("confirmed_injected",delivered.getOrDefault(key,0L)); row.addProperty("initial_stock_credited_once",initial.getOrDefault(key,0L)); sources.add(row);
        }
        result.add("sources",sources); result.addProperty("receipt_count",receipts.size()); result.addProperty("scope","input_admission_not_link_flow_or_future_inventory"); return result;
    }
    private static Stock stock(JsonObject observation, Resource resource) {
        Map<String,Map<String,Long>> views = new LinkedHashMap<>(); Map<String,String> memberships = new LinkedHashMap<>();
        for (var raw : array(observation,"resources")) {
            if (!raw.isJsonObject()) continue; JsonObject row = raw.getAsJsonObject();
            if (!resource.id().equals(text(row,"resource_id")) || !"server_native".equals(text(row,"provenance"))) continue;
            Long amount = number(row,"amount"); String storage = text(row,"storage_id"), membership = text(row,"membership"), side = text(row,"side");
            if (amount == null || amount < 0 || storage == null || membership == null || side == null) continue;
            String view = membership + "\n" + side;
            views.computeIfAbsent(view, ignored -> new LinkedHashMap<>()).merge(storage,amount,Math::max); memberships.put(view,membership);
        }
        long best = 0; String membership = null;
        for (var view : views.entrySet()) {
            long count = 0; for (long amount : view.getValue().values()) count = Math.addExact(count,amount);
            if (count > best || membership == null) { best = count; membership = memberships.get(view.getKey()); }
        }
        return new Stock(best,membership);
    }
}
