// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import static org.maiwithu.maicraft.core.blueprint.BuildingSceneGeometry.*;

/** 原材质及面棱绑定的纯声明检查，组件尚未放进场景时也能发现错误，不依赖游戏世界或绘制流程。 */
final class BuildingModelMaterialRules {
    private BuildingModelMaterialRules() {}
    static void validate(JsonObject palette, JsonObject node, BuildingModelShape shape, boolean cutter) {
        if (!cutter && !node.has("material")) throw bad("solid model needs material: " + node.get("name").getAsString());
        for (String field : Set.of("material", "edge_material")) if (node.has(field)) material(palette, node.get(field).getAsString());
        // 未摆出的网格组件也必须引用真实材质，不能等复制到工地后才发现半砖或孔洞绑定写错。
        if (node.has("pattern") && node.getAsJsonObject("pattern").has("materials"))
            for (var entry : node.getAsJsonObject("pattern").getAsJsonObject("materials").entrySet())
                material(palette, entry.getValue().getAsString());
        var faceIds = new HashSet<String>(); shape.faces().forEach(face -> faceIds.add(face.id()));
        var edgeIds = new HashSet<String>(); shape.edges().forEach(edge -> edgeIds.add(edge.id()));
        if (node.has("face_materials")) for (var entry : node.getAsJsonObject("face_materials").entrySet()) {
            if (!faceIds.contains(entry.getKey())) throw bad("unknown face " + entry.getKey()); material(palette, entry.getValue().getAsString());
        }
        if (node.has("edge_materials")) {
            var seen = new HashSet<String>();
            for (var entry : node.getAsJsonObject("edge_materials").entrySet()) {
                String[] pair = entry.getKey().split("\\+", -1); Arrays.sort(pair); String id = String.join("+", pair);
                if (pair.length != 2 || !edgeIds.contains(id) || !seen.add(id)) throw bad("unknown or duplicate edge " + entry.getKey());
                material(palette, entry.getValue().getAsString());
            }
        }
        // 空心开口必须指向这个图元实际存在的面；开放顶盖不会连带删除侧壁。
        if (node.has("open_faces")) for (var value : node.getAsJsonArray("open_faces"))
            if (!node.has("fill") || !node.get("fill").getAsString().equals("hollow") || !faceIds.contains(value.getAsString()))
                throw bad("open_faces needs a hollow model and existing face IDs");
    }
    private static void material(JsonObject palette, String name) { if (!palette.has(name)) throw bad("unknown model material: " + name); }
}
