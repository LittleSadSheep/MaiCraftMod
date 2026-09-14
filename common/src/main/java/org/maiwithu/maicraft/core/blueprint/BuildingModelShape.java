// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;

/** 建筑快速建模的局部凸几何；面和棱供检查及材质选择使用，不读取注册表，也不接触世界方块。 */
public final class BuildingModelShape {
    public record Face(String id, Vec3 normal, double offset) {
        public Face {
            if (id == null || !id.matches("[a-z][a-z0-9_]*") || !finite(normal) || !Double.isFinite(offset)
                    || Math.abs(length(normal) - 1) > 1e-9) throw bad("face needs a stable id and finite unit outward normal");
        }
        // 正数表示该点在面内侧，零为表面，负数为外侧；真实方块距离不会受法线长度影响。
        public double distance(Vec3 point) {
            if (!finite(point)) throw bad("distance point must be finite");
            return offset - normal.dot(point);
        }
    }
    public record Edge(String id, Vec3 from, Vec3 to, List<String> faces) {
        public Edge {
            faces = faces == null ? List.of() : faces.stream().sorted().toList();
            if (!finite(from) || !finite(to) || !(length(to.subtract(from)) > 0)
                    || !Double.isFinite(length(to.subtract(from))) || faces.size() != 2 || faces.get(0).equals(faces.get(1))
                    || !String.join("+", faces).equals(id)) throw bad("edge needs two different faces and a finite nonzero segment");
        }
        // 材质贴棱时按有限线段测距；越过端点后量到端点，不能把无限延长线也当作建筑棱。
        public double distance(Vec3 point) {
            if (!finite(point)) throw bad("distance point must be finite");
            Vec3 delta = to.subtract(from); double length = length(delta);
            Vec3 direction = new Vec3(delta.x / length, delta.y / length, delta.z / length), relative = point.subtract(from);
            if (!finite(relative)) return Double.POSITIVE_INFINITY;
            double along = Math.clamp(relative.dot(direction), 0, length);
            return length(relative.subtract(direction.scale(along)));
        }
    }
    private final Vec3 boundsMin, boundsMax;
    private final List<Face> faces;
    private final List<Edge> edges;
    private final double tolerance;

    BuildingModelShape(Vec3 boundsMin, Vec3 boundsMax, List<Face> faces, List<Edge> edges, double tolerance) {
        this.boundsMin = boundsMin; this.boundsMax = boundsMax; this.faces = List.copyOf(faces); this.edges = List.copyOf(edges);
        this.tolerance = tolerance;
    }
    public static BuildingModelShape create(String primitive, JsonObject object, Vec3 dimensions) {
        if (primitive == null || primitive.isBlank()) throw bad("primitive is required");
        if (!finite(dimensions) || dimensions.x <= 0 || dimensions.y <= 0 || dimensions.z <= 0)
            throw bad("dimensions must be finite positive block lengths");
        String kind = primitive.strip().toLowerCase(Locale.ROOT);
        // 先在归一化坐标中校验封闭凸体，再按方块尺寸换算法线；细长建筑也不能把斜面距离直接按原比例套用。
        var mesh = BuildingModelPrimitives.mesh(kind, object == null ? new JsonObject() : object);
        return BuildingConvexMesh.compile(mesh.vertices(), mesh.polygons(), dimensions);
    }
    public Vec3 boundsMin() { return boundsMin; }
    public Vec3 boundsMax() { return boundsMax; }
    public List<Face> faces() { return faces; }
    public List<Edge> edges() { return edges; }
    public boolean contains(Vec3 point) {
        if (!finite(point)) return false;
        for (Face face : faces) {
            double magnitude = Math.max(Math.abs(face.offset()), Math.max(Math.abs(face.normal.x * point.x),
                    Math.max(Math.abs(face.normal.y * point.y), Math.abs(face.normal.z * point.z))));
            if (face.distance(point) < -Math.max(tolerance, Math.ulp(magnitude) * 32)) return false;
        }
        return true;
    }
    static double length(Vec3 vector) { return Math.hypot(Math.hypot(vector.x, vector.y), vector.z); }
    static boolean finite(Vec3 vector) { return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z); }
    static IllegalArgumentException bad(String message) { return new IllegalArgumentException("Invalid building model shape: " + message); }
}
