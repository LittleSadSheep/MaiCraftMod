// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.maiwithu.maicraft.ability.design.DesignFormat.bad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.ability.design.DesignTransform.Box;

/**
 * 把图纸展开成一个个独立的叶子：组件实例复制出来、阵列铺开、变换串起来；每片叶子记着自己的展开路径
 * （例如 Arcade[1,0,0]/Shell），布尔开孔只认同一份实例里的切割体，不会串到另一份。
 * 展开前先把坏引用、组件环和超量阵列都拒绝掉，不能复制到一半才失控。
 */
public final class DesignExpansion {

    /** 展开后的一片叶子：一个图元实例，或一个屋顶（shape 为空）。 */
    public record Leaf(String name, JsonObject node, DesignShape shape, Box bounds, DesignTransform transform,
                       List<String> ownCuts, List<String> inheritedCuts, List<JsonObject> materialMaps, String stateAxes) {

        List<String> cuts() {
            var all = new LinkedHashSet<>(inheritedCuts);
            all.addAll(ownCuts);
            return List.copyOf(all);
        }

        boolean contains(Vec3 point) {
            return shape != null && shape.contains(transform.inverse(point));
        }

        /** 屋顶不是凸图元，格由屋顶做法逐格算。 */
        public boolean roof() {
            return shape == null;
        }
    }

    final JsonObject drawing;
    final List<Leaf> leaves = new ArrayList<>();
    final Map<String, List<Leaf>> references = new LinkedHashMap<>();
    final Map<String, JsonObject> nodes = new LinkedHashMap<>();
    final Set<String> cutterLeaves = new HashSet<>();
    private final Set<String> cutterReferences = new HashSet<>();
    private final JsonObject components;
    private final boolean blender;
    private final Map<String, Integer> componentDepths = new HashMap<>();
    private final Map<JsonObject, DesignShape> shapeCache = new IdentityHashMap<>();
    // 用长整数累计再比较，大量继承的切割引用不能溢出成负数而获准展开。
    private long expandedNodes;
    private long expandedCuts;
    private long authoredNodes;
    private long declaredModifiers;

    static DesignExpansion expand(JsonObject source) {
        return new DesignExpansion(source);
    }

    private DesignExpansion(JsonObject source) {
        DesignFormat.validate(source);
        drawing = source.deepCopy();
        components = drawing.has("components") ? drawing.getAsJsonObject("components") : new JsonObject();
        blender = !drawing.has("coordinate_system") || drawing.get("coordinate_system").getAsString().equals("blender_z_up");
        inspectList(drawing.getAsJsonArray("objects"));
        for (var definition : components.entrySet()) inspectList(definition.getValue().getAsJsonObject().getAsJsonArray("objects"));
        for (String name : components.keySet()) checkComponent(name, new HashSet<>());
        expandList(drawing.getAsJsonArray("objects"), "", DesignTransform.identity(), List.of(), List.of(),
                drawing.has("block_state_axes") ? drawing.get("block_state_axes").getAsString() : "local", 0);
        for (String name : cutterReferences) {
            List<Leaf> found = references.get(name);
            if (found == null) throw bad("找不到展开后的切割体：" + name);
            for (Leaf leaf : found) cutterLeaves.add(leaf.name);
        }
        if (leaves.stream().noneMatch(leaf -> !cutterLeaves.contains(leaf.name))) throw bad("图纸里没有一个实心对象");
    }

    boolean blender() {
        return blender;
    }

    /** 展开后的对象数，含切割体与屋顶。 */
    public int expandedCount() {
        return leaves.size();
    }

    /** 其中有几个是切割体。 */
    public int cutterCount() {
        return cutterLeaves.size();
    }

    private void inspectList(JsonArray objects) {
        // 还没摆进图纸的组件也先查：坏引用和藏着的无限递归提前拒绝，不等复制一次才突然失控。
        authoredNodes += objects.size();
        if (authoredNodes > DesignLimits.MAX_OBJECTS) throw bad("作者对象超过上限 " + DesignLimits.MAX_OBJECTS);
        Map<String, JsonObject> siblings = new LinkedHashMap<>();
        for (var entry : objects) {
            JsonObject node = entry.getAsJsonObject();
            siblings.put(node.get("name").getAsString(), node);
        }
        var localCutters = new HashSet<String>();
        siblings.values().forEach(node -> localCutters.addAll(rawCuts(node)));
        for (var node : siblings.values()) {
            String type = node.get("type").getAsString();
            // 没摆出来的图元也先验证几何和直角变换，组件库里不能悄悄存着开放或退化的凸体。
            DesignTransform.local(node, blender, Vec3.ZERO);
            if (type.equals("MESH")) {
                PanelPattern.validatePanel(node, DesignTransform.dimensions(node, blender));
                var shape = shapeCache.computeIfAbsent(node, ignored -> createShape(node, DesignTransform.dimensions(node, blender)));
                MaterialRules.validate(drawing.getAsJsonObject("materials"), node, shape,
                        localCutters.contains(node.get("name").getAsString()) || node.has("role") && node.get("role").getAsString().equals("cutter"));
            } else if (type.equals("ROOF")) {
                Roof.checkMaterials(drawing.getAsJsonObject("materials"), node);
            } else if (!components.has(node.get("component").getAsString())) {
                throw bad("没有这个组件：" + node.get("component").getAsString());
            }
            if (node.has("material_map")) {
                for (var mapping : node.getAsJsonObject("material_map").entrySet()) {
                    material(mapping.getKey());
                    material(mapping.getValue().getAsString());
                }
            }
            checkCuts(node, siblings);
        }
    }

