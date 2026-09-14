// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;

/** 给组合建模回归提供明确的材料和坐标样本；只生成作者输入，不操作玩家、世界或背包。 */
final class BuildingModelTestData {
    private BuildingModelTestData() {}
    static JsonObject scene(JsonObject... nodes) {
        JsonObject scene = json("""
                {"schema_version":2,"coordinate_system":"minecraft_y_up","materials":{
                 "Body":{"block_id":"minecraft:stone"},"Glass":{"block_id":"minecraft:glass"},
                 "Trim":{"block_id":"minecraft:quartz_block"},"Accent":{"block_id":"minecraft:gold_block"},
                 "Top":{"block_id":"minecraft:red_concrete"},"Wood":{"block_id":"minecraft:oak_log","properties":{"axis":"z"}},
                 "Stair":{"block_id":"minecraft:oak_stairs","properties":{"facing":"north","half":"bottom"}}},"objects":[]}
                """);
        for (var node : nodes) scene.getAsJsonArray("objects").add(node); return scene;
    }
    static JsonObject mesh(String name, String primitive, double[] center, int[] size, String material) {
        JsonObject node = new JsonObject(); node.addProperty("name", name); node.addProperty("type", "MESH"); node.addProperty("primitive", primitive);
        JsonArray location = new JsonArray(), dimensions = new JsonArray(); for (double value : center) location.add(value); for (int value : size) dimensions.add(value);
        node.add("location", location); node.add("dimensions", dimensions); if (material != null) node.addProperty("material", material); return node;
    }
    static JsonObject instance(String name, String component, int x, int y, int z) {
        JsonObject node = new JsonObject(); node.addProperty("name", name); node.addProperty("type", "INSTANCE"); node.addProperty("component", component);
        node.add("location", JsonParser.parseString("[" + x + "," + y + "," + z + "]")); return node;
    }
    static void component(JsonObject scene, String name, JsonObject... nodes) {
        if (!scene.has("components")) scene.add("components", new JsonObject());
        JsonArray objects = new JsonArray(); for (var node : nodes) objects.add(node);
        JsonObject component = new JsonObject(); component.add("objects", objects); scene.getAsJsonObject("components").add(name, component);
    }
    static Map<String, JsonObject> cells(JsonObject scene) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (var value : BuildingSceneCompiler.compile(scene).getAsJsonArray("blocks")) {
            JsonObject cell = value.getAsJsonObject(); check(result.put(cell.get("offset").toString(), cell) == null, "最终蓝图不能有重复坐标");
        }
        return result;
    }
    static JsonObject at(Map<String, JsonObject> cells, int x, int y, int z) { return cells.get("[" + x + "," + y + "," + z + "]"); }
    static String block(Map<String, JsonObject> cells, int x, int y, int z) {
        JsonObject value = at(cells, x, y, z); return value == null ? "unspecified" : value.get("block_id").getAsString();
    }
    static long count(Map<String, JsonObject> cells, String block) { return cells.values().stream().filter(cell -> cell.get("block_id").getAsString().equals(block)).count(); }
    static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    static void rejects(Runnable operation, String message) {
        try { operation.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
