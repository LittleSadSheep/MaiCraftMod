// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.build.BuildingBudgets;
import static org.maiwithu.maicraft.core.blueprint.BuildingSceneGeometry.*;

/** 把现场设计的窗框组件展开成独立实例，保留源路径并隔离每个实例自己的开孔引用。 */
final class BuildingModelExpansion {
    record Leaf(String name, JsonObject node, BuildingModelShape shape, Box bounds, BuildingModelTransform transform,
                List<String> ownCuts, List<String> inheritedCuts, List<JsonObject> materialMaps, String stateAxes) {
        List<String> cuts() { var all = new LinkedHashSet<>(inheritedCuts); all.addAll(ownCuts); return List.copyOf(all); }
        boolean contains(Vec3 point) { return shape.contains(transform.inverse(point)); }
    }
    final JsonObject scene;
    final List<Leaf> leaves = new ArrayList<>();
    final Map<String, List<Leaf>> references = new LinkedHashMap<>();
    final Map<String, JsonObject> nodes = new LinkedHashMap<>();
    final Set<String> cutterLeaves = new HashSet<>();
    private final Set<String> cutterReferences = new HashSet<>();
    private final JsonObject components;
    private final boolean blender;
    private final Map<String, Integer> componentDepths = new HashMap<>();
    private final Map<JsonObject, BuildingModelShape> shapeCache = new java.util.IdentityHashMap<>();
    // 用户调高节点与连接预算后，先用长整数累计再比较，不能让大量继承切割引用溢出成负数而获准展开。
    private long expandedNodes, expandedCuts, authoredNodes, declaredModifiers;

    static BuildingModelExpansion expand(JsonObject source) { return new BuildingModelExpansion(source); }
    private BuildingModelExpansion(JsonObject source) {
        BuildingModelSchema.validate(source); scene = source.deepCopy();
        components = scene.has("components") ? scene.getAsJsonObject("components") : new JsonObject();
        blender = !scene.has("coordinate_system") || scene.get("coordinate_system").getAsString().equals("blender_z_up");
        inspectList(scene.getAsJsonArray("objects"));
        for (var definition : components.entrySet()) inspectList(definition.getValue().getAsJsonObject().getAsJsonArray("objects"));
        for (String name : components.keySet()) checkComponent(name, new HashSet<>());
        expandList(scene.getAsJsonArray("objects"), "", BuildingModelTransform.identity(), List.of(), List.of(),
                scene.has("block_state_axes") ? scene.get("block_state_axes").getAsString() : "local", 0);
        for (String name : cutterReferences) {
            List<Leaf> found = references.get(name);
            if (found == null) throw bad("missing expanded cutter: " + name);
            for (Leaf leaf : found) cutterLeaves.add(leaf.name);
        }
        if (leaves.stream().noneMatch(leaf -> !cutterLeaves.contains(leaf.name))) throw bad("scene has no instantiated solid meshes");
    }

