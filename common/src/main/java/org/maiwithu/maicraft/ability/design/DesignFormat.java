// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 图纸格式：先核对作者写的每个字段，再交给展开；写错字段名时连同本层可用的字段一起报出。
 * 校验错误一次报全，每条带 objects[下标](名字) 这样的定位。只做格式判断，不读注册表、不碰世界。
 *
 * <p>图纸正文：name、coordinate_system（minecraft_y_up / blender_z_up）、materials（材料表，一项是一种方块状态或
 * 一组按权重混用的 mix）、objects（对象）、components（可复用组件）、block_state_axes（方块朝向跟局部轴还是世界轴）、
 * overlap_policy（叠加时后写的赢还是报错）。对象分三种：MESH 图元、INSTANCE 组件实例、ROOF 参数化屋顶。
 */
public final class DesignFormat {

    static final Set<String> PRIMITIVES = Set.of("cube", "panel", "triangle", "wedge", "triangular_prism",
            "tetrahedron", "triangular_pyramid", "pyramid", "prism", "cylinder", "cone", "convex_polyhedron");
    static final Set<String> TOP = Set.of("name", "coordinate_system", "materials", "objects", "components",
            "block_state_axes", "overlap_policy");
    static final Set<String> EVERY_OBJECT = Set.of("name", "type", "location", "rotation_euler", "mirror", "array", "modifiers",
            "block_state_axes");
    static final Set<String> MESH = Set.of("primitive", "dimensions", "role", "material", "fill", "wall_thickness",
            "face_materials", "edge_material", "edge_materials", "edge_width", "open_faces", "segments", "vertices", "faces",
            "pattern");
    static final Set<String> INSTANCE = Set.of("component", "material_map");
    static final Set<String> ROOF = Set.of("footprint", "material", "shape", "curve", "overhang", "corner_lift",
            "gable_material", "ridge_material", "eave_material", "soffit_material", "hollow");
    static final Set<String> TYPES = Set.of("MESH", "INSTANCE", "ROOF");
    static final Set<String> ROOF_SHAPES = Set.of("xuanshan", "gable", "wudian", "hip", "xieshan", "half_hip",
            "zuanjian", "pyramid", "shed");

    private DesignFormat() {}

    /** 核对整张图纸；所有错误一次报全，用分号隔开。 */
    public static void validate(JsonObject drawing) {
        List<String> problems = new ArrayList<>();
        try {
            keys(drawing, TOP, "图纸");
            choice(drawing, "coordinate_system", Set.of("minecraft_y_up", "blender_z_up"));
            choice(drawing, "block_state_axes", Set.of("local", "minecraft_world"));
            choice(drawing, "overlap_policy", Set.of("last_wins", "error"));
            if (drawing.has("name")) string(drawing.get("name"), 128, "name");
            validateMaterials(object(drawing.get("materials"), "materials"));
        } catch (IllegalArgumentException invalid) {
            problems.add(invalid.getMessage());
        }
        try {
            nodes(array(drawing.get("objects"), 1, DesignLimits.MAX_OBJECTS, "objects"), "objects", problems);
        } catch (IllegalArgumentException invalid) {
            problems.add(invalid.getMessage());
        }
        if (drawing.has("components")) validateComponents(drawing.get("components"), problems);
        if (!problems.isEmpty()) throw bad(String.join("；", problems));
    }

    private static void validateComponents(JsonElement value, List<String> problems) {
        try {
            JsonObject components = object(value, "components");
            if (components.size() > DesignLimits.MAX_OBJECTS) throw bad("组件定义超过 " + DesignLimits.MAX_OBJECTS + " 个");
            for (var entry : components.entrySet()) {
                String label = "components[" + entry.getKey() + "]";
                try {
                    name(entry.getKey());
                    JsonObject component = object(entry.getValue(), label);
                    keys(component, Set.of("objects"), "组件定义");
                    nodes(array(component.get("objects"), 1, DesignLimits.MAX_OBJECTS, label + ".objects"), label + ".objects", problems);
                } catch (IllegalArgumentException invalid) {
                    problems.add(label + "：" + invalid.getMessage());
                }
            }
        } catch (IllegalArgumentException invalid) {
            problems.add(invalid.getMessage());
        }
    }

