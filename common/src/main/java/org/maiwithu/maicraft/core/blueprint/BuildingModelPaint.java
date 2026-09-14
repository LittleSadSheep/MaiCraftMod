// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import static org.maiwithu.maicraft.core.blueprint.BuildingSceneGeometry.*;

/** 在图元实际表面选择材质：先决定空腔，再涂指定面，最后包边；复制后的本地方向同时变换。 */
final class BuildingModelPaint {
    private static final double EPS = 1e-8;
    private final BuildingModelExpansion model;
    private final BuildingModelExpansion.Leaf leaf;
    private final Map<String, JsonObject> cache;
    private final Map<String, String> faces = new LinkedHashMap<>(), edges = new LinkedHashMap<>();
    private final Set<String> open = new HashSet<>();
    private final boolean hollow;
    private final double thickness, edgeWidth;
    private final String body, allEdges;

    BuildingModelPaint(BuildingModelExpansion model, BuildingModelExpansion.Leaf leaf, Map<String, JsonObject> cache) {
        this.model = model; this.leaf = leaf; this.cache = cache; JsonObject node = leaf.node();
        body = node.has("material") ? node.get("material").getAsString() : null;
        if (body == null && !model.cutterLeaves.contains(leaf.name())) throw bad("solid model needs material: " + leaf.name());
        allEdges = node.has("edge_material") ? node.get("edge_material").getAsString() : null;
        hollow = node.has("fill") && node.get("fill").getAsString().equals("hollow");
        thickness = node.has("wall_thickness") ? node.get("wall_thickness").getAsDouble() : 1;
        edgeWidth = node.has("edge_width") ? node.get("edge_width").getAsDouble() : 1;
        Set<String> faceNames = new HashSet<>(); leaf.shape().faces().forEach(face -> faceNames.add(face.id()));
        Set<String> edgeNames = new HashSet<>(); leaf.shape().edges().forEach(edge -> edgeNames.add(edge.id()));
        if (node.has("face_materials")) node.getAsJsonObject("face_materials").entrySet().forEach(entry -> {
            if (!faceNames.contains(entry.getKey())) throw bad("unknown face " + entry.getKey() + " on " + leaf.name());
            faces.put(entry.getKey(), entry.getValue().getAsString());
        });
        if (node.has("edge_materials")) node.getAsJsonObject("edge_materials").entrySet().forEach(entry -> {
            String[] pair = entry.getKey().split("\\+", -1); Arrays.sort(pair); String key = String.join("+", pair);
            if (pair.length != 2 || !edgeNames.contains(key) || edges.putIfAbsent(key, entry.getValue().getAsString()) != null)
                throw bad("unknown or duplicate edge " + entry.getKey() + " on " + leaf.name());
        });
        if (node.has("open_faces")) for (var value : node.getAsJsonArray("open_faces")) {
            if (!hollow || !faceNames.contains(value.getAsString())) throw bad("open_faces needs a hollow model and existing face IDs");
            open.add(value.getAsString());
        }
        if (body != null) checkMaterial(body); if (allEdges != null) checkMaterial(allEdges);
        faces.values().forEach(this::checkMaterial); edges.values().forEach(this::checkMaterial);
    }

    JsonObject at(Vec3 local) {
        // 开口面不提供封闭壳层；其他面的侧壁仍可延伸到开口边缘，空腔明确留给后续清障。
        if (hollow && leaf.shape().faces().stream().noneMatch(face -> !open.contains(face.id()) && face.distance(local) <= thickness + EPS)) return null;
        String chosen = body, nearestFace = null; double faceDistance = Double.POSITIVE_INFINITY;
        for (var face : leaf.shape().faces()) {
            if (!faces.containsKey(face.id()) || open.contains(face.id())) continue;
            double distance = face.distance(local);
            if (distance <= (hollow ? thickness : 1) + EPS
                    && (distance < faceDistance - EPS || Math.abs(distance - faceDistance) <= EPS && (nearestFace == null || face.id().compareTo(nearestFace) < 0))) {
                faceDistance = distance; nearestFace = face.id(); chosen = faces.get(face.id());
            }
        }
        String nearestEdge = null; double edgeDistance = Double.POSITIVE_INFINITY; boolean explicitEdge = false;
        if (allEdges != null || !edges.isEmpty()) for (var edge : leaf.shape().edges()) {
            String material = edges.getOrDefault(edge.id(), allEdges);
            if (material == null || edge.faces().stream().allMatch(open::contains)) continue;
            double distance = edge.distance(local); boolean explicit = edges.containsKey(edge.id());
            // 同一角落多条棱相遇时，较近者优先；同距优先明确指定的棱，最后按稳定ID决定，不随机换材质。
            boolean nearer = distance < edgeDistance - EPS;
            boolean tied = Math.abs(distance - edgeDistance) <= EPS && (explicit && !explicitEdge
                    || explicit == explicitEdge && (nearestEdge == null || edge.id().compareTo(nearestEdge) < 0));
            if (distance <= edgeWidth + EPS && (nearer || tied)) {
                edgeDistance = distance; nearestEdge = edge.id(); explicitEdge = explicit; chosen = material;
            }
        }
        return resolve(chosen);
    }

    int sampleCost() { return leaf.shape().faces().size() * 2 + (allEdges != null || !edges.isEmpty() ? leaf.shape().edges().size() : 0); }
    private String mapped(String name) {
        for (JsonObject map : leaf.materialMaps()) if (map.has(name)) name = map.get(name).getAsString();
        return name;
    }
    private void checkMaterial(String name) { model.material(name); model.material(mapped(name)); }
    private JsonObject resolve(String name) {
        String material = mapped(name);
        String key = material + ":" + leaf.stateAxes() + ":" + Arrays.toString(leaf.transform().axes());
        return cache.computeIfAbsent(key, ignored -> {
            JsonObject source = model.material(material);
            JsonObject state = leaf.stateAxes().equals("minecraft_world") ? source.deepCopy()
                    : BuildingModelBlockStates.transform(source, leaf.transform().axes());
            // 编译、预览和导出共用相同的稀疏状态结构；空属性对象只作规范化，不增加作者未要求的方向。
            if (!state.has("properties")) state.add("properties", new JsonObject()); return state;
        });
    }
}
