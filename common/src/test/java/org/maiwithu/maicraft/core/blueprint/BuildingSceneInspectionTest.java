// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Inspection must expose the actual source objects and transformed bounds without losing cutters. */
public final class BuildingSceneInspectionTest {
    public static void main(String[] args) {
        JsonObject scene = JsonParser.parseString("""
                {"name":"Facade","materials":{"wall":{"block_id":"minecraft:oak_planks"}},"objects":[]}
                """).getAsJsonObject();
        JsonArray objects = scene.getAsJsonArray("objects");
        for (int i = 0; i < 12; i++) {
            JsonObject mesh = new JsonObject(); mesh.addProperty("name", "Object" + i); mesh.addProperty("type", "cube");
            mesh.addProperty("material", "wall"); mesh.add("dimensions", JsonParser.parseString("[1,1,1]"));
            JsonArray at = new JsonArray(); at.add(i + 0.5); at.add(-0.5); at.add(0.5); mesh.add("location", at); objects.add(mesh);
        }
        objects.get(0).getAsJsonObject().add("modifiers", JsonParser.parseString("""
                [{"type":"BOOLEAN","operation":"DIFFERENCE","object":"Object11"}]
                """));
        String original = scene.toString();
        JsonObject first = BuildingSceneInspection.sceneInfo(scene, 0), last = BuildingSceneInspection.sceneInfo(scene, 1);
        check(first.get("name").getAsString().equals("Facade") && first.get("object_count").getAsInt() == 12, "scene name/count changed");
        check(first.getAsJsonArray("objects").size() == 10 && first.get("has_more").getAsBoolean(), "first page must contain ten objects");
        check(last.getAsJsonArray("objects").size() == 2 && !last.get("has_more").getAsBoolean(), "last page lost objects");
        check(BuildingSceneInspection.sceneInfo(scene, Integer.MAX_VALUE).getAsJsonArray("objects").isEmpty(), "page multiplication overflowed");
        JsonObject cutter = BuildingSceneInspection.objectInfo(scene, "Object11");
        check(!cutter.get("visible").getAsBoolean(), "referenced cutter must be hidden even without explicit cutter role");
        check(cutter.get("type").getAsString().equals("MESH") && cutter.get("primitive").getAsString().equals("cube"), "primitive alias did not normalize");
        check(cutter.getAsJsonArray("scale").toString().equals("[1,1,1]") && cutter.getAsJsonArray("rotation").toString().equals("[0,0,0]"), "baked default transform changed");
        check(cutter.getAsJsonObject("minecraft_block_bounds").getAsJsonArray("from").toString().equals("[11,0,0]"), "Minecraft block bounds moved the cutter");
        JsonArray corners = cutter.getAsJsonArray("world_bounding_box");
        check(corners.size() == 8 && corners.asList().stream().anyMatch(c -> c.toString().equals("[11,-1,0]"))
                && corners.asList().stream().anyMatch(c -> c.toString().equals("[12,0,1]")), "bounding box must describe source-space geometric faces");
        check(cutter.get("coordinate_space").getAsString().equals("scene_local"), "local geometry must not pretend to have a world anchor");
        check(original.equals(scene.toString()), "inspection mutated saved source objects");
        try { BuildingSceneInspection.objectInfo(scene, "missing"); throw new AssertionError("missing object was accepted"); }
        catch (IllegalArgumentException expected) { }
        try { BuildingSceneInspection.sceneInfo(scene, -1); throw new AssertionError("negative page was accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("BuildingSceneInspectionTest: named objects, pages, cutter visibility and exact bounds passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
