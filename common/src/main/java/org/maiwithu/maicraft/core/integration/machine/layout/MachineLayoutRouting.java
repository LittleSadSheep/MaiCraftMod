// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.layout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.MachinePlanningBudget;

/** Bounded physical routing; distinct resource edges never silently join another conduit. */
final class MachineLayoutRouting {
    record Pos(int x, int y, int z) {
        Pos step(Side side) { return new Pos(Math.addExact(x,side.x), Math.addExact(y,side.y), Math.addExact(z,side.z)); }
        Pos plus(Pos other) { return new Pos(Math.addExact(x,other.x), Math.addExact(y,other.y), Math.addExact(z,other.z)); }
        int distance(Pos other) { return (int)Math.min(Integer.MAX_VALUE, Math.abs((long)x-other.x)+Math.abs((long)y-other.y)+Math.abs((long)z-other.z)); }
    }
    enum Side {
        EAST(1, 0, 0), WEST(-1, 0, 0), SOUTH(0, 0, 1), UP(0, 1, 0), DOWN(0, -1, 0), NORTH(0, 0, -1);
        final int x, y, z;
        Side(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        String label() { return name().toLowerCase(java.util.Locale.ROOT); }
    }
    record Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        boolean contains(Pos p) {
            return p.x >= minX && p.x <= maxX && p.y >= minY && p.y <= maxY && p.z >= minZ && p.z <= maxZ;
        }
    }
    record Cell(Pos position, String id, Map<String, String> properties, String part, String owner) {
        Cell(Pos position, String id, Map<String, String> properties, boolean part, String owner) {
            this(position, id, properties, part ? "center" : null, owner);
        }
        boolean isPart() { return part != null; }
    }
    record Route(List<Cell> cells, Side sourceSide, Side destinationSide, int searched) {}
    private record Node(Pos pos, boolean alongX) {}
    private record Frontier(Node node, int cost, int priority) {}
    private record Candidate(Side from, Side to) {}
    private static final int SEARCH_BUDGET = MachinePlanningBudget.current().searchVisitedBudget();
    private MachineLayoutRouting() {}
    static void checkpoint() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("machine layout compilation cancelled");
    }

    static Route route(Pos from, Pos to, List<Side> fromSides, List<Side> toSides, String medium,
                       String transport, String owner, Map<Pos, Cell> occupied, Set<Pos> clearance, Bounds bounds) {
        List<Candidate> candidates = new ArrayList<>();
        for (Side a : fromSides) for (Side b : toSides) candidates.add(new Candidate(a, b));
        candidates.sort(java.util.Comparator.<Candidate>comparingInt(c ->
                        -(reusable(occupied.get(from.step(c.from)), transport, owner) ? 1 : 0)
                        -(reusable(occupied.get(to.step(c.to)), transport, owner) ? 1 : 0))
                .thenComparingInt(c -> from.step(c.from).distance(to.step(c.to))));
        int remaining = SEARCH_BUDGET;
        for (Candidate ports : candidates) {
            checkpoint();
            if (remaining <= 0) break;
            Pos start = from.step(ports.from), end = to.step(ports.to);
            if (!available(start, from, to, transport, owner, occupied, clearance, bounds)
                    || !available(end, from, to, transport, owner, occupied, clearance, bounds)) continue;
            Search result = search(start, end, from, to, medium.equals("kinetic"), transport,
                    owner, occupied, clearance, bounds, remaining);
            remaining -= result.searched;
            if (result.path == null) continue;
            List<Cell> cells = new ArrayList<>();
            Set<Pos> unique = new HashSet<>();
            boolean valid = true;
            for (Node node : result.path) {
                checkpoint();
                if (!unique.add(node.pos)) { valid = false; break; }
                Map<String, String> state = medium.equals("kinetic")
                        ? Map.of("axis", "y", "axis_along_first", Boolean.toString(node.alongX)) : Map.of();
                cells.add(new Cell(node.pos, transport, state, medium.equals("ae_network"), owner));
            }
            if (valid) return new Route(List.copyOf(cells), ports.from, ports.to, SEARCH_BUDGET - remaining);
        }
        return null;
    }

    private record Search(List<Node> path, int searched) {}
    private static Search search(Pos start, Pos end, Pos from, Pos to, boolean kinetic, String transport, String owner,
                                 Map<Pos, Cell> occupied, Set<Pos> clearance, Bounds bounds, int budget) {
        PriorityQueue<Frontier> open = new PriorityQueue<>(java.util.Comparator.comparingInt(Frontier::priority)
                .thenComparingInt(e -> e.node.pos.distance(end)).thenComparingInt(e -> e.node.pos.x)
                .thenComparingInt(e -> e.node.pos.y).thenComparingInt(e -> e.node.pos.z).thenComparing(e -> e.node.alongX));
        Map<Node, Node> parents = new HashMap<>();
        Map<Node, Integer> costs = new HashMap<>();
        Node first = new Node(start, true);
        open.add(new Frontier(first,0,start.distance(end))); parents.put(first, null); costs.put(first,0);
        if (kinetic) { Node other = new Node(start, false); open.add(new Frontier(other,0,start.distance(end))); parents.put(other, null); costs.put(other,0); }
        while (!open.isEmpty() && parents.size() <= budget) {
            checkpoint();
            Frontier next = open.remove(); Node at = next.node;
            if (next.cost != costs.get(at)) continue;
            if (at.pos.equals(end)) {
                List<Node> path = new ArrayList<>();
                for (Node node = at; node != null; node = parents.get(node)) path.add(node);
                Collections.reverse(path);
                return new Search(path, parents.size());
            }
            List<Side> directions = new ArrayList<>(List.of(Side.values()));
            directions.sort(java.util.Comparator.comparingInt(d -> at.pos.step(d).distance(end)));
            for (Side direction : directions) {
                if (kinetic && direction.y == 0 && (direction.x != 0) != at.alongX) continue;
                Pos nextPos = at.pos.step(direction);
                if (!available(nextPos, from, to, transport, owner, occupied, clearance, bounds)) continue;
                offer(new Node(nextPos, at.alongX), at, open, parents, costs, end);
                // Chain drives turn by changing their horizontal connection axis on a vertical shaft step.
                if (kinetic && direction.y != 0) offer(new Node(nextPos, !at.alongX), at, open, parents, costs, end);
            }
        }
        return new Search(null, parents.size());
    }

    private static void offer(Node node, Node parent, PriorityQueue<Frontier> open, Map<Node, Node> parents,
                              Map<Node,Integer> costs, Pos end) {
        int cost = costs.get(parent) + 1;
        if (costs.getOrDefault(node,Integer.MAX_VALUE) <= cost) return;
        costs.put(node,cost); parents.put(node, parent); open.add(new Frontier(node,cost,(int)Math.min(Integer.MAX_VALUE,(long)cost+node.pos.distance(end))));
    }

    private static boolean reusable(Cell cell, String transport, String owner) {
        return cell != null && transport.equals(MachineLayoutAeNetworks.DENSE) && cell.id.equals(transport) && cell.owner.equals(owner);
    }

    private static boolean available(Pos at, Pos source, Pos destination, String transport, String owner,
                                     Map<Pos, Cell> occupied, Set<Pos> clearance, Bounds bounds) {
        if (!bounds.contains(at) || occupied.containsKey(at) && !reusable(occupied.get(at),transport,owner) || clearance.contains(at)) return false;
        for (Side side : Side.values()) {
            Pos neighbor = at.step(side);
            Cell cell = occupied.get(neighbor);
            if (cell == null || neighbor.equals(source) || neighbor.equals(destination)) continue;
            if (reusable(cell,transport,owner)) continue;
            // Any adjacent equipment could accept resources; any equal conduit could cross-connect.
            if (cell.owner.startsWith("component:") || cell.id.equals(transport)) return false;
        }
        return true;
    }
}
