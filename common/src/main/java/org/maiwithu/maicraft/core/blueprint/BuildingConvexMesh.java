// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import static org.maiwithu.maicraft.core.blueprint.BuildingModelShape.*;

/** 先验证封闭、平面和凸性，再输出稳定面/棱；错误网格在体素化之前拒绝，不能悄悄填成意外的建筑实体。 */
final class BuildingConvexMesh {
    private static final double EPS = 1e-9;
    private record Facet(String id, List<Integer> ring, Vec3 normal, double offset) {}
    private record Key(int from, int to) { static Key of(int a, int b) { return new Key(Math.min(a,b),Math.max(a,b)); } }
    private record Use(int face, int from, int to) {}
    private BuildingConvexMesh() {}

    static BuildingModelShape compile(List<Vec3> vertices, List<BuildingModelPrimitives.Polygon> polygons, Vec3 dimensions) {
        if (vertices.size() < 4 || vertices.size() > 64 || polygons.size() < 4 || polygons.size() > 64)
            throw bad("a convex mesh needs 4..64 vertices and faces");
        Vec3 center = Vec3.ZERO;
        for (int i = 0; i < vertices.size(); i++) {
            Vec3 vertex = vertices.get(i);
            if (!finite(vertex)) throw bad("vertices must be finite");
            for (int j = 0; j < i; j++) if (vertex.distanceToSqr(vertices.get(j)) <= EPS * EPS) throw bad("duplicate or indistinguishable vertices");
            center = center.add(vertex.scale(1.0 / vertices.size()));
        }
        var facets = new ArrayList<Facet>(); var names = new HashSet<String>(); var used = new HashSet<Integer>();
        for (var polygon : polygons) {
            if (!names.add(polygon.id())) throw bad("duplicate face id: " + polygon.id());
            var facet = facet(vertices, polygon, center); facets.add(facet); used.addAll(facet.ring);
        }
        if (used.size() != vertices.size()) throw bad("every vertex must belong to the closed surface");
        // 材质按真实面与棱匹配；同平面拆成几片会产生假棱，要求作者合并成一个完整平面。
        for (int i = 0; i < facets.size(); i++) for (int j = 0; j < i; j++) {
            Facet a = facets.get(i), b = facets.get(j);
            if (a.normal.distanceToSqr(b.normal) <= EPS * EPS && Math.abs(a.offset - b.offset) <= EPS)
                throw bad("coplanar faces must be merged into one face");
        }
        var uses = new LinkedHashMap<Key, List<Use>>();
        for (int i = 0; i < facets.size(); i++) {
            var ring = facets.get(i).ring;
            for (int j = 0; j < ring.size(); j++) {
                int a = ring.get(j), b = ring.get((j + 1) % ring.size());
                uses.computeIfAbsent(Key.of(a,b), ignored -> new ArrayList<>()).add(new Use(i,a,b));
            }
        }
        for (var edge : uses.values()) if (edge.size() != 2 || edge.get(0).from != edge.get(1).to || edge.get(0).to != edge.get(1).from)
            throw bad("each edge must join exactly two consistently oriented faces");
        if (vertices.size() - uses.size() + facets.size() != 2 || !connected(facets.size(), uses)) throw bad("surface must be one closed convex shell");
        double volume = 0;
        for (var facet : facets) {
            Vec3 a = vertices.get(facet.ring.getFirst()).subtract(center);
            for (int i = 1; i + 1 < facet.ring.size(); i++)
                volume += a.dot(vertices.get(facet.ring.get(i)).subtract(center).cross(vertices.get(facet.ring.get(i+1)).subtract(center))) / 6;
        }
        if (!(volume > EPS * EPS * EPS)) throw bad("polyhedron must enclose a nonzero volume");
        return scaled(vertices, facets, uses, dimensions);
    }

