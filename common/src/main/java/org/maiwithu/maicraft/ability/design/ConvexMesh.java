// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.maiwithu.maicraft.ability.design.DesignShape.bad;
import static org.maiwithu.maicraft.ability.design.DesignShape.finite;
import static org.maiwithu.maicraft.ability.design.DesignShape.length;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.phys.Vec3;

/**
 * 把网格验证成封闭的凸多面体，再按方块尺寸缩放成图元几何：先验证封闭、每面共面、整体凸，再给出稳定的面与棱。
 * 坏网格在逐格采样之前就拒绝，不会悄悄填成意外的实体。
 */
public final class ConvexMesh {

    private static final double EPS = 1e-9;

    private record Facet(String id, List<Integer> ring, Vec3 normal, double offset) {}

    private record Key(int from, int to) {
        static Key of(int a, int b) {
            return new Key(Math.min(a, b), Math.max(a, b));
        }
    }

    private record Use(int face, int from, int to) {}

    private ConvexMesh() {}

    static DesignShape compile(List<Vec3> vertices, List<DesignPrimitives.Polygon> polygons, Vec3 dimensions) {
        if (vertices.size() < 4 || vertices.size() > 64 || polygons.size() < 4 || polygons.size() > 64) throw bad("凸体要有 4..64 个顶点和面");
        Vec3 center = Vec3.ZERO;
        for (int i = 0; i < vertices.size(); i++) {
            Vec3 vertex = vertices.get(i);
            if (!finite(vertex)) throw bad("顶点要是有限坐标");
            for (int j = 0; j < i; j++) if (vertex.distanceToSqr(vertices.get(j)) <= EPS * EPS) throw bad("有重复或分不开的顶点");
            center = center.add(vertex.scale(1.0 / vertices.size()));
        }
        var facets = new ArrayList<Facet>();
        var names = new HashSet<String>();
        var used = new HashSet<Integer>();
        for (var polygon : polygons) {
            if (!names.add(polygon.id())) throw bad("面名重复：" + polygon.id());
            var facet = facet(vertices, polygon, center);
            facets.add(facet);
            used.addAll(facet.ring);
        }
        if (used.size() != vertices.size()) throw bad("每个顶点都要属于封闭表面");
        // 材质按真实的面与棱匹配；同一平面拆成几片会生出假棱，要求作者合成一个完整的面。
        for (int i = 0; i < facets.size(); i++) {
            for (int j = 0; j < i; j++) {
                Facet a = facets.get(i), b = facets.get(j);
                if (a.normal.distanceToSqr(b.normal) <= EPS * EPS && Math.abs(a.offset - b.offset) <= EPS) throw bad("共面的面要合成一个");
            }
        }
        var uses = new LinkedHashMap<Key, List<Use>>();
        for (int i = 0; i < facets.size(); i++) {
            var ring = facets.get(i).ring;
            for (int j = 0; j < ring.size(); j++) {
                int a = ring.get(j), b = ring.get((j + 1) % ring.size());
                uses.computeIfAbsent(Key.of(a, b), ignored -> new ArrayList<>()).add(new Use(i, a, b));
            }
        }
        for (var edge : uses.values()) {
            if (edge.size() != 2 || edge.get(0).from != edge.get(1).to || edge.get(0).to != edge.get(1).from) throw bad("每条棱要恰好连着两个绕向一致的面");
        }
        if (vertices.size() - uses.size() + facets.size() != 2 || !connected(facets.size(), uses)) throw bad("表面要是一个封闭的凸壳");
        double volume = 0;
        for (var facet : facets) {
            Vec3 a = vertices.get(facet.ring.getFirst()).subtract(center);
            for (int i = 1; i + 1 < facet.ring.size(); i++) {
                volume += a.dot(vertices.get(facet.ring.get(i)).subtract(center).cross(vertices.get(facet.ring.get(i + 1)).subtract(center))) / 6;
            }
        }
        if (!(volume > EPS * EPS * EPS)) throw bad("多面体要围出体积");
        return scaled(vertices, facets, uses, dimensions);
    }

