package org.maiwithu.maicraft.core.integration.physics.balance;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/** 蒙皮试算按原生气球的向上及横向连通规则找可容气区域，开口底部可以存在，侧面或顶部破口会漏气。 */
public final class BalloonEnvelope {
    private BalloonEnvelope() {}
    public enum Kind { AIR, SOLID, AIRTIGHT, UNKNOWN }
    public record Cell(int x, int y, int z) {
        public Cell offset(int x, int y, int z) { return new Cell(this.x+x, this.y+y, this.z+z); }
    }
    public record Bounds(Cell min, Cell max) {
        boolean contains(Cell p) { return p.x >= min.x && p.y >= min.y && p.z >= min.z
                && p.x <= max.x && p.y <= max.y && p.z <= max.z; }
    }
    public record Result(String state, int capacity, PhysicsVector center, int reads) {}
    private static final int[][] FLOW = {{0,1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}};

    public static Result inspect(Function<Cell, Kind> source, Bounds bounds, Cell start, int budget) {
        if (budget < 1 || budget > 262144) throw new IllegalArgumentException("气球观察预算无效");
        var view = new View(source, bounds, budget);
        Set<Cell> volume = new HashSet<>(), leaking = new HashSet<>();
        try {
            if (view.get(start) == Kind.AIRTIGHT) return new Result("blocked_source", 0, PhysicsVector.ZERO, view.cache.size());
            Set<Cell> initial = flood(view, start, volume, leaking);
            if (initial == null) return new Result("leaking", 0, PhysicsVector.ZERO, view.cache.size());
            volume.addAll(initial);
            var down = new ArrayDeque<Cell>(); initial.forEach(p -> down.add(p.offset(0, -1, 0)));
            Set<Cell> tested = new HashSet<>();
            while (!down.isEmpty()) {
                Cell below = down.removeFirst();
                if (!tested.add(below) || volume.contains(below) || leaking.contains(below) || view.get(below) == Kind.AIRTIGHT) continue;
                Set<Cell> layer = flood(view, below, volume, leaking);
                if (layer == null) continue;
                volume.addAll(layer); layer.forEach(p -> down.add(p.offset(0, -1, 0)));
            }
            int capacity = 0; PhysicsVector sum = PhysicsVector.ZERO;
            for (Cell p : volume) if (view.get(p) == Kind.AIR) {
                capacity++; sum = sum.add(new PhysicsVector(p.x+.5, p.y+.5, p.z+.5));
            }
            return new Result("enclosed", capacity, capacity == 0 ? PhysicsVector.ZERO : sum.scale(1.0 / capacity), view.cache.size());
        } catch (Unknown missing) { return new Result(missing.getMessage(), 0, PhysicsVector.ZERO, view.cache.size()); }
    }
    private static Set<Cell> flood(View view, Cell start, Set<Cell> existing, Set<Cell> leaks) {
        Set<Cell> visited = new HashSet<>(); var queue = new ArrayDeque<Cell>(); queue.add(start);
        while (!queue.isEmpty()) {
            Cell p = queue.removeFirst();
            if (existing.contains(p) || visited.contains(p)) continue;
            if (!view.bounds.contains(p) || leaks.contains(p)) { leaks.addAll(visited); leaks.add(start); return null; }
            if (view.get(p) == Kind.AIRTIGHT) continue;
            visited.add(p);
            for (int[] direction : FLOW) queue.add(p.offset(direction[0], direction[1], direction[2]));
        }
        return visited;
    }
    private static final class View {
        final Function<Cell, Kind> source; final Bounds bounds; final int budget;
        final HashMap<Cell, Kind> cache = new HashMap<>();
        View(Function<Cell, Kind> source, Bounds bounds, int budget) { this.source = source; this.bounds = bounds; this.budget = budget; }
        Kind get(Cell p) {
            if (!bounds.contains(p)) return Kind.AIR;
            Kind known = cache.get(p); if (known != null) return known;
            if (cache.size() >= budget) throw new Unknown("budget_exhausted");
            Kind value = source.apply(p);
            if (value == null || value == Kind.UNKNOWN) throw new Unknown("unloaded");
            cache.put(p, value); return value;
        }
    }
    private static final class Unknown extends RuntimeException { Unknown(String state) { super(state); } }
}