    /** 核对一个组件定义（改图时单独校验用）。 */
    public static void validateComponent(JsonObject component) {
        List<String> problems = new ArrayList<>();
        keys(component, Set.of("objects"), "组件定义");
        nodes(array(component.get("objects"), 1, DesignLimits.MAX_OBJECTS, "component.objects"), "objects", problems);
        if (!problems.isEmpty()) throw bad(String.join("；", problems));
    }

    /** 核对改图时给的一个对象补丁：字段可以不全，但写了的必须对。 */
    public static void validateNodePatch(JsonObject node) {
        validateNode(node, false);
    }

    private static void nodes(JsonArray objects, String label, List<String> problems) {
        var names = new HashSet<String>();
        for (int index = 0; index < objects.size(); index++) {
            // 错误带源对象下标和名字，LLM 能直接改指定对象，不必猜是哪一面墙被拒绝。
            JsonElement entry = objects.get(index);
            String where = label + "[" + index + "]";
            if (entry.isJsonObject() && entry.getAsJsonObject().has("name")) {
                var candidate = entry.getAsJsonObject().get("name");
                if (candidate.isJsonPrimitive() && candidate.getAsJsonPrimitive().isString() && candidate.getAsString().length() <= 64) {
                    where += "(" + candidate.getAsString() + ")";
                }
            }
            try {
                JsonObject node = object(entry, "对象");
                validateNode(node, true);
                if (!names.add(name(node.get("name")))) throw bad("同一组里对象名重复");
            } catch (IllegalArgumentException invalid) {
                problems.add(where + "：" + invalid.getMessage());
            }
        }
    }

    private static void validateNode(JsonObject node, boolean complete) {
        var allowed = new HashSet<>(EVERY_OBJECT);
        allowed.addAll(MESH);
        allowed.addAll(INSTANCE);
        allowed.addAll(ROOF);
        keys(node, allowed, "对象");
        if (complete) name(node.get("name")); else string(node.get("name"), 64, "对象名");
        String type = node.has("type") ? string(node.get("type"), 16, "type") : null;
        if (complete && type == null) throw bad("对象要写 type：MESH、INSTANCE 或 ROOF");
        if (type != null && !TYPES.contains(type)) throw bad("type 只能是 MESH、INSTANCE 或 ROOF");
        if (type != null) checkFieldsBelongToType(node, type);
        if (complete) checkRequiredFields(node, type);
        if (node.has("component")) name(node.get("component"));
        choice(node, "primitive", PRIMITIVES);
        choice(node, "role", Set.of("solid", "cutter"));
        choice(node, "fill", Set.of("solid", "hollow"));
        choice(node, "block_state_axes", Set.of("local", "minecraft_world"));
        checkGeometry(node);
        checkBindings(node);
        if ("ROOF".equals(type)) checkRoof(node);
        if (node.has("array")) validateArray(object(node.get("array"), "array"));
        // 改图或新建时先核对图案字段；图案所在的平面要等尺寸换成统一局部坐标后再查。
        if (node.has("pattern")) PanelPattern.validate(object(node.get("pattern"), "pattern"));
        if (node.has("modifiers")) checkModifiers(array(node.get("modifiers"), 0, DesignLimits.MAX_CUTS, "modifiers"));
    }

    private static void checkFieldsBelongToType(JsonObject node, String type) {
        Set<String> own = switch (type) {
            case "MESH" -> MESH;
            case "INSTANCE" -> INSTANCE;
            default -> ROOF;
        };
        for (String key : node.keySet()) {
            if (!EVERY_OBJECT.contains(key) && !own.contains(key)) throw bad(key + " 不是 " + type + " 对象的字段");
        }
    }

