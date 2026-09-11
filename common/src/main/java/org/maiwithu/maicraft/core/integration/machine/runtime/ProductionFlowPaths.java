// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.*;

/** Endpoint attribution can cover one unique declared chain; it never asserts the route actually traversed. */
final class ProductionFlowPaths {
    private final ProductionRunPlan plan;
    final List<Link> links;
    final Set<String> producers = new LinkedHashSet<>();
    private final Map<String, Set<String>> positions = new LinkedHashMap<>();
    private final Map<String, List<Link>> outgoing = new LinkedHashMap<>();
    private int searches;

    ProductionFlowPaths(ProductionRunPlan plan) {
        this.plan = plan;
        Resource target = plan.manifest().target().resource();
        Set<String> reachable = new HashSet<>(); reachable.add(plan.manifest().target().node());
        boolean changed;
        do {
            changed = false;
            for (Link link : plan.manifest().links()) {
                String from = plan.port(link.from()).node(), to = plan.port(link.to()).node();
                if (link.resource().equals(target) && reachable.contains(to)
                        && !plan.node(to).kind().equals("process")) changed |= reachable.add(from);
            }
        } while (changed);
        links = plan.manifest().links().stream().filter(link -> link.resource().equals(target)
                && reachable.contains(plan.port(link.from()).node()) && reachable.contains(plan.port(link.to()).node())).toList();
        for (Node node : plan.manifest().nodes()) {
            if (!reachable.contains(node.id())) continue;
            addPosition(plan.at(node), node.id());
            if (node.kind().equals("process")) producers.add(node.id());
        }
        for (Link link : links) {
            Port from = plan.port(link.from()), to = plan.port(link.to());
            addPosition(plan.at(from.offset()), from.node()); addPosition(plan.at(to.offset()), to.node());
            outgoing.computeIfAbsent(from.node(), ignored -> new ArrayList<>()).add(link);
        }
    }

    private void addPosition(BlockPos position, String node) {
        positions.computeIfAbsent(key(position), ignored -> new LinkedHashSet<>()).add(node);
    }

    List<Link> unique(String source, String destination) {
        if (source.isBlank() || destination.isBlank() || source.equals(destination)) return List.of();
        Map<String, List<Link>> found = new LinkedHashMap<>(); searches = 0;
        for (String from : positions.getOrDefault(source, Set.of())) {
            for (String to : positions.getOrDefault(destination, Set.of())) {
                visit(from, to, new ArrayList<>(), new HashSet<>(), found);
                if (found.size() > 1) return List.of();
            }
        }
        return found.size() == 1 ? found.values().iterator().next() : List.of();
    }

    private void visit(String from, String to, List<Link> path, Set<String> seen, Map<String, List<Link>> found) {
        if (++searches > 20_000) throw new IllegalStateException("production_flow_path_search_budget");
        if (from.equals(to)) {
            if (!path.isEmpty()) found.putIfAbsent(path.stream().map(Link::id).toList().toString(), List.copyOf(path));
            return;
        }
        if (!seen.add(from) || found.size() > 1) return;
        for (Link link : outgoing.getOrDefault(from, List.of())) {
            String next = plan.port(link.to()).node();
            if (!next.equals(to) && !plan.node(next).kind().equals("transport")) continue;
            path.add(link); visit(next, to, path, seen, found); path.removeLast();
        }
        seen.remove(from);
    }

    Set<BlockPos> observationPositions() {
        Set<BlockPos> result = new LinkedHashSet<>();
        for (String producer : producers) result.add(plan.at(plan.node(producer)));
        for (Link link : links) {
            result.add(plan.at(plan.port(link.from()).offset()));
            result.add(plan.at(plan.port(link.to()).offset()));
        }
        result.add(plan.at(plan.node(plan.manifest().target().node())));
        return result;
    }

    static boolean matches(Resource target, JsonObject resource) {
        if (!resource.has("identity") || !resource.has("resource_id")) return false;
        JsonObject identity = resource.getAsJsonObject("identity");
        return target.medium().equals(text(identity, "kind")) && identity.has("components")
                && (target.id().equals(text(identity, "id")) || target.id().equals(text(resource, "resource_id")));
    }

    static String key(BlockPos position) { return position.getX() + "," + position.getY() + "," + position.getZ(); }
    static String text(JsonObject value, String key) { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : ""; }
}
