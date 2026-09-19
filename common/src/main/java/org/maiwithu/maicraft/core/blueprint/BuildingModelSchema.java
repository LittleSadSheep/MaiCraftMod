// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import org.maiwithu.maicraft.core.build.BuildingBudgets;
import static org.maiwithu.maicraft.core.blueprint.BuildingSceneGeometry.*;

/** 先核对快速图元和组件的作者输入，再展开重复装饰；未知字段不能成为隐藏的游戏操作。 */
public final class BuildingModelSchema {
    static final Set<String> PRIMITIVES = Set.of("cube", "panel", "triangle", "wedge", "triangular_prism",
            "tetrahedron", "triangular_pyramid", "pyramid", "prism", "cylinder", "cone", "convex_polyhedron");
    private static final Set<String> COMMON = Set.of("name", "type", "location", "rotation_euler", "mirror", "array", "modifiers", "block_state_axes");
    private static final Set<String> MESH = Set.of("primitive", "dimensions", "role", "material", "fill", "wall_thickness",
            "face_materials", "edge_material", "edge_materials", "edge_width", "open_faces", "segments", "vertices", "faces");
    private static final Set<String> INSTANCE = Set.of("component", "material_map");
    private BuildingModelSchema() {}

    public static boolean applies(JsonObject scene) {
        return scene != null && scene.has("schema_version") && scene.get("schema_version").isJsonPrimitive()
                && scene.getAsJsonPrimitive("schema_version").isNumber()
                && scene.get("schema_version").getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(2)) == 0;
    }

    static void validate(JsonObject scene) {
        keys(scene, Set.of("schema_version", "name", "coordinate_system", "materials", "objects", "components", "block_state_axes", "overlap_policy"), "v2 scene");
        integer(scene.get("schema_version"), 2, 2, "schema_version");
        choice(scene, "coordinate_system", Set.of("minecraft_y_up", "blender_z_up"));
        choice(scene, "block_state_axes", Set.of("local", "minecraft_world"));
        choice(scene, "overlap_policy", Set.of("last_wins", "error"));
        if (scene.has("name")) string(scene.get("name"), 128, "scene.name");
        validateMaterials(object(scene.get("materials"), "materials"));
        nodes(array(scene.get("objects"), 1, limit(), "objects"), true);
        if (scene.has("components")) {
            JsonObject components = object(scene.get("components"), "components");
            if (components.size() > limit()) throw bad("too many component definitions");
            for (var entry : components.entrySet()) { name(entry.getKey()); validateComponent(object(entry.getValue(), "component")); }
        }
    }

    public static void validateComponent(JsonObject component) {
        keys(component, Set.of("objects"), "component definition");
        nodes(array(component.get("objects"), 1, limit(), "component.objects"), true);
    }

    static void nodes(JsonArray objects, boolean complete) {
        var names = new HashSet<String>();
        for (var entry : objects) {
            JsonObject node = object(entry, "model node"); validateNode(node, complete);
            if (!names.add(name(node.get("name")))) throw bad("duplicate object name in the same component");
        }
    }
    public static void validateNodePatch(JsonObject node) { validateNode(node, false); }

    private static void validateNode(JsonObject node, boolean complete) {
        var allowed = new HashSet<>(COMMON); allowed.addAll(MESH); allowed.addAll(INSTANCE);
        keys(node, allowed, "model node");
        if (complete) name(node.get("name")); else string(node.get("name"), 64, "object name");
        String type = node.has("type") ? string(node.get("type"), 16, "type") : null;
        // 老场景的 cube/panel 简写仍可按名称编辑或显式升级，不要求先重写每一面现成墙体。
        boolean shorthand = type != null && Set.of("cube", "panel").contains(type);
        if (shorthand) { if (node.has("primitive")) throw bad("primitive requires type MESH"); type = "MESH"; }
        if (complete && type == null) throw bad("model node needs type MESH or INSTANCE");
        if (type != null && !Set.of("MESH", "INSTANCE").contains(type)) throw bad("model node type must be MESH or INSTANCE");
        if (type != null) for (String key : node.keySet()) {
            if (type.equals("MESH") && INSTANCE.contains(key) || type.equals("INSTANCE") && MESH.contains(key))
                throw bad(key + " is not valid on " + type);
        }
        if (complete && type.equals("MESH")) {
            for (String field : Set.of("location", "dimensions")) if (!node.has(field)) throw bad("mesh needs " + field);
            if (!shorthand && !node.has("primitive")) throw bad("mesh needs primitive");
            String primitive = shorthand ? node.get("type").getAsString() : string(node.get("primitive"), 64, "primitive");
            if (node.has("segments") && !Set.of("prism", "cylinder", "cone").contains(primitive)) throw bad("segments only applies to prism, cylinder or cone");
            if ((node.has("vertices") || node.has("faces")) && !primitive.equals("convex_polyhedron")) throw bad("explicit vertices/faces need convex_polyhedron");
            if (primitive.equals("convex_polyhedron") && (!node.has("vertices") || !node.has("faces"))) throw bad("convex_polyhedron needs both vertices and faces");
        }
        if (complete && type.equals("INSTANCE") && !node.has("component")) throw bad("instance needs component");
        if (node.has("component")) name(node.get("component"));
        choice(node, "primitive", PRIMITIVES); choice(node, "role", Set.of("solid", "cutter"));
        choice(node, "fill", Set.of("solid", "hollow")); choice(node, "block_state_axes", Set.of("local", "minecraft_world"));
        for (String field : Set.of("location", "dimensions", "rotation_euler")) if (node.has(field)) {
            for (JsonElement value : array(node.get(field), 3, 3, field)) {
                if (field.equals("dimensions")) integer(value, 1, 2 * BuildingBudgets.current().maxRadius() + 1, field);
                else finite(value, field);
            }
        }
        if (complete && type.equals("MESH") && (node.has("primitive") ? node.get("primitive").getAsString() : node.get("type").getAsString()).equals("panel")
                && node.getAsJsonArray("dimensions").asList().stream().noneMatch(value -> value.getAsDouble() == 1))
            throw bad("panel must be one block thick on at least one axis");
        for (String field : Set.of("material", "edge_material")) if (node.has(field)) string(node.get(field), 64, field);
        for (String field : Set.of("wall_thickness", "edge_width")) if (node.has(field)) {
            double value = finite(node.get(field), field);
            if (value < .5 || value > 2L * BuildingBudgets.current().maxRadius() + 1) throw bad(field + " must be at least half a block and remain bounded");
        }
        for (String field : Set.of("face_materials", "edge_materials", "material_map")) if (node.has(field)) namesMap(object(node.get(field), field));
        if (node.has("mirror")) distinctStrings(array(node.get("mirror"), 0, 3, "mirror"), Set.of("x", "y", "z"), "mirror");
        if (node.has("open_faces")) distinctStrings(array(node.get("open_faces"), 0, 64, "open_faces"), null, "open_faces");
        if (node.has("segments")) integer(node.get("segments"), 3, 32, "segments");
        if (node.has("vertices")) for (var vertex : array(node.get("vertices"), 4, 64, "vertices"))
            for (var coordinate : array(vertex, 3, 3, "vertex")) { double v = finite(coordinate, "vertex"); if (v < 0 || v > 1) throw bad("vertices must be normalized to 0..1"); }
        if (node.has("faces")) for (var face : array(node.get("faces"), 4, 64, "faces"))
            for (var index : array(face, 3, 64, "face")) integer(index, 0, 63, "face vertex index");
        if (node.has("array")) validateArray(object(node.get("array"), "array"));
        if (node.has("modifiers")) for (var value : array(node.get("modifiers"), 0, BuildingBudgets.current().maxConnections(), "modifiers")) {
            JsonObject modifier = object(value, "modifier"); keys(modifier, Set.of("type", "operation", "object"), "modifier");
            if (!"BOOLEAN".equals(string(modifier.get("type"), 16, "modifier.type"))
                    || !"DIFFERENCE".equals(string(modifier.get("operation"), 16, "modifier.operation"))) throw bad("only BOOLEAN DIFFERENCE is supported");
            name(modifier.get("object"));
        }
    }

    static void validateArray(JsonObject repeat) {
        // 阵列先检查乘积，门洞跳过索引也必须落在阵列内，不能展开到一半才发现几百万份复制。
        keys(repeat, Set.of("count", "step", "skip"), "array");
        JsonArray count = array(repeat.get("count"), 3, 3, "array.count"), step = array(repeat.get("step"), 3, 3, "array.step");
        long total = 1;
        for (int axis = 0; axis < 3; axis++) {
            int n = integer(count.get(axis), 1, limit(), "array.count"); total *= n;
            double distance = finite(step.get(axis), "array.step");
            if (n > 1 && distance == 0 || total > limit()) throw bad("array repeats must have distinct spacing and stay within the component budget");
        }
        if (repeat.has("skip")) {
            var seen = new HashSet<String>();
            for (var value : array(repeat.get("skip"), 0, limit(), "array.skip")) {
                JsonArray index = array(value, 3, 3, "skip index"); StringBuilder key = new StringBuilder();
                for (int axis = 0; axis < 3; axis++) key.append(integer(index.get(axis), 0, count.get(axis).getAsInt() - 1, "skip index")).append('/');
                if (!seen.add(key.toString())) throw bad("duplicate skipped array index");
            }
        }
    }

    private static void validateMaterials(JsonObject materials) {
        if (materials.isEmpty() || materials.size() > limit()) throw bad("materials must contain a bounded named palette");
        for (var entry : materials.entrySet()) {
            if (entry.getKey().isBlank() || entry.getKey().length() > 64) throw bad("invalid material name");
            JsonObject state = object(entry.getValue(), "material"); keys(state, Set.of("block_id", "properties"), "material");
            if (!string(state.get("block_id"), 256, "block_id").matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw bad("material needs a namespaced block ID");
            if (state.has("properties")) {
                JsonObject properties = object(state.get("properties"), "properties");
                if (properties.size() > 32) throw bad("too many material properties");
                for (var property : properties.entrySet()) {
                    if (property.getKey().isBlank() || property.getKey().length() > 64) throw bad("invalid material property");
                    string(property.getValue(), 128, "material property value");
                }
            }
        }
    }
    private static void namesMap(JsonObject map) {
        if (map.size() > 192) throw bad("too many material bindings");
        for (var entry : map.entrySet()) {
            if (entry.getKey().isBlank() || entry.getKey().length() > 128) throw bad("invalid material binding name");
            string(entry.getValue(), 64, "material binding");
        }
    }
    private static void distinctStrings(JsonArray values, Set<String> allowed, String field) {
        var seen = new HashSet<String>();
        for (var value : values) { String text = string(value, 64, field); if (!seen.add(text) || allowed != null && !allowed.contains(text)) throw bad("invalid or duplicate " + field); }
    }
    static void choice(JsonObject object, String key, Set<String> values) {
        if (object.has(key) && !values.contains(string(object.get(key), 64, key))) throw bad("unsupported " + key);
    }
    static double finite(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw bad(field + " must be numeric");
        double result = value.getAsDouble(); if (!Double.isFinite(result) || Math.abs(result) > 1_000_000_000) throw bad(field + " exceeds finite model coordinates"); return result;
    }
    // 柱网与幕墙阵列按配置计入作者和展开节点，不能在配置之外另藏一个4096节点上限。
    static int limit() { return BuildingBudgets.current().maxObjects(); }
    static String name(JsonElement value) { return name(string(value, 64, "object/component name")); }
    static String name(String value) {
        if (value.isBlank() || value.length() > 64 || value.contains("/") || value.contains("[") || value.contains("]"))
            throw bad("object/component names cannot contain path or instance separators"); return value;
    }
}