    private static void checkRequiredFields(JsonObject node, String type) {
        switch (type) {
            case "MESH" -> {
                for (String field : List.of("location", "dimensions", "primitive")) if (!node.has(field)) throw bad("图元要写 " + field);
                String primitive = string(node.get("primitive"), 64, "primitive");
                if (node.has("segments") && !Set.of("prism", "cylinder", "cone").contains(primitive)) throw bad("segments 只用于 prism、cylinder、cone");
                if ((node.has("vertices") || node.has("faces")) && !primitive.equals("convex_polyhedron")) throw bad("vertices 与 faces 只用于 convex_polyhedron");
                if (primitive.equals("convex_polyhedron") && (!node.has("vertices") || !node.has("faces"))) throw bad("convex_polyhedron 要同时写 vertices 与 faces");
                if (primitive.equals("panel") && node.has("dimensions")
                        && array(node.get("dimensions"), 3, 3, "dimensions").asList().stream().noneMatch(value -> value.getAsDouble() == 1)) {
                    throw bad("panel 至少有一个轴的尺寸是 1");
                }
            }
            case "INSTANCE" -> {
                if (!node.has("component")) throw bad("实例要写 component");
            }
            default -> {
                for (String field : List.of("location", "footprint", "material")) if (!node.has(field)) throw bad("屋顶要写 " + field);
            }
        }
    }

    private static void checkGeometry(JsonObject node) {
        for (String field : List.of("location", "dimensions", "rotation_euler")) {
            if (!node.has(field)) continue;
            for (JsonElement value : array(node.get(field), 3, 3, field)) {
                if (field.equals("dimensions")) integer(value, 1, 2 * DesignLimits.MAX_RADIUS + 1, field);
                else finite(value, field);
            }
        }
        for (String field : List.of("wall_thickness", "edge_width")) {
            if (!node.has(field)) continue;
            double value = finite(node.get(field), field);
            if (value < .5 || value > 2L * DesignLimits.MAX_RADIUS + 1) throw bad(field + " 至少半格，且不能超过坐标半径");
        }
        if (node.has("mirror")) distinctStrings(array(node.get("mirror"), 0, 3, "mirror"), Set.of("x", "y", "z"), "mirror");
        if (node.has("open_faces")) distinctStrings(array(node.get("open_faces"), 0, 64, "open_faces"), null, "open_faces");
        if (node.has("segments")) integer(node.get("segments"), 3, 32, "segments");
        if (node.has("vertices")) {
            for (var vertex : array(node.get("vertices"), 4, 64, "vertices")) {
                for (var coordinate : array(vertex, 3, 3, "vertex")) {
                    double v = finite(coordinate, "vertex");
                    if (v < 0 || v > 1) throw bad("vertices 要归一到 0..1");
                }
            }
        }
        if (node.has("faces")) {
            for (var face : array(node.get("faces"), 4, 64, "faces")) {
                for (var index : array(face, 3, 64, "face")) integer(index, 0, 63, "face 的顶点下标");
            }
        }
    }

    private static void checkBindings(JsonObject node) {
        for (String field : List.of("material", "edge_material", "gable_material", "ridge_material", "eave_material", "soffit_material")) {
            if (node.has(field)) string(node.get(field), 64, field);
        }
        for (String field : List.of("face_materials", "edge_materials", "material_map")) {
            if (node.has(field)) namesMap(object(node.get(field), field));
        }
    }

    private static void checkRoof(JsonObject node) {
        if (node.has("footprint")) {
            for (JsonElement value : array(node.get("footprint"), 2, 2, "footprint")) integer(value, 1, 2 * DesignLimits.MAX_RADIUS + 1, "footprint");
        }
        choice(node, "shape", ROOF_SHAPES);
        choice(node, "curve", Set.of("concave", "straight"));
        if (node.has("overhang")) integer(node.get("overhang"), 0, 4, "overhang");
        if (node.has("corner_lift")) integer(node.get("corner_lift"), 0, 3, "corner_lift");
        if (node.has("hollow")) bool(node.get("hollow"), "hollow");
        if (node.has("modifiers")) throw bad("屋顶不支持布尔切割");
        // 屋面的半砖只有上下两档，上下翻过来没有对应的方块状态。
        if (node.has("mirror") && node.getAsJsonArray("mirror").asList().stream().anyMatch(value -> "y".equals(value.getAsString()))) {
            throw bad("屋顶不能沿 y 镜像");
        }
    }