    private static Facet facet(List<Vec3> vertices, DesignPrimitives.Polygon polygon, Vec3 center) {
        var ring = new ArrayList<>(polygon.indices());
        var distinct = new HashSet<Integer>();
        if (ring.size() < 3 || ring.size() > 64) throw bad("一个面要有 3..64 个顶点");
        for (int index : ring) if (index < 0 || index >= vertices.size() || !distinct.add(index)) throw bad("面的顶点下标不对或重复");
        Vec3 a = vertices.get(ring.getFirst()), area = Vec3.ZERO;
        for (int i = 1; i + 1 < ring.size(); i++) area = area.add(vertices.get(ring.get(i)).subtract(a).cross(vertices.get(ring.get(i + 1)).subtract(a)));
        double magnitude = length(area);
        if (!(magnitude > EPS * EPS)) throw bad("有退化的面");
        Vec3 normal = area.scale(1 / magnitude);
        double offset = normal.dot(a), inside = normal.dot(center) - offset;
        if (Math.abs(inside) <= EPS) throw bad("表面没有真正的内部");
        // 作者可以顺时针或逆时针写下标；统一成朝外后再查共享边，不靠输入绕序猜哪边是里面。
        if (inside > 0) {
            normal = normal.scale(-1);
            offset = -offset;
            Collections.reverse(ring);
        }
        for (int index : ring) if (Math.abs(normal.dot(vertices.get(index)) - offset) > EPS) throw bad("一个面的顶点要在同一平面上");
        for (Vec3 vertex : vertices) if (normal.dot(vertex) - offset > EPS) throw bad("多面体要是凸的");
        for (int i = 0; i < ring.size(); i++) {
            Vec3 start = vertices.get(ring.get(i)), edge = vertices.get(ring.get((i + 1) % ring.size())).subtract(start);
            for (int j = 0; j < ring.size(); j++) {
                if (j == i || j == (i + 1) % ring.size()) continue;
                double distance = edge.cross(vertices.get(ring.get(j)).subtract(start)).dot(normal) / length(edge);
                if (!(distance > EPS)) throw bad("面要是简单凸多边形，不能有共线的角");
            }
        }
        return new Facet(polygon.id(), List.copyOf(ring), normal, offset);
    }

    private static boolean connected(int count, Map<Key, List<Use>> uses) {
        var reached = new HashSet<Integer>();
        var queue = new ArrayDeque<Integer>();
        reached.add(0);
        queue.add(0);
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

    private static DesignShape scaled(List<Vec3> vertices, List<Facet> facets, Map<Key, List<Use>> uses, Vec3 dimensions) {
        var world = vertices.stream().map(v -> v.multiply(dimensions)).toList();
        double x0 = Double.POSITIVE_INFINITY, y0 = x0, z0 = x0, x1 = -x0, y1 = -x0, z1 = -x0;
        for (Vec3 vertex : world) {
            x0 = Math.min(x0, vertex.x);
            y0 = Math.min(y0, vertex.y);
            z0 = Math.min(z0, vertex.z);
            x1 = Math.max(x1, vertex.x);
            y1 = Math.max(y1, vertex.y);
            z1 = Math.max(z1, vertex.z);
        }
        double minimum = Math.min(dimensions.x, Math.min(dimensions.y, dimensions.z));
        var faces = new ArrayList<DesignShape.Face>();
        for (var facet : facets) {
            // 非等比缩放后按逆转置换算法线，再恢复单位长度；面的距离仍用真正的方块单位。
            Vec3 weighted = new Vec3(facet.normal.x * (minimum / dimensions.x), facet.normal.y * (minimum / dimensions.y), facet.normal.z * (minimum / dimensions.z));
            double magnitude = length(weighted);
            if (!(magnitude > 0)) throw bad("dimensions 超出可计算的范围");
            Vec3 normal = new Vec3(weighted.x / magnitude, weighted.y / magnitude, weighted.z / magnitude);
            faces.add(new DesignShape.Face(facet.id, normal, normal.dot(world.get(facet.ring.getFirst()))));
        }
        var edges = new ArrayList<DesignShape.Edge>();
        var ids = new HashSet<String>();
        Comparator<Vec3> position = Comparator.comparingDouble((Vec3 v) -> v.x).thenComparingDouble(v -> v.y).thenComparingDouble(v -> v.z);
        for (var entry : uses.entrySet()) {
            var adjacent = entry.getValue().stream().map(use -> facets.get(use.face).id).sorted().toList();
            String id = String.join("+", adjacent);
            if (!ids.add(id)) throw bad("两个面之间最多只能有一条棱");
            Vec3 from = world.get(entry.getKey().from), to = world.get(entry.getKey().to);
            if (position.compare(from, to) > 0) {
                Vec3 swap = from;
                from = to;
                to = swap;
            }
            edges.add(new DesignShape.Edge(id, from, to, adjacent));
        }
        edges.sort(Comparator.comparing(DesignShape.Edge::id));
        return new DesignShape(new Vec3(x0, y0, z0), new Vec3(x1, y1, z1), faces, edges, minimum * 1e-10);
    }
}