    private void checkCuts(JsonObject node, Map<String, JsonObject> siblings) {
        for (String reference : rawCuts(node)) {
            if (++declaredModifiers > DesignLimits.MAX_CUTS) throw bad("布尔切割引用超过上限 " + DesignLimits.MAX_CUTS);
            JsonObject cutter = siblings.get(reference);
            if (cutter == null || reference.equals(node.get("name").getAsString())) throw bad("布尔切割要指向同一组里的另一个对象：" + reference);
            if (!cutter.get("type").getAsString().equals("MESH") || cutter.has("pattern") || !rawCuts(cutter).isEmpty()
                    || cutter.has("fill") && !cutter.get("fill").getAsString().equals("solid")) {
                throw bad("切割体要是实心图元，不能带图案或修改器（可以是阵列）：" + reference);
            }
        }
        if (node.has("role") && node.get("role").getAsString().equals("cutter")
                && (node.has("pattern") || !rawCuts(node).isEmpty() || node.has("fill") && !node.get("fill").getAsString().equals("solid"))) {
            throw bad("切割体要是实心的，不能带图案或修改器：" + node.get("name").getAsString());
        }
    }

    private int checkComponent(String name, Set<String> active) {
        // 共用的子组件只查一次；有向无环图不能因为重复引用在预检时变成指数级递归。
        if (componentDepths.containsKey(name)) return componentDepths.get(name);
        if (active.size() >= DesignLimits.MAX_NESTING || !active.add(name)) throw bad("组件引用成环，或嵌套超过 " + DesignLimits.MAX_NESTING + " 层：" + name);
        int depth = 1;
        for (var entry : components.getAsJsonObject(name).getAsJsonArray("objects")) {
            JsonObject node = entry.getAsJsonObject();
            if (node.get("type").getAsString().equals("INSTANCE")) depth = Math.max(depth, 1 + checkComponent(node.get("component").getAsString(), active));
        }
        if (depth > DesignLimits.MAX_NESTING) throw bad("组件嵌套超过 " + DesignLimits.MAX_NESTING + " 层：" + name);
        active.remove(name);
        componentDepths.put(name, depth);
        return depth;
    }

    private List<Leaf> expandList(JsonArray objects, String scope, DesignTransform parent, List<String> inheritedCuts,
                                  List<JsonObject> mappings, String stateAxes, int depth) {
        if (depth > DesignLimits.MAX_NESTING) throw bad("组件嵌套超过 " + DesignLimits.MAX_NESTING + " 层");
        var all = new ArrayList<Leaf>();
        for (var value : objects) {
            DesignFormat.checkInterrupted();
            JsonObject node = value.getAsJsonObject();
            String key = scope + node.get("name").getAsString();
            nodes.put(key, node);
            if (key.length() > 256) throw bad("展开路径超过 256 个字符：" + key);
            List<String> cuts = rawCuts(node).stream().map(name -> scope + name).toList();
            cutterReferences.addAll(cuts);
            if (node.has("role") && node.get("role").getAsString().equals("cutter")) cutterReferences.add(key);
            var collected = new ArrayList<Leaf>();
            int[] count = {1, 1, 1};
            Vec3 step = Vec3.ZERO;
            Set<String> skip = new HashSet<>();
            boolean array = node.has("array");
            if (array) {
                JsonObject repeat = node.getAsJsonObject("array");
                for (int axis = 0; axis < 3; axis++) count[axis] = repeat.getAsJsonArray("count").get(axis).getAsInt();
                step = DesignTransform.vector(repeat, "step", Vec3.ZERO);
                if (repeat.has("skip")) {
                    for (var index : repeat.getAsJsonArray("skip")) {
                        JsonArray at = index.getAsJsonArray();
                        skip.add(index(at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt()));
                    }
                }
            }
            for (int z = 0; z < count[2]; z++) {
                for (int y = 0; y < count[1]; y++) {
                    for (int x = 0; x < count[0]; x++) {
                        if (skip.contains(index(x, y, z))) continue;
                        String path = key + (array ? "[" + index(x, y, z) + "]" : "");
                        DesignTransform transform = parent.then(DesignTransform.local(node, blender, new Vec3(x * step.x, y * step.y, z * step.z)));
                        String axes = node.has("block_state_axes") ? node.get("block_state_axes").getAsString() : stateAxes;
                        List<Leaf> instance = expandOne(node, path, transform, inheritedCuts, cuts, mappings, axes, depth);
                        collected.addAll(instance);
                        if (array) {
                            references.put(path, List.copyOf(instance));
                            nodes.put(path, node);
                        }
                    }
                }
            }
            references.put(key, List.copyOf(collected));
            all.addAll(collected);
        }
        return List.copyOf(all);
    }

