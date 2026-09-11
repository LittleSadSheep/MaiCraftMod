// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.*;

/** Validates explicit directed topology and path endpoints before querying any live capability adapter. */
final class ProductionGraph {
    final Map<String, Node> nodes = new LinkedHashMap<>();
    final Map<String, Port> ports = new LinkedHashMap<>();
    final Map<String, List<Link>> incoming = new HashMap<>(), outgoing = new HashMap<>();
    final List<String> order = new ArrayList<>();
    ProductionGraph(ProductionManifest manifest) {
        manifest.nodes().forEach(n -> { nodes.put(n.id(), n); incoming.put(n.id(), new ArrayList<>()); outgoing.put(n.id(), new ArrayList<>()); });
        manifest.ports().forEach(p -> ports.put(p.id(), p));
        for (Link link : manifest.links()) {
            Port from = ports.get(link.from()), to = ports.get(link.to());
            if (!from.direction().equals("output") || !to.direction().equals("input")) throw bad("Link " + link.id() + " must connect output to input");
            if (!from.medium().equals(link.resource().medium()) || !to.medium().equals(from.medium())) throw bad("Incompatible media on link " + link.id());
            if (from.node().equals(to.node())) throw bad("A self-link does not establish processing or transfer: " + link.id());
            if (nodes.get(from.node()).kind().equals("sink") || nodes.get(to.node()).kind().equals("source")) throw bad("Source/sink directions conflict on " + link.id());
            if (!link.path().isEmpty()) validatePath(link, from, to);
            incoming.get(to.node()).add(link); outgoing.get(from.node()).add(link);
        }
        for (Node node : nodes.values()) if (node.kind().equals("source")) {
            Map<Resource,Port> ingresses = new HashMap<>();
            for (Link link : outgoing.get(node.id())) if (material(link.resource())) {
                Port port = ports.get(link.from()), first = ingresses.putIfAbsent(link.resource(),port);
                if (first != null && (!first.offset().equals(port.offset()) || !first.face().equals(port.face())))
                    throw bad("Split source " + node.id() + " by native ingress face for " + link.resource() + "; shared storage budgets are reconciled by membership");
            }
        }
        if (!nodes.get(manifest.target().node()).kind().equals("sink")) throw bad("Production target must identify an output sink node");
        Map<String, Integer> dependencies = new LinkedHashMap<>(); nodes.keySet().forEach(id -> dependencies.put(id, 0));
        for (Link link : manifest.links()) if (material(link.resource())) dependencies.merge(ports.get(link.to()).node(), 1, Integer::sum);
        ArrayDeque<String> ready = new ArrayDeque<>(); dependencies.forEach((id, n) -> { if (n == 0) ready.add(id); });
        while (!ready.isEmpty()) {
            String id = ready.remove(); order.add(id);
            for (Link link : outgoing.get(id)) if (material(link.resource())) {
                String next = ports.get(link.to()).node(); if (dependencies.merge(next, -1, Integer::sum) == 0) ready.add(next);
            }
        }
        if (order.size() != nodes.size()) throw bad("Material feedback needs explicit finite recipe stages; cyclic manifests cannot claim a complete batch plan");
    }
    static boolean material(Resource resource) { return !resource.medium().equals("energy") && !resource.medium().equals("kinetic"); }
    static Map<Resource, Long> amounts(List<Link> links) {
        Map<Resource, Long> totals = new LinkedHashMap<>();
        for (Link link : links) totals.merge(link.resource(), link.amount(), link.resource().medium().equals("kinetic") ? Math::max : Math::addExact);
        return totals;
    }
    private static void validatePath(Link link, Port from, Port to) {
        List<Point> path = link.path();
        if (!path.getFirst().equals(from.offset()) || !path.getLast().equals(to.offset())
                || !path.get(1).equals(from.offset().step(from.face())) || !path.get(path.size()-2).equals(to.offset().step(to.face())))
            throw bad("Path must enter and leave declared port faces: " + link.id());
        var seen = new HashSet<Point>();
        for (int i = 0; i < path.size(); i++) {
            if (!seen.add(path.get(i))) throw bad("Self-intersecting path on " + link.id());
            if (i > 0 && path.get(i-1).distance(path.get(i)) != 1) throw bad("Path has a gap or diagonal on " + link.id());
        }
    }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
