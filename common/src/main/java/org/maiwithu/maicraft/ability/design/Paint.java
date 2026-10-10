// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.maiwithu.maicraft.ability.design.DesignFormat.bad;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.ability.design.DesignTransform.Point;

/**
 * 在图元的真实表面上选材料：先决定空腔，再涂指定的面，最后包棱，图案留孔；复制后的局部方向一起变换。
 * 同一个角落几条棱相遇时近者优先，同距优先明确指定的棱，再按稳定的棱名决定，不随机换材料。
 */
public final class Paint {

    private static final double EPS = 1e-8;

    private final DesignExpansion model;
    private final DesignExpansion.Leaf leaf;
    private final Map<String, JsonObject> cache;
    private final Map<String, String> faces = new LinkedHashMap<>();
    private final Map<String, String> edges = new LinkedHashMap<>();
    private final Set<String> open = new HashSet<>();
    private final boolean hollow;
    private final double thickness;
    private final double edgeWidth;
    private final String fillMaterial;
    private final String allEdges;
    private final PanelPattern pattern;

    Paint(DesignExpansion model, DesignExpansion.Leaf leaf, Map<String, JsonObject> cache) {
        this.model = model;
        this.leaf = leaf;
        this.cache = cache;
        JsonObject node = leaf.node();
        pattern = node.has("pattern") ? new PanelPattern(node.getAsJsonObject("pattern"), DesignTransform.dimensions(node, model.blender())) : null;
        fillMaterial = node.has("material") ? node.get("material").getAsString() : null;
        if (fillMaterial == null && !model.cutterLeaves.contains(leaf.name())) throw bad("实心图元要写 material：" + leaf.name());
        allEdges = node.has("edge_material") ? node.get("edge_material").getAsString() : null;
        hollow = node.has("fill") && node.get("fill").getAsString().equals("hollow");
        thickness = node.has("wall_thickness") ? node.get("wall_thickness").getAsDouble() : 1;
        edgeWidth = node.has("edge_width") ? node.get("edge_width").getAsDouble() : 1;
        Set<String> faceNames = new HashSet<>();
        leaf.shape().faces().forEach(face -> faceNames.add(face.id()));
        Set<String> edgeNames = new HashSet<>();
        leaf.shape().edges().forEach(edge -> edgeNames.add(edge.id()));
        if (node.has("face_materials")) {
            node.getAsJsonObject("face_materials").entrySet().forEach(entry -> {
                if (!faceNames.contains(entry.getKey())) throw bad(leaf.name() + " 没有这个面：" + entry.getKey());
                faces.put(entry.getKey(), entry.getValue().getAsString());
            });
        }
        if (node.has("edge_materials")) {
            node.getAsJsonObject("edge_materials").entrySet().forEach(entry -> {
                String[] pair = entry.getKey().split("\\+", -1);
                Arrays.sort(pair);
                String key = String.join("+", pair);
                if (pair.length != 2 || !edgeNames.contains(key) || edges.putIfAbsent(key, entry.getValue().getAsString()) != null) {
                    throw bad(leaf.name() + " 没有这条棱或写了两次：" + entry.getKey());
                }
            });
        }
        if (node.has("open_faces")) {
            for (var value : node.getAsJsonArray("open_faces")) {
                if (!hollow || !faceNames.contains(value.getAsString())) throw bad("open_faces 要配 fill=hollow，而且面名要存在：" + value.getAsString());
                open.add(value.getAsString());
            }
        }
        if (fillMaterial != null) checkMaterial(fillMaterial);
        if (allEdges != null) checkMaterial(allEdges);
        faces.values().forEach(this::checkMaterial);
        edges.values().forEach(this::checkMaterial);
        if (pattern != null) pattern.materials.values().forEach(this::checkMaterial);
    }

    /** 图元局部坐标 {@code local} 处该放什么；null 表示这一格留空（空腔或图案的孔）。 */
    JsonObject at(Vec3 local, Point cell) {
        String chosen = materialAt(local);
        return chosen == null ? null : MaterialStates.resolve(model, mapped(chosen), leaf, cell, cache);
    }

    private String materialAt(Vec3 local) {
        // 开口面不提供壳层；其他面的侧壁仍延伸到开口边缘，空腔明确留给后面清空。
        if (hollow && leaf.shape().faces().stream().noneMatch(face -> !open.contains(face.id()) && face.distance(local) <= thickness + EPS)) return null;
        String chosen = fillMaterial, nearestFace = null;
        double faceDistance = Double.POSITIVE_INFINITY;
        for (var face : leaf.shape().faces()) {
            if (!faces.containsKey(face.id()) || open.contains(face.id())) continue;
            double distance = face.distance(local);
            if (distance <= (hollow ? thickness : 1) + EPS
                    && (distance < faceDistance - EPS || Math.abs(distance - faceDistance) <= EPS && (nearestFace == null || face.id().compareTo(nearestFace) < 0))) {
                faceDistance = distance;
                nearestFace = face.id();
                chosen = faces.get(face.id());
            }
        }
        String nearestEdge = null;
        double edgeDistance = Double.POSITIVE_INFINITY;
        boolean explicitEdge = false;
        if (allEdges != null || !edges.isEmpty()) {
            for (var edge : leaf.shape().edges()) {
                String material = edges.getOrDefault(edge.id(), allEdges);
                if (material == null || edge.faces().stream().allMatch(open::contains)) continue;
                double distance = edge.distance(local);
                boolean explicit = edges.containsKey(edge.id());
                boolean nearer = distance < edgeDistance - EPS;
                boolean tied = Math.abs(distance - edgeDistance) <= EPS && (explicit && !explicitEdge
                        || explicit == explicitEdge && (nearestEdge == null || edge.id().compareTo(nearestEdge) < 0));
                if (distance <= edgeWidth + EPS && (nearer || tied)) {
                    edgeDistance = distance;
                    nearestEdge = edge.id();
                    explicitEdge = explicit;
                    chosen = material;
                }
            }
        }
        // 先涂面与包棱，再按图案留孔或盖上指定材料；留的孔和布尔开孔一样不会抹掉别的对象。
        return pattern != null ? pattern.materialAt(local, chosen) : chosen;
    }

    /** 这一格要做多少次面与棱的比较，用来算工作量。 */
    int sampleCost() {
        return leaf.shape().faces().size() * 2 + (allEdges != null || !edges.isEmpty() ? leaf.shape().edges().size() : 0) + (pattern != null ? 2 : 0);
    }

    private String mapped(String name) {
        for (JsonObject map : leaf.materialMaps()) if (map.has(name)) name = map.get(name).getAsString();
        return name;
    }

    private void checkMaterial(String name) {
        model.material(name);
        model.material(mapped(name));
    }
}
