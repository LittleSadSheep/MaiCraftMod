// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;

/** 检查模型变成方块后，墙厚、开窗、独立玻璃、后写材质和两种坐标旋转都保持预期；也验证非法模型被拒绝。 */
public final class BuildingSceneCompilerTest {
    public static void main(String[] args) {
        JsonObject scene = scene(); scene.addProperty("name", "Window wall"); String original = scene.toString();
        JsonObject compiled = BuildingSceneCompiler.compile(scene);
        Map<String, JsonObject> cells = cells(compiled);
        check(cells.size() == 20, "wall must occupy exactly five by four cells");
        check(count(cells, "minecraft:oak_planks") == 14 && count(cells, "minecraft:air") == 6, "Boolean window must clear six cells");
        check(cells.containsKey("[0,0,0]") && cells.containsKey("[4,3,0]"), "Blender Z-up conversion shifted wall faces");
        check(original.equals(scene.toString()), "compiler mutated the editable source model");
        check(compiled.equals(BuildingSceneCompiler.compile(scene)), "compilation must be deterministic");

        // 往刚挖出的窗洞里加入独立玻璃，再交换对象顺序，确认开窗不会把别的对象一起擦掉。
        JsonObject glass = mesh("Glass", "glass", "[2.5,-0.5,2]", "[3,1,2]");
        scene.getAsJsonArray("objects").add(glass);
        Map<String, JsonObject> filled = cells(BuildingSceneCompiler.compile(scene));
        check(count(filled, "minecraft:blue_stained_glass") == 6 && count(filled, "minecraft:air") == 0, "glass did not fill the cut");
        JsonArray reversed = new JsonArray(); reversed.add(glass);
        reversed.add(scene.getAsJsonArray("objects").get(1)); reversed.add(scene.getAsJsonArray("objects").get(0));
        scene.add("objects", reversed);
        check(filled.equals(cells(BuildingSceneCompiler.compile(scene))), "a scoped cut erased another mesh when object order changed");

        JsonObject overlap = mesh("Trim", "trim", "[0.5,-0.5,0.5]", "[1,1,1]");
        scene.getAsJsonArray("objects").add(overlap);
        JsonObject winner = cells(BuildingSceneCompiler.compile(scene)).get("[0,0,0]");
        check(winner.get("block_id").getAsString().equals("minecraft:spruce_log"), "later solid must win overlaps without palette substitution");
        check(winner.getAsJsonObject("properties").get("axis").getAsString().equals("z"), "explicit material properties changed");

        // 同一盒子分别按 Blender 和游戏的竖直轴旋转；只改变几何，木头的显式轴向仍按世界坐标解释。
        JsonObject rotated = mesh("Roof", "trim", "[3,2,1]", "[4,2,2]");
        rotated.add("rotation_euler", JsonParser.parseString("[0,0,1.5707963267948966]"));
        JsonArray objects = new JsonArray(); objects.add(rotated); scene.add("objects", objects);
        cells = cells(BuildingSceneCompiler.compile(scene));
        check(cells.size() == 16 && cells.containsKey("[2,0,-4]") && cells.containsKey("[3,1,-1]"), "centered yaw did not rotate dimensions before voxel mapping");
        check(cells.values().stream().allMatch(c -> c.getAsJsonObject("properties").get("axis").getAsString().equals("z")), "mesh rotation must not reinterpret world-axis state properties");
        scene.addProperty("coordinate_system", "minecraft_y_up");
        rotated.add("rotation_euler", JsonParser.parseString("[0,1.5707963267948966,0]"));
        cells = cells(BuildingSceneCompiler.compile(scene));
        check(cells.containsKey("[2,1,-1]") && cells.containsKey("[3,2,2]"), "explicit Minecraft Y-up transform failed");
        scene.remove("coordinate_system");
        rotated.add("rotation_euler", JsonParser.parseString("[0,0,0]"));
        rotated.add("dimensions", JsonParser.parseString("[1,1,1]"));
        rotated.add("location", JsonParser.parseString("[0.5,-128.5,0.5]"));
        check(cells(BuildingSceneCompiler.compile(scene)).containsKey("[0,0,128]"), "Blender face inversion rejected a valid edge voxel");

        // 依次检查未知坐标系、多余字段、不对齐网格、斜转、小数尺寸、极大数字、坏引用和超限场景。
        rejects(s -> s.addProperty("coordinate_system", "guess"));
        rejects(s -> s.getAsJsonArray("objects").get(0).getAsJsonObject().addProperty("scale", 2));
        rejects(s -> s.getAsJsonArray("objects").get(0).getAsJsonObject().add("location", JsonParser.parseString("[0,0,0]")));
        rejects(s -> s.getAsJsonArray("objects").get(0).getAsJsonObject().add("rotation_euler", JsonParser.parseString("[0,0,0.7853981633974483]")));
        rejects(s -> s.getAsJsonArray("objects").get(0).getAsJsonObject().add("rotation_euler", JsonParser.parseString("[1.5707963267948966,0,0]")));
        rejects(s -> s.getAsJsonArray("objects").get(0).getAsJsonObject().add("dimensions", JsonParser.parseString("[5.1,1,4]")));
        rejects(s -> s.getAsJsonArray("objects").get(0).getAsJsonObject().add("location", JsonParser.parseString("[1e9999999,0,0]")));
        rejects(s -> s.getAsJsonArray("objects").get(0).getAsJsonObject().addProperty("material", "missing"));
        rejects(s -> s.getAsJsonArray("objects").remove(1));
        rejects(s -> s.getAsJsonArray("objects").add(s.getAsJsonArray("objects").get(0).deepCopy()));
        rejects(s -> s.getAsJsonArray("objects").get(1).getAsJsonObject().add("modifiers", s.getAsJsonArray("objects").get(0).getAsJsonObject().get("modifiers").deepCopy()));
        rejects(s -> { JsonObject large = s.getAsJsonArray("objects").get(0).getAsJsonObject(); large.addProperty("primitive", "cube"); large.add("dimensions", JsonParser.parseString("[32,32,32]")); large.add("location", JsonParser.parseString("[16,16,16]")); });
        rejects(s -> {
            JsonArray disjoint = new JsonArray();
            for (int i = 0; i < 3; i++) disjoint.add(mesh("Volume" + i, "wall", "[" + (10 + i * 32) + ",10,10]", "[20,20,20]"));
            s.add("objects", disjoint); BuildingSceneCompiler.validateWire(s); // Structural checks do not enumerate the union.
        });
        System.out.println("BuildingSceneCompilerTest: exact mesh transforms, materials and scoped Boolean cuts passed");
    }

