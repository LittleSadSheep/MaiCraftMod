package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Observed, directed signal paths. A physical structure or a seat alone proves no driving capability. */
public final class ControlCircuit {
    public enum Kind { KEY, THROTTLE, STEERING_WHEEL, TRANSMITTER, RECEIVER, WIRE, RELAY,
        TRANSMISSION, WHEEL, PROPELLER, JOINT, SEAT, OTHER }
    public record Node(String id, Kind kind, Map<String, Object> facts) {
        public Node { facts = Map.copyOf(facts); }
        public boolean control() { return kind == Kind.KEY || kind == Kind.THROTTLE || kind == Kind.STEERING_WHEEL; }
        public boolean actuator() { return kind == Kind.WHEEL || kind == Kind.PROPELLER || kind == Kind.JOINT; }
        public boolean locomotion() { return kind == Kind.WHEEL || kind == Kind.PROPELLER; }
    }
    public record Edge(String from, String to, String medium, String behavior, boolean verified) {}
    public record Route(String control, String actuator, List<Edge> path) {
        public Route { path = List.copyOf(path); }
    }
    public record Analysis(List<Route> routes, Set<String> unconnectedControls, List<String> unknowns,
                           boolean locomotionObserved, boolean locomotionControlled, boolean complete) {
        public Analysis {
            routes = List.copyOf(routes); unconnectedControls = Set.copyOf(unconnectedControls);
            unknowns = List.copyOf(unknowns);
        }
        public boolean controllableCandidate() { return locomotionControlled; }
    }

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, List<Edge>> outgoing = new LinkedHashMap<>();
    private final Set<Edge> edges = new LinkedHashSet<>();
    private final Set<String> unknowns = new LinkedHashSet<>();
    public void add(Node node) {
        if (nodes.putIfAbsent(node.id(), node) != null) throw new IllegalArgumentException("duplicate circuit node " + node.id());
    }
    public void connect(Edge edge) {
        if (!nodes.containsKey(edge.from()) || !nodes.containsKey(edge.to()))
            throw new IllegalArgumentException("circuit edge must reference observed nodes");
        if (edges.add(edge)) outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
    }
    public void unknown(String detail) { unknowns.add(detail); }
    public List<Node> nodes() { return List.copyOf(nodes.values()); }
    public List<Edge> edges() { return List.copyOf(edges); }
    public Node node(String id) { return nodes.get(id); }
    public int size() { return nodes.size(); }

    public Analysis analyze() {
        List<Route> routes = new ArrayList<>();
        Set<String> unconnected = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>(unknowns);
        for (Node control : nodes.values()) {
            if (!control.control()) continue;
            Map<String, Edge> previous = new LinkedHashMap<>();
            Set<String> seen = new LinkedHashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(control.id()); seen.add(control.id());
            boolean connected = false;
            while (!queue.isEmpty()) {
                String id = queue.remove();
                Node node = nodes.get(id);
                if (node.actuator()) {
                    List<Edge> path = new ArrayList<>();
                    for (String cursor = id; !cursor.equals(control.id());) {
                        Edge step = previous.get(cursor); path.add(step); cursor = step.from();
                    }
                    routes.add(new Route(control.id(), id, path.reversed())); connected = true;
                }
                for (Edge edge : outgoing.getOrDefault(id, List.of())) {
                    if (!edge.verified()) {
                        missing.add("unverified path from " + id + " to " + edge.to() + ": " + edge.behavior());
                    } else if (seen.add(edge.to())) { previous.put(edge.to(), edge); queue.add(edge.to()); }
                }
            }
            if (!connected) unconnected.add(control.id());
        }
        return new Analysis(routes, unconnected, missing,
                nodes.values().stream().anyMatch(Node::locomotion),
                routes.stream().map(r->nodes.get(r.actuator())).anyMatch(n->n.locomotion()
                        && !Boolean.FALSE.equals(n.facts().get("wheel_item_present"))), missing.isEmpty());
    }
}