    private void inspectList(JsonArray objects) {
        // 即使组件暂未摆进场景，坏引用和隐藏的无限递归也提前拒绝，避免之后复制一次就突然失控。
        authoredNodes += objects.size();
        if (authoredNodes > BuildingModelSchema.limit()) throw bad("authored model exceeds the component budget");
        Map<String, JsonObject> siblings = new LinkedHashMap<>();
        for (var entry : objects) { JsonObject node = entry.getAsJsonObject(); siblings.put(node.get("name").getAsString(), node); }
        var localCutters = new HashSet<String>(); siblings.values().forEach(node -> localCutters.addAll(rawCuts(node)));
        for (var node : siblings.values()) {
            // 未摆出的图元也先验证几何和直角变换，组件库中不能悄悄保存开放或退化的凸体。
            BuildingModelTransform.local(node, blender, Vec3.ZERO);
            if (!node.get("type").getAsString().equals("INSTANCE")) {
                // 未实例化的组件也检查图案平面，避免复制窗格时才发现图案沿厚度方向展开。
                BuildingModelPattern.validatePanel(node, BuildingModelTransform.dimensions(node, blender));
                String primitive = node.has("primitive") ? node.get("primitive").getAsString() : node.get("type").getAsString();
                var shape = shapeCache.computeIfAbsent(node, ignored -> createShape(primitive, node, BuildingModelTransform.dimensions(node, blender)));
                BuildingModelMaterialRules.validate(scene.getAsJsonObject("materials"), node, shape, localCutters.contains(node.get("name").getAsString())
                        || node.has("role") && node.get("role").getAsString().equals("cutter"));
            }
            if (node.get("type").getAsString().equals("INSTANCE") && !components.has(node.get("component").getAsString()))
                throw bad("unknown component: " + node.get("component").getAsString());
            if (node.has("material_map")) for (var mapping : node.getAsJsonObject("material_map").entrySet()) {
                material(mapping.getKey()); material(mapping.getValue().getAsString());
            }
            for (String reference : rawCuts(node)) {
                if (++declaredModifiers > connectionLimit()) throw bad("model exceeds the modifier budget");
                JsonObject cutter = siblings.get(reference);
                if (cutter == null || reference.equals(node.get("name").getAsString())) throw bad("Boolean cutter must name a different object in the same component");
                if (cutter.get("type").getAsString().equals("INSTANCE") || cutter.has("pattern") || !rawCuts(cutter).isEmpty()
                        || cutter.has("fill") && !cutter.get("fill").getAsString().equals("solid"))
                    throw bad("Boolean cutters must be solid primitive meshes without patterns or modifiers; arrays are allowed");
            }
            if (node.has("role") && node.get("role").getAsString().equals("cutter")
                    && (node.has("pattern") || !rawCuts(node).isEmpty() || node.has("fill") && !node.get("fill").getAsString().equals("solid")))
                throw bad("cutter meshes must be solid and have no patterns or modifiers");
        }
    }

    private int checkComponent(String name, Set<String> active) {
        // 共用子组件只检查一次；有向无环图不能因重复引用在预检阶段变成指数级递归。
        if (componentDepths.containsKey(name)) return componentDepths.get(name);
        if (active.size() >= 8 || !active.add(name)) throw bad("component references form a cycle or exceed eight levels");
        int depth = 1;
        for (var entry : components.getAsJsonObject(name).getAsJsonArray("objects")) {
            JsonObject node = entry.getAsJsonObject();
            if (node.get("type").getAsString().equals("INSTANCE")) depth = Math.max(depth, 1 + checkComponent(node.get("component").getAsString(), active));
        }
        if (depth > 8) throw bad("component references exceed eight levels");
        active.remove(name); componentDepths.put(name, depth); return depth;
    }

