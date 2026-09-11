// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.*;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionStageReadiness;

/** Frozen authored production intent anchored to the exact reviewed build, never to the player's later position. */
public final class ProductionRunPlan {
    private final BlockPos anchor;
    private final String dimension;
    private final String json;
    private final ProductionManifest manifest;
    private final Map<String, Node> nodes;
    private final Map<String, Port> ports;
    private record Bound(String json, ProductionManifest manifest, Map<String, Node> nodes, ProductionPlanBindings bindings,
                         ProductionPlanHints hints) {}
    private volatile Bound bound;

    public ProductionRunPlan(BlockPos anchor, String dimension, JsonObject manifest) {
        this.anchor = anchor.immutable(); this.dimension = java.util.Objects.requireNonNull(dimension);
        this.json = manifest.toString(); this.manifest = ProductionManifest.parse(manifest);
        var nodes = new LinkedHashMap<String, Node>(); this.manifest.nodes().forEach(n -> nodes.put(n.id(), n));
        var ports = new LinkedHashMap<String, Port>(); this.manifest.ports().forEach(p -> ports.put(p.id(), p));
        this.nodes = Map.copyOf(nodes); this.ports = Map.copyOf(ports);
    }

    /** Accept only a compiler report admitted to supply; authored geometry and budgets remain immutable. */
    public synchronized void bindResolved(JsonObject compilationReport) {
        if (!ProductionStageReadiness.canEnter(compilationReport, "supply"))
            throw new IllegalArgumentException("production_binding_not_ready_for_supply");
        if (!compilationReport.has("resolved_manifest") || !compilationReport.get("resolved_manifest").isJsonObject())
            throw new IllegalArgumentException("production_binding_missing_resolved_manifest");
        JsonObject resolvedJson = compilationReport.getAsJsonObject("resolved_manifest").deepCopy();
        ProductionManifest resolved = ProductionManifest.parse(resolvedJson);
        if (bound != null && !json().equals(resolvedJson)) throw new IllegalStateException("production_binding_already_frozen");
        ProductionPlanBindings bindings = new ProductionPlanBindings(manifest, compilationReport, bound == null ? null : bound.bindings());
        bindings.verifyManifest(authoredJson(), manifest, resolvedJson, resolved, compilationReport, bound != null);
        ProductionPlanHints hints = new ProductionPlanHints(resolved, bindings, compilationReport);
        if (bound != null) {
            if (!json().equals(resolvedJson) || !bound.bindings().same(bindings) || !bound.hints().same(hints))
                throw new IllegalStateException("production_binding_already_frozen");
            return;
        }
        Map<String, Node> resolvedNodes = new LinkedHashMap<>(); resolved.nodes().forEach(node -> resolvedNodes.put(node.id(), node));
        bound = new Bound(resolvedJson.toString(), resolved, Map.copyOf(resolvedNodes), bindings, hints);
    }

    public boolean bound() { return bound != null; }
    public ProductionManifest manifest() { Bound state = bound; return state == null ? manifest : state.manifest(); }
    public JsonObject json() { Bound state = bound; return com.google.gson.JsonParser.parseString(state == null ? json : state.json()).getAsJsonObject(); }
    public JsonObject authoredJson() { return com.google.gson.JsonParser.parseString(json).getAsJsonObject(); }
    public Resource resolvedResource(Resource selector) { Bound state = bound; return state == null ? selector : state.bindings().resolve(selector); }
    public String registryId(Resource selector) { return bindings().registryId(selector); }
    /** Null is reserved for the non-inventory kinetic RPM unit. */
    public JsonObject resourceIdentity(Resource selector) { return bindings().identity(selector); }
    public long supplyBatch(String sourceId, Resource selector) { return hints().batch(sourceId, resolvedResource(selector)); }
    public Set<String> supplyConsumers(String sourceId, Resource selector) { return hints().consumers(sourceId, resolvedResource(selector)); }
    private ProductionPlanHints hints() {
        Bound state = bound;
        if (state == null) throw new IllegalStateException("production_supply_hints_not_bound");
        return state.hints();
    }
    private ProductionPlanBindings bindings() {
        Bound state = bound;
        if (state == null) throw new IllegalStateException("production_native_identity_not_bound");
        return state.bindings();
    }
    public String dimension() { return dimension; }
    public BlockPos anchor() { return anchor; }
    public Node node(String id) { Bound state = bound; return java.util.Objects.requireNonNull((state == null ? nodes : state.nodes()).get(id), "Unknown production node " + id); }
    public Port port(String id) { return java.util.Objects.requireNonNull(ports.get(id), "Unknown production port " + id); }
    public BlockPos at(Point offset) { return new BlockPos(Math.addExact(anchor.getX(), offset.x()),
            Math.addExact(anchor.getY(), offset.y()), Math.addExact(anchor.getZ(), offset.z())); }
    public BlockPos at(Node node) { return at(node.offset()); }

    public List<BlockPos> positions() {
        Set<BlockPos> positions = new LinkedHashSet<>();
        manifest.nodes().forEach(node -> positions.add(at(node)));
        manifest.ports().forEach(port -> positions.add(at(port.offset())));
        manifest.links().forEach(link -> link.path().forEach(point -> positions.add(at(point))));
        return List.copyOf(positions);
    }

    public JsonObject snapshotBody(BlockPos position) {
        var body = new JsonObject(); var positions = new JsonArray(); positions.add(position(position));
        body.add("positions", positions); body.addProperty("resource_limit", 128);
        return body;
    }

    public JsonObject configurationBody(Configuration configuration) {
        JsonObject body = configuration.arguments(); body.add("position", position(at(node(configuration.node()))));
        return body;
    }

    public static JsonObject position(BlockPos position) {
        var json = new JsonObject(); json.addProperty("x", position.getX());
        json.addProperty("y", position.getY()); json.addProperty("z", position.getZ()); return json;
    }
}