    private static Facet facet(List<Vec3> vertices, BuildingModelPrimitives.Polygon polygon, Vec3 center) {
        var ring = new ArrayList<>(polygon.indices()); var distinct = new HashSet<Integer>();
        if (ring.size() < 3 || ring.size() > 64) throw bad("face needs 3..64 vertices");
        for (int index : ring) if (index < 0 || index >= vertices.size() || !distinct.add(index)) throw bad("face has an invalid or repeated vertex index");
        Vec3 a = vertices.get(ring.getFirst()), area = Vec3.ZERO;
        for (int i = 1; i + 1 < ring.size(); i++)
            area = area.add(vertices.get(ring.get(i)).subtract(a).cross(vertices.get(ring.get(i+1)).subtract(a)));
        double magnitude = length(area);
        if (!(magnitude > EPS * EPS)) throw bad("face is degenerate");
        Vec3 normal = area.scale(1 / magnitude); double offset = normal.dot(a), inside = normal.dot(center) - offset;
        if (Math.abs(inside) <= EPS) throw bad("surface has no strictly interior volume");
        // 作者可写顺时针或逆时针索引，统一朝外后才验证共享边，不能靠输入绕序猜里面是哪一侧。
        if (inside > 0) { normal = normal.scale(-1); offset = -offset; Collections.reverse(ring); }
        for (int index : ring) if (Math.abs(normal.dot(vertices.get(index)) - offset) > EPS) throw bad("face vertices must lie on one plane");
        for (Vec3 vertex : vertices) if (normal.dot(vertex) - offset > EPS) throw bad("polyhedron must be convex");
        for (int i = 0; i < ring.size(); i++) {
            Vec3 start = vertices.get(ring.get(i)), edge = vertices.get(ring.get((i+1) % ring.size())).subtract(start);
            for (int j = 0; j < ring.size(); j++) {
                if (j == i || j == (i+1) % ring.size()) continue;
                double distance = edge.cross(vertices.get(ring.get(j)).subtract(start)).dot(normal) / length(edge);
                if (!(distance > EPS)) throw bad("face polygon must be simple, convex and without collinear corners");
            }
        }
        return new Facet(polygon.id(), List.copyOf(ring), normal, offset);
    }
    private static boolean connected(int count, Map<Key, List<Use>> uses) {
        var reached = new HashSet<Integer>(); var queue = new ArrayDeque<Integer>(); reached.add(0); queue.add(0);
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            for (var edge : uses.values()) {
                int a = edge.get(0).face, b = edge.get(1).face;
                int other = a == current ? b : b == current ? a : -1;
                if (other >= 0 && reached.add(other)) queue.add(other);
            }
        }
        return reached.size() == count;
    }
    private static BuildingModelShape scaled(List<Vec3> vertices, List<Facet> facets, Map<Key, List<Use>> uses, Vec3 dimensions) {
        var world = vertices.stream().map(v -> v.multiply(dimensions)).toList();
        double x0 = Double.POSITIVE_INFINITY, y0 = x0, z0 = x0, x1 = -x0, y1 = -x0, z1 = -x0;
        for (Vec3 vertex : world) {
            x0 = Math.min(x0,vertex.x); y0 = Math.min(y0,vertex.y); z0 = Math.min(z0,vertex.z);
            x1 = Math.max(x1,vertex.x); y1 = Math.max(y1,vertex.y); z1 = Math.max(z1,vertex.z);
        }
        double minimum = Math.min(dimensions.x, Math.min(dimensions.y, dimensions.z)); var faces = new ArrayList<Face>();
        for (var facet : facets) {
            // 非等比缩放后按逆转置换算法线，再恢复单位长度；面距离仍使用真正的方块单位。
            Vec3 weighted = new Vec3(facet.normal.x * (minimum / dimensions.x), facet.normal.y * (minimum / dimensions.y), facet.normal.z * (minimum / dimensions.z));
            double magnitude = length(weighted);
            if (!(magnitude > 0)) throw bad("dimensions exceed the supported numeric range");
            Vec3 normal = new Vec3(weighted.x / magnitude, weighted.y / magnitude, weighted.z / magnitude);
            faces.add(new Face(facet.id, normal, normal.dot(world.get(facet.ring.getFirst()))));
        }
        var edges = new ArrayList<Edge>(); var ids = new HashSet<String>();
        Comparator<Vec3> position = Comparator.comparingDouble((Vec3 v) -> v.x).thenComparingDouble(v -> v.y).thenComparingDouble(v -> v.z);
        for (var entry : uses.entrySet()) {
            var adjacent = entry.getValue().stream().map(use -> facets.get(use.face).id).sorted().toList();
            String id = String.join("+", adjacent);
            if (!ids.add(id)) throw bad("two faces must share at most one geometric edge");
            Vec3 from = world.get(entry.getKey().from), to = world.get(entry.getKey().to);
            if (position.compare(from,to) > 0) { Vec3 swap = from; from = to; to = swap; }
            edges.add(new Edge(id, from, to, adjacent));
        }
        edges.sort(Comparator.comparing(Edge::id));
        return new BuildingModelShape(new Vec3(x0,y0,z0), new Vec3(x1,y1,z1), faces, edges, minimum * 1e-10);
    }
}