    private static void checkModifiers(JsonArray modifiers) {
        for (var value : modifiers) {
            JsonObject modifier = object(value, "modifier");
            keys(modifier, Set.of("type", "operation", "object"), "modifier");
            if (!"BOOLEAN".equals(string(modifier.get("type"), 16, "modifier.type"))
                    || !"DIFFERENCE".equals(string(modifier.get("operation"), 16, "modifier.operation"))) {
                throw bad("修改器只支持 BOOLEAN DIFFERENCE");
            }
            name(modifier.get("object"));
        }
    }

    static void validateArray(JsonObject repeat) {
        // 阵列先算总份数，跳过的下标也要落在阵列里；不能展开到一半才发现几百万份复制。
        keys(repeat, Set.of("count", "step", "skip"), "array");
        JsonArray count = array(repeat.get("count"), 3, 3, "array.count");
        JsonArray step = array(repeat.get("step"), 3, 3, "array.step");
        long total = 1;
        for (int axis = 0; axis < 3; axis++) {
            int n = integer(count.get(axis), 1, DesignLimits.MAX_OBJECTS, "array.count");
            total *= n;
            double distance = finite(step.get(axis), "array.step");
            if (n > 1 && distance == 0 || total > DesignLimits.MAX_OBJECTS) throw bad("阵列的间距不能为零，份数也不能超过对象上限");
        }
        if (repeat.has("skip")) {
            var seen = new HashSet<String>();
            for (var value : array(repeat.get("skip"), 0, DesignLimits.MAX_OBJECTS, "array.skip")) {
                JsonArray index = array(value, 3, 3, "skip 下标");
                StringBuilder key = new StringBuilder();
                for (int axis = 0; axis < 3; axis++) key.append(integer(index.get(axis), 0, count.get(axis).getAsInt() - 1, "skip 下标")).append('/');
                if (!seen.add(key.toString())) throw bad("skip 里同一份写了两次");
            }
        }
    }

    private static void validateMaterials(JsonObject materials) {
        if (materials.isEmpty() || materials.size() > DesignLimits.MAX_OBJECTS) throw bad("materials 至少一项，最多 " + DesignLimits.MAX_OBJECTS + " 项");
        for (var entry : materials.entrySet()) {
            if (entry.getKey().isBlank() || entry.getKey().length() > 64) throw bad("材料名不合法：" + entry.getKey());
            JsonObject material = object(entry.getValue(), "material");
            if (material.has("mix")) {
                keys(material, Set.of("mix"), "混色材料");
                for (var part : array(material.get("mix"), 1, DesignLimits.MAX_MIX_ENTRIES, "mix")) {
                    JsonObject state = object(part, "mix 的一项");
                    keys(state, Set.of("block_id", "properties", "weight"), "mix 的一项");
                    validateBlockState(state);
                    if (state.has("weight")) integer(state.get("weight"), 1, 1000, "weight");
                }
            } else {
                keys(material, Set.of("block_id", "properties"), "material");
                validateBlockState(material);
            }
        }
    }

