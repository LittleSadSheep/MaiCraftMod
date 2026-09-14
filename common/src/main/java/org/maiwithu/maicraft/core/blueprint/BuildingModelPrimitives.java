// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import static org.maiwithu.maicraft.core.blueprint.BuildingModelShape.bad;

/** 以包围框中心为局部原点生成封闭图元；三角板沿 Z 拉伸，棱柱和锥体沿 Minecraft 的 Y 轴向上。 */
final class BuildingModelPrimitives {
    record Polygon(String id, List<Integer> indices) { Polygon { indices = List.copyOf(indices); } }
    record Mesh(List<Vec3> vertices, List<Polygon> polygons) { Mesh { vertices = List.copyOf(vertices); polygons = List.copyOf(polygons); } }
    private BuildingModelPrimitives() {}

    static Mesh mesh(String kind, JsonObject object) {
        return switch (kind) {
            case "cube", "panel" -> box();
            case "triangle", "triangular_prism" -> triangle(false);
            case "wedge" -> triangle(true);
            case "tetrahedron", "triangular_pyramid" -> pyramid(true);
            case "pyramid" -> pyramid(false);
            case "prism" -> round(segments(object, 6), false);
            case "cylinder" -> round(segments(object, 16), false);
            case "cone" -> round(segments(object, 16), true);
            case "convex_polyhedron" -> custom(object);
            default -> throw bad("unsupported primitive: " + kind);
        };
    }
    private static Mesh box() {
        var vertices = List.of(v(-1,-1,-1), v(1,-1,-1), v(1,1,-1), v(-1,1,-1),
                v(-1,-1,1), v(1,-1,1), v(1,1,1), v(-1,1,1));
        // front 固定为 -Z，back 为 +Z；实例的旋转/镜像由上层处理，不随着观察相机重命名。
        return new Mesh(vertices, List.of(p("left",0,3,7,4), p("right",1,5,6,2), p("bottom",0,4,5,1),
                p("top",3,2,6,7), p("front",0,1,2,3), p("back",4,7,6,5)));
    }
    private static Mesh triangle(boolean right) {
        var vertices = List.of(v(-1,-1,-1), v(1,-1,-1), v(right ? -1 : 0,1,-1),
                v(-1,-1,1), v(1,-1,1), v(right ? -1 : 0,1,1));
        return new Mesh(vertices, List.of(p("bottom",0,3,4,1), p(right ? "left" : "left_slope",0,2,5,3),
                p(right ? "slope" : "right_slope",1,4,5,2), p("front",0,1,2), p("back",3,5,4)));
    }
    private static Mesh pyramid(boolean triangular) {
        if (triangular) return new Mesh(List.of(v(-1,-1,-1),v(1,-1,-1),v(0,-1,1),v(0,1,0)),
                List.of(p("bottom",0,2,1),p("side_0",0,1,3),p("side_1",1,2,3),p("side_2",2,0,3)));
        return new Mesh(List.of(v(-1,-1,-1),v(1,-1,-1),v(1,-1,1),v(-1,-1,1),v(0,1,0)),
                List.of(p("bottom",0,3,2,1),p("front_slope",0,1,4),p("right_slope",1,2,4),
                        p("back_slope",2,3,4),p("left_slope",3,0,4)));
    }
    private static Mesh round(int segments, boolean cone) {
        var ring = new ArrayList<Vec3>(); double minX = 1, maxX = -1, minZ = 1, maxZ = -1;
        for (int i = 0; i < segments; i++) {
            double angle = 2 * Math.PI * i / segments, x = Math.cos(angle), z = Math.sin(angle);
            ring.add(new Vec3(x,0,z)); minX = Math.min(minX,x); maxX = Math.max(maxX,x); minZ = Math.min(minZ,z); maxZ = Math.max(maxZ,z);
        }
        var vertices = new ArrayList<Vec3>();
        // 尺寸表示实际外包围框；奇数边图元也先按真实环顶点范围居中，再缩放至给定长宽。
        for (Vec3 point : ring) vertices.add(new Vec3((point.x-minX)/(maxX-minX)-.5, -.5, (point.z-minZ)/(maxZ-minZ)-.5));
        if (cone) vertices.add(new Vec3(0,.5,0));
        else for (int i = 0; i < segments; i++) vertices.add(vertices.get(i).add(0,1,0));
        var polygons = new ArrayList<Polygon>(); var bottom = new ArrayList<Integer>(); var top = new ArrayList<Integer>();
        for (int i = 0; i < segments; i++) { bottom.add(i); top.add(i+segments); }
        polygons.add(new Polygon("bottom", bottom));
        if (!cone) polygons.add(new Polygon("top", top));
        for (int i = 0; i < segments; i++) {
            int next = (i + 1) % segments;
            polygons.add(cone ? p("side_"+i,i,next,segments) : p("side_"+i,i,next,next+segments,i+segments));
        }
        return new Mesh(vertices, polygons);
    }
    private static int segments(JsonObject object, int fallback) {
        if (!object.has("segments")) return fallback;
        int result = integer(object.get("segments"), "segments");
        if (result < 3 || result > 32) throw bad("segments must be between 3 and 32");
        return result;
    }
    private static Mesh custom(JsonObject object) {
        if (!object.has("vertices") || !object.get("vertices").isJsonArray() || !object.has("faces") || !object.get("faces").isJsonArray())
            throw bad("convex_polyhedron requires vertices and faces arrays");
        var input = object.getAsJsonArray("vertices"); var faces = object.getAsJsonArray("faces");
        if (input.size() < 4 || input.size() > 64 || faces.size() < 4 || faces.size() > 64) throw bad("vertices and faces must each contain 4..64 entries");
        var vertices = new ArrayList<Vec3>(); var polygons = new ArrayList<Polygon>();
        for (var element : input) {
            if (!element.isJsonArray() || element.getAsJsonArray().size() != 3) throw bad("each vertex must contain three normalized coordinates");
            var xyz = element.getAsJsonArray(); vertices.add(new Vec3(coordinate(xyz.get(0))-.5,coordinate(xyz.get(1))-.5,coordinate(xyz.get(2))-.5));
        }
        for (int i = 0; i < faces.size(); i++) {
            var element = faces.get(i);
            if (!element.isJsonArray() || element.getAsJsonArray().size() < 3 || element.getAsJsonArray().size() > 64)
                throw bad("each face must contain 3..64 vertex indices");
            var indices = new ArrayList<Integer>(); for (var index : element.getAsJsonArray()) indices.add(integer(index, "face vertex index"));
            polygons.add(new Polygon("face_"+i, indices));
        }
        return new Mesh(vertices, polygons);
    }
    private static double coordinate(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw bad("vertex coordinate must be numeric");
        double number = value.getAsDouble(); if (!Double.isFinite(number) || number < 0 || number > 1) throw bad("vertex coordinate must be within 0..1");
        return number;
    }
    private static int integer(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw bad(field + " must be an integer");
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) { throw bad(field + " must be an integer"); }
    }
    private static Vec3 v(double x, double y, double z) { return new Vec3(x*.5,y*.5,z*.5); }
    private static Polygon p(String name, Integer... indices) { return new Polygon(name, List.of(indices)); }
}