    private List<Leaf> expandList(JsonArray objects, String scope, BuildingModelTransform parent, List<String> inheritedCuts,
            List<JsonObject> mappings, String stateAxes, int depth) {
        if (depth > 8) throw bad("component nesting exceeds eight levels");
        var all = new ArrayList<Leaf>();
        for (var value : objects) {
            BlueprintFormats.checkInterrupted(); JsonObject node = value.getAsJsonObject();
            String key = scope + node.get("name").getAsString(); nodes.put(key, node);
            if (key.length() > 256) throw bad("expanded object paths must fit the 256-character inspection name");
            List<String> cuts = rawCuts(node).stream().map(name -> scope + name).toList(); cutterReferences.addAll(cuts);
            if (node.has("role") && node.get("role").getAsString().equals("cutter")) cutterReferences.add(key);
            var collected = new ArrayList<Leaf>();
            int[] count = {1, 1, 1}; Vec3 step = Vec3.ZERO; Set<String> skip = new HashSet<>();
            boolean array = node.has("array");
            if (array) {
                JsonObject repeat = node.getAsJsonObject("array");
                for (int axis = 0; axis < 3; axis++) count[axis] = repeat.getAsJsonArray("count").get(axis).getAsInt();
                step = BuildingModelTransform.vector(repeat, "step", Vec3.ZERO);
                if (repeat.has("skip")) for (var index : repeat.getAsJsonArray("skip")) {
                    JsonArray at = index.getAsJsonArray(); skip.add(index(at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt()));
                }
            }
            for (int z = 0; z < count[2]; z++) for (int y = 0; y < count[1]; y++) for (int x = 0; x < count[0]; x++) {
                if (skip.contains(index(x, y, z))) continue;
                BlueprintFormats.checkInterrupted();
                if (++expandedNodes > BuildingModelSchema.limit()) throw bad("expanded model exceeds the component budget");
                String path = key + (array ? "[" + index(x, y, z) + "]" : "");
                if (path.length() > 256) throw bad("expanded instance path exceeds 256 characters");
                BuildingModelTransform transform = parent.then(BuildingModelTransform.local(node, blender, new Vec3(x * step.x, y * step.y, z * step.z)));
                String axes = node.has("block_state_axes") ? node.get("block_state_axes").getAsString() : stateAxes;
                List<Leaf> instance;
                if (node.get("type").getAsString().equals("INSTANCE")) {
                    var nextMaps = new ArrayList<JsonObject>(); if (node.has("material_map")) nextMaps.add(node.getAsJsonObject("material_map")); nextMaps.addAll(mappings);
                    var nextCuts = new ArrayList<>(inheritedCuts); nextCuts.addAll(cuts);
                    instance = expandList(components.getAsJsonObject(node.get("component").getAsString()).getAsJsonArray("objects"),
                            path + "/", transform, List.copyOf(nextCuts), List.copyOf(nextMaps), axes, depth + 1);
                } else {
                    Vec3 dimensions = BuildingModelTransform.dimensions(node, blender);
                    String primitive = node.has("primitive") ? node.get("primitive").getAsString() : node.get("type").getAsString();
                    // 每个组件落到总蓝图后按建筑的实际配置半径校验，保持全局锚点不变。
                    Box bounds = transform.bounds(dimensions, BuildingBudgets.current().maxRadius());
                    BuildingModelShape shape = shapeCache.computeIfAbsent(node, ignored -> createShape(primitive, node, dimensions));
                    Leaf leaf = new Leaf(path, node, shape, bounds, transform, cuts, inheritedCuts, mappings, axes);
                    expandedCuts += leaf.cuts().size(); if (expandedCuts > connectionLimit()) throw bad("expanded cutter references exceed the modifier budget");
                    leaves.add(leaf); instance = List.of(leaf);
                }
                collected.addAll(instance);
                if (array) { references.put(path, List.copyOf(instance)); nodes.put(path, node); }
            }
            references.put(key, List.copyOf(collected)); all.addAll(collected);
        }
        return List.copyOf(all);
    }

    List<Leaf> cutters(Leaf leaf) {
        var masks = new LinkedHashMap<String, Leaf>();
        for (String name : leaf.cuts()) for (Leaf cutter : references.get(name)) masks.put(cutter.name, cutter);
        return List.copyOf(masks.values());
    }
    JsonObject material(String name) {
        if (!scene.getAsJsonObject("materials").has(name)) throw bad("unknown model material: " + name);
        return scene.getAsJsonObject("materials").getAsJsonObject(name);
    }
    private BuildingModelShape createShape(String primitive, JsonObject node, Vec3 dimensions) {
        if (!blender || !primitive.equals("convex_polyhedron")) return BuildingModelShape.create(primitive, node, dimensions);
        // 自定义顶点也跟随作者选的坐标系；归一化盒中的 -Y 对应 1-y，不能只换物体尺寸却把网格留在旧轴上。
        JsonObject normalized = node.deepCopy(); JsonArray vertices = new JsonArray();
        for (var value : node.getAsJsonArray("vertices")) {
            JsonArray point = value.getAsJsonArray();
            vertices.add(BuildingModelTransform.json(new Vec3(point.get(0).getAsDouble(), point.get(2).getAsDouble(), 1 - point.get(1).getAsDouble())));
        }
        normalized.add("vertices", vertices); return BuildingModelShape.create(primitive, normalized, dimensions);
    }
    private static int connectionLimit() { return BuildingBudgets.current().maxConnections(); }
    private static String index(int x, int y, int z) { return x + "," + y + "," + z; }
    private static List<String> rawCuts(JsonObject node) {
        if (!node.has("modifiers")) return List.of();
        var result = new ArrayList<String>();
        for (var value : node.getAsJsonArray("modifiers")) {
            String name = value.getAsJsonObject().get("object").getAsString();
            if (result.contains(name)) throw bad("duplicate Boolean cutter reference"); result.add(name);
        }
        return List.copyOf(result);
    }
}