    private static void validateBlockState(JsonObject state) {
        if (!string(state.get("block_id"), 256, "block_id").matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw bad("block_id 要写带命名空间的方块 ID");
        if (state.has("properties")) {
            JsonObject properties = object(state.get("properties"), "properties");
            if (properties.size() > 32) throw bad("材料的属性太多");
            for (var property : properties.entrySet()) {
                if (property.getKey().isBlank() || property.getKey().length() > 64) throw bad("材料属性名不合法");
                string(property.getValue(), 128, "材料属性值");
            }
        }
    }

    private static void namesMap(JsonObject map) {
        if (map.size() > 192) throw bad("材料绑定太多");
        for (var entry : map.entrySet()) {
            if (entry.getKey().isBlank() || entry.getKey().length() > 128) throw bad("材料绑定名不合法");
            string(entry.getValue(), 64, "材料绑定");
        }
    }

    private static void distinctStrings(JsonArray values, Set<String> allowed, String field) {
        var seen = new HashSet<String>();
        for (var value : values) {
            String text = string(value, 64, field);
            if (!seen.add(text) || allowed != null && !allowed.contains(text)) throw bad(field + " 有重复或不认识的项：" + text);
        }
    }

    static void choice(JsonObject object, String key, Set<String> values) {
        if (object.has(key) && !values.contains(string(object.get(key), 64, key))) {
            throw bad(key + " 不支持 " + object.get(key).getAsString() + "；可选：" + String.join(", ", new TreeSet<>(values)));
        }
    }

    static JsonObject object(JsonElement value, String field) {
        if (value == null || !value.isJsonObject()) throw bad(field + " 要是对象");
        return value.getAsJsonObject();
    }

    static JsonArray array(JsonElement value, int min, int max, String field) {
        if (value == null || !value.isJsonArray()) throw bad(field + " 要是数组");
        JsonArray out = value.getAsJsonArray();
        if (out.size() < min || out.size() > max) throw bad(field + " 要有 " + min + ".." + max + " 项");
        return out;
    }

    /** 要精确整数并核对上下限，不能把 1.9 截成 1。 */
    static int integer(JsonElement value, int min, int max, String field) {
        try {
            int result = number(value, field).intValueExact();
            if (result < min || result > max) throw bad(field + " 超出 " + min + ".." + max);
            return result;
        } catch (ArithmeticException invalid) {
            throw bad(field + " 要是范围内的整数");
        }
    }

    private static BigDecimal number(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw bad(field + " 要是数字");
        // 先限制数字文本长度、精度和指数，再做小数运算，极端数字不能拖慢校验。
        try {
            if (value.getAsString().length() > 64) throw bad(field + " 的数字太长");
            BigDecimal result = value.getAsBigDecimal();
            if (Math.abs((long) result.scale()) > 32 || result.precision() > 32) throw bad(field + " 的数字超出范围");
            return result;
        } catch (NumberFormatException invalid) {
            throw bad(field + " 要是有限的数字");
        }
    }

    static double finite(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw bad(field + " 要是数字");
        double result = value.getAsDouble();
        if (!Double.isFinite(result) || Math.abs(result) > 1_000_000_000) throw bad(field + " 超出有限的模型坐标");
        return result;
    }

    static boolean bool(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw bad(field + " 要是 true 或 false");
        return value.getAsBoolean();
    }

    static String string(JsonElement value, int max, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw bad(field + " 要是字符串");
        String result = value.getAsString();
        if (result.isBlank() || result.length() > max) throw bad(field + " 不能为空，也不能超过 " + max + " 个字符");
        return result;
    }

    static void keys(JsonObject object, Set<String> allowed, String field) {
        // 写错字段名时连同本层合法的字段一起报出，LLM 据此改同一张图纸。
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) throw bad(field + " 不认识字段 " + key + "；可用：" + String.join(", ", new TreeSet<>(allowed)));
        }
    }

    static String name(JsonElement value) {
        return name(string(value, 64, "对象或组件名"));
    }

    static String name(String value) {
        if (value.isBlank() || value.length() > 64 || value.contains("/") || value.contains("[") || value.contains("]")) {
            throw bad("对象或组件名不能含 / [ ]（它们是展开路径的分隔符）：" + value);
        }
        return value;
    }

    /** 展开和采样是长循环，任务被取消时要能停下。 */
    static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException(message);
    }
}
