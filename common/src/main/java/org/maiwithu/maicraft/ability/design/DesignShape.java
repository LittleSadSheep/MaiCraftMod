// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import java.util.List;
import java.util.Locale;

import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;

/**
 * 一个图元的局部凸几何：面和棱有稳定的名字，供检查与选材质用；判断一点在不在图元里。
 * 不读注册表，也不碰世界方块。
 */
public final class DesignShape {

    /** 一个面：名字、朝外的单位法线、到原点的带符号距离。 */
    public record Face(String id, Vec3 normal, double offset) {
        public Face {
            if (id == null || !id.matches("[a-z][a-z0-9_]*") || !finite(normal) || !Double.isFinite(offset)
                    || Math.abs(length(normal) - 1) > 1e-9) {
                throw bad("面要有稳定的名字和朝外的单位法线");
            }
        }

        /** 正数表示点在面内侧，零在面上，负数在面外；按真实方块距离算。 */
        public double distance(Vec3 point) {
            if (!finite(point)) throw bad("测距的点要是有限坐标");
            return offset - normal.dot(point);
        }
    }

    /** 一条棱：两个相邻面的名字排序后用加号连起来就是它的名字。 */
    public record Edge(String id, Vec3 from, Vec3 to, List<String> faces) {
        public Edge {
            faces = faces == null ? List.of() : faces.stream().sorted().toList();
            if (!finite(from) || !finite(to) || !(length(to.subtract(from)) > 0)
                    || !Double.isFinite(length(to.subtract(from))) || faces.size() != 2 || faces.get(0).equals(faces.get(1))
                    || !String.join("+", faces).equals(id)) {
                throw bad("棱要连着两个不同的面，而且是一段有限长度的线段");
            }
        }

        /** 到这段棱的距离：越过端点就量到端点，不把无限延长线当成棱。 */
        public double distance(Vec3 point) {
            if (!finite(point)) throw bad("测距的点要是有限坐标");
            Vec3 delta = to.subtract(from);
            double length = length(delta);
            Vec3 direction = new Vec3(delta.x / length, delta.y / length, delta.z / length);
            Vec3 relative = point.subtract(from);
            if (!finite(relative)) return Double.POSITIVE_INFINITY;
            double along = Math.clamp(relative.dot(direction), 0, length);
            return length(relative.subtract(direction.scale(along)));
        }
    }

    private final Vec3 boundsMin;
    private final Vec3 boundsMax;
    private final List<Face> faces;
    private final List<Edge> edges;
    private final double tolerance;

    DesignShape(Vec3 boundsMin, Vec3 boundsMax, List<Face> faces, List<Edge> edges, double tolerance) {
        this.boundsMin = boundsMin;
        this.boundsMax = boundsMax;
        this.faces = List.copyOf(faces);
        this.edges = List.copyOf(edges);
        this.tolerance = tolerance;
    }

    /** 按图元名和尺寸生成几何；先在归一化坐标里验证封闭凸体，再按方块尺寸换算法线。 */
    public static DesignShape create(String primitive, JsonObject object, Vec3 dimensions) {
        if (primitive == null || primitive.isBlank()) throw bad("要写 primitive");
        if (!finite(dimensions) || dimensions.x <= 0 || dimensions.y <= 0 || dimensions.z <= 0) throw bad("dimensions 要是有限的正数");
        String kind = primitive.strip().toLowerCase(Locale.ROOT);
        var mesh = DesignPrimitives.mesh(kind, object == null ? new JsonObject() : object);
        return ConvexMesh.compile(mesh.vertices(), mesh.polygons(), dimensions);
    }

    public Vec3 boundsMin() {
        return boundsMin;
    }

    public Vec3 boundsMax() {
        return boundsMax;
    }

    public List<Face> faces() {
        return faces;
    }

    public List<Edge> edges() {
        return edges;
    }

    /** 点在所有面的内侧（含面上）就在图元里；容差随坐标大小放宽一点，细长图元不会因浮点误差漏格。 */
    public boolean contains(Vec3 point) {
        if (!finite(point)) return false;
        for (Face face : faces) {
            double magnitude = Math.max(Math.abs(face.offset()), Math.max(Math.abs(face.normal.x * point.x),
                    Math.max(Math.abs(face.normal.y * point.y), Math.abs(face.normal.z * point.z))));
            if (face.distance(point) < -Math.max(tolerance, Math.ulp(magnitude) * 32)) return false;
        }
        return true;
    }

    static double length(Vec3 vector) {
        return Math.hypot(Math.hypot(vector.x, vector.y), vector.z);
    }

    static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException("图元几何不对：" + message);
    }
}
