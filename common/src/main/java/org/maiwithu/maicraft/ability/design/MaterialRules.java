// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import static org.maiwithu.maicraft.ability.design.DesignFormat.bad;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonObject;

/**
 * 材料绑定的纯声明检查：图元引用的材料名、面名、棱名都要真实存在。组件还没摆进图纸时也查，
 * 不等复制到工地才发现半砖或开孔绑错。不依赖世界，也不画任何东西。
 */
public final class MaterialRules {

    private MaterialRules() {}

    static void validate(JsonObject palette, JsonObject node, DesignShape shape, boolean cutter) {
        if (!cutter && !node.has("material")) throw bad("实心图元要写 material：" + node.get("name").getAsString());
        for (String field : Set.of("material", "edge_material")) if (node.has(field)) material(palette, node.get(field).getAsString());
        if (node.has("pattern") && node.getAsJsonObject("pattern").has("materials")) {
            for (var entry : node.getAsJsonObject("pattern").getAsJsonObject("materials").entrySet()) material(palette, entry.getValue().getAsString());
        }
        var faceIds = new HashSet<String>();
        shape.faces().forEach(face -> faceIds.add(face.id()));
        var edgeIds = new HashSet<String>();
        shape.edges().forEach(edge -> edgeIds.add(edge.id()));
        if (node.has("face_materials")) {
            for (var entry : node.getAsJsonObject("face_materials").entrySet()) {
                if (!faceIds.contains(entry.getKey())) throw bad("没有这个面：" + entry.getKey() + "；可用：" + String.join(", ", faceIds.stream().sorted().toList()));
                material(palette, entry.getValue().getAsString());
            }
        }
        if (node.has("edge_materials")) {
            var seen = new HashSet<String>();
            for (var entry : node.getAsJsonObject("edge_materials").entrySet()) {
                String[] pair = entry.getKey().split("\\+", -1);
                Arrays.sort(pair);
                String id = String.join("+", pair);
                if (pair.length != 2 || !edgeIds.contains(id) || !seen.add(id)) throw bad("没有这条棱或写了两次：" + entry.getKey());
                material(palette, entry.getValue().getAsString());
            }
        }
        // 开口面必须是这个图元真实存在的面；打开顶盖不会连带删掉侧壁。
        if (node.has("open_faces")) {
            for (var value : node.getAsJsonArray("open_faces")) {
                if (!node.has("fill") || !node.get("fill").getAsString().equals("hollow") || !faceIds.contains(value.getAsString())) {
                    throw bad("open_faces 要配 fill=hollow，而且面名要存在：" + value.getAsString());
                }
            }
        }
    }

    static void material(JsonObject palette, String name) {
        if (!palette.has(name)) throw bad("材料表里没有 " + name);
    }
}