    private List<Leaf> expandOne(JsonObject node, String path, DesignTransform transform, List<String> inheritedCuts, List<String> cuts,
                                 List<JsonObject> mappings, String axes, int depth) {
        DesignFormat.checkInterrupted();
        if (++expandedNodes > DesignLimits.MAX_OBJECTS) throw bad("展开后的对象超过上限 " + DesignLimits.MAX_OBJECTS);
        if (path.length() > 256) throw bad("展开路径超过 256 个字符：" + path);
        String type = node.get("type").getAsString();
        if (type.equals("INSTANCE")) {
            var nextMaps = new ArrayList<JsonObject>();
            if (node.has("material_map")) nextMaps.add(node.getAsJsonObject("material_map"));
            nextMaps.addAll(mappings);
            var nextCuts = new ArrayList<>(inheritedCuts);
            nextCuts.addAll(cuts);
            return expandList(components.getAsJsonObject(node.get("component").getAsString()).getAsJsonArray("objects"),
                    path + "/", transform, List.copyOf(nextCuts), List.copyOf(nextMaps), axes, depth + 1);
        }
        Leaf leaf;
        if (type.equals("ROOF")) {
            leaf = new Leaf(path, node, null, Roof.bounds(node, transform), transform, List.of(), List.of(), mappings, axes);
        } else {
            Vec3 dimensions = DesignTransform.dimensions(node, blender);
            // 每片叶子落到整张图纸后按同一个坐标半径校验，设计原点不变。
            Box bounds = transform.bounds(dimensions, DesignLimits.MAX_RADIUS);
            DesignShape shape = shapeCache.computeIfAbsent(node, ignored -> createShape(node, dimensions));
            leaf = new Leaf(path, node, shape, bounds, transform, cuts, inheritedCuts, mappings, axes);
            expandedCuts += leaf.cuts().size();
            if (expandedCuts > DesignLimits.MAX_CUTS) throw bad("展开后的切割引用超过上限 " + DesignLimits.MAX_CUTS);
        }
        leaves.add(leaf);
        return List.of(leaf);
    }

    List<Leaf> cutters(Leaf leaf) {
        var masks = new LinkedHashMap<String, Leaf>();
        for (String name : leaf.cuts()) for (Leaf cutter : references.get(name)) masks.put(cutter.name, cutter);
        return List.copyOf(masks.values());
    }

    /** 材料表里的一项；没有就拒绝。 */
    JsonObject material(String name) {
        if (!drawing.getAsJsonObject("materials").has(name)) throw bad("材料表里没有 " + name);
        return drawing.getAsJsonObject("materials").getAsJsonObject(name);
    }

    private DesignShape createShape(JsonObject node, Vec3 dimensions) {
        String primitive = node.get("primitive").getAsString();
        if (!blender || !primitive.equals("convex_polyhedron")) return DesignShape.create(primitive, node, dimensions);
        // 自定义顶点也跟着作者选的坐标系走：归一化盒里 Blender 的 y 变成 1-z，不能只换尺寸却把网格留在旧轴上。
        JsonObject normalized = node.deepCopy();
        JsonArray vertices = new JsonArray();
        for (var value : node.getAsJsonArray("vertices")) {
            JsonArray point = value.getAsJsonArray();
            vertices.add(DesignTransform.json(new Vec3(point.get(0).getAsDouble(), point.get(2).getAsDouble(), 1 - point.get(1).getAsDouble())));
        }
        normalized.add("vertices", vertices);
        return DesignShape.create(primitive, normalized, dimensions);
    }

    private static String index(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static List<String> rawCuts(JsonObject node) {
        if (!node.has("modifiers")) return List.of();
        var result = new ArrayList<String>();
        for (var value : node.getAsJsonArray("modifiers")) {
            String name = value.getAsJsonObject().get("object").getAsString();
            if (result.contains(name)) throw bad("同一个切割体引用了两次：" + name);
            result.add(name);
        }
        return List.copyOf(result);
    }
}
