// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** 把已完成的局部改造合回原设计；省略的机件保留，显式空气保留为拆除目标，不读取或改写世界。 */
final class MachineBlueprintRevision {
    private MachineBlueprintRevision() {}

    static JsonObject merge(JsonObject previous, JsonObject patch) {
        JsonObject merged = previous.deepCopy();
        patch.entrySet().stream().filter(entry -> !Set.of("blocks", "assembly", "external_inputs").contains(entry.getKey()))
                .forEach(entry -> merged.add(entry.getKey(), entry.getValue().deepCopy()));
        var blocks = new LinkedHashMap<String, JsonObject>();
        for (var raw : previous.getAsJsonArray("blocks")) { var row = raw.getAsJsonObject(); blocks.put(slot(row), row.deepCopy()); }
        var changed = new LinkedHashMap<String, JsonArray>();
        for (var raw : patch.getAsJsonArray("blocks")) {
            var row = raw.getAsJsonObject(); String at = row.get("offset").toString(), slot = slot(row);
            if (!row.equals(blocks.get(slot))) changed.put(at, row.getAsJsonArray("offset"));
            // 普通方块替换整格；部件只替换该安装面，保留同一宿主其他面的部件。
            blocks.entrySet().removeIf(entry -> entry.getValue().get("offset").toString().equals(at)
                    && (!row.has("part") || !entry.getValue().has("part")));
            blocks.put(slot, row.deepCopy());
        }
        JsonArray cells = new JsonArray(); blocks.values().forEach(cells::add); merged.add("blocks", cells);
        if (previous.has("assembly") || patch.has("assembly")) {
            var assembly = new JsonObject();
            for (String kind : List.of("installations", "processing")) {
                var entries = new LinkedHashMap<String, JsonObject>();
                for (var raw : array(object(previous, "assembly"), kind)) {
                    var row = raw.getAsJsonObject();
                    // 拆换传动段或加工端点后，旧安装关系不再可执行；仅保留完全未触及的关系。
                    String first = kind.equals("processing") ? "processor" : "first";
                    String second = kind.equals("processing") ? "surface" : "second";
                    boolean touched = changed.values().stream().anyMatch(at -> within(at, row.getAsJsonArray(first), row.getAsJsonArray(second)));
                    if (!touched) entries.put(relationKey(kind, row), row.deepCopy());
                }
                for (var raw : array(object(patch, "assembly"), kind)) {
                    var row = raw.getAsJsonObject(); entries.put(relationKey(kind, row), row.deepCopy());
                }
                var rows = new JsonArray(); entries.values().forEach(rows::add); assembly.add(kind, rows);
            }
            merged.add("assembly", assembly);
        }
        if (previous.has("external_inputs") || patch.has("external_inputs")) {
            var inputs = new LinkedHashMap<String, JsonObject>();
            for (var raw : array(previous, "external_inputs")) {
                var row = raw.getAsJsonObject();
                // 输入所在机件被拆换时去掉旧端口描述，作者本次重新声明的同名入口会在下方补回。
                if (!changed.containsKey(row.get("offset").toString())) inputs.put(row.get("id").getAsString(), row.deepCopy());
            }
            for (var raw : array(patch, "external_inputs")) {
                var row = raw.getAsJsonObject(); inputs.put(row.get("id").getAsString(), row.deepCopy());
            }
            var rows = new JsonArray(); inputs.values().forEach(rows::add); merged.add("external_inputs", rows);
        }
        return merged;
    }

    private static String slot(JsonObject row) { return row.get("offset") + "/" + (row.has("part") ? row.get("part").getAsString() : "block"); }
    private static JsonObject object(JsonObject value, String key) { return value.has(key) ? value.getAsJsonObject(key) : new JsonObject(); }
    private static JsonArray array(JsonObject value, String key) { return value.has(key) ? value.getAsJsonArray(key) : new JsonArray(); }
    private static String relationKey(String kind, JsonObject row) {
        if (kind.equals("processing")) return row.get("processor").toString();
        var ends = new ArrayList<>(List.of(row.get("first").toString(), row.get("second").toString()));
        ends.sort(String::compareTo); return row.get("type") + ":" + ends;
    }
    private static boolean within(JsonArray point, JsonArray first, JsonArray second) {
        for (int axis = 0; axis < 3; axis++) {
            int p = point.get(axis).getAsInt(), a = first.get(axis).getAsInt(), b = second.get(axis).getAsInt();
            if (p < Math.min(a, b) || p > Math.max(a, b)) return false;
        }
        return true;
    }
}