    private static JsonObject scene() {
        return JsonParser.parseString("""
                {"schema_version":1,"materials":{
                  "wall":{"block_id":"minecraft:oak_planks"},
                  "glass":{"block_id":"minecraft:blue_stained_glass"},
                  "trim":{"block_id":"minecraft:spruce_log","properties":{"axis":"z"}}},
                 "objects":[
                  {"name":"Wall","type":"MESH","primitive":"panel","material":"wall",
                   "location":[2.5,-0.5,2],"dimensions":[5,1,4],
                   "modifiers":[{"type":"BOOLEAN","operation":"DIFFERENCE","object":"WindowCut"}]},
                  {"name":"WindowCut","type":"MESH","primitive":"cube",
                   "location":[2.5,-0.5,2],"dimensions":[3,3,2]}]}
                """).getAsJsonObject();
    }
    private static JsonObject mesh(String name, String material, String location, String dimensions) {
        JsonObject mesh = new JsonObject(); mesh.addProperty("name", name); mesh.addProperty("type", "MESH");
        mesh.addProperty("primitive", "cube"); mesh.addProperty("material", material);
        mesh.add("location", JsonParser.parseString(location)); mesh.add("dimensions", JsonParser.parseString(dimensions));
        return mesh;
    }
    private static Map<String, JsonObject> cells(JsonObject blueprint) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        blueprint.getAsJsonArray("blocks").forEach(e -> { JsonObject c = e.getAsJsonObject(); check(result.put(c.get("offset").toString(), c) == null, "duplicate compiled coordinate"); });
        return result;
    }
    private static long count(Map<String, JsonObject> cells, String id) { return cells.values().stream().filter(c -> c.get("block_id").getAsString().equals(id)).count(); }
    private static void rejects(java.util.function.Consumer<JsonObject> edit) {
        JsonObject invalid = scene(); edit.accept(invalid);
        try { BuildingSceneCompiler.compile(invalid); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid scene was accepted: " + invalid);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
