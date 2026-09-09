// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.maiwithu.maicraft.intent.Goal;

/** Saved revisions keep object identity and the original site across edits and process reloads. */
public final class BuildingSceneStoreTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("building-scene-store-");
        String world = "a".repeat(64), dimension = "minecraft:overworld";
        var store = new BuildingSceneStore(root, world);
        var anchor = new Goal.WorldPosition(123, 64, -321, dimension);
        JsonObject scene = json("""
                {"schema_version":1,"coordinate_system":"minecraft_y_up",
                 "materials":{"wood":{"block_id":"minecraft:oak_planks"}},
                 "objects":[{"name":"wall","type":"panel","location":[2.5,1.5,0.5],
                             "dimensions":[5,3,1],"material":"wood"}]}
                """);
        var first = store.save(scene, anchor);
        scene.getAsJsonArray("objects").get(0).getAsJsonObject().addProperty("name", "mutated");
        first.scene().getAsJsonArray("objects").get(0).getAsJsonObject().addProperty("name", "also mutated");
        var reopened = new BuildingSceneStore(root, world).load(first.sceneId(), dimension);
        check(reopened.anchor().equals(anchor) && name(reopened.scene()).equals("wall"), "source and returned JSON cannot mutate the saved design");
        JsonObject edits = json("""
                {"objects":[{"name":"wall","location":[20.5,1.5,0.5]}],
                 "materials":{"wood":{"block_id":"minecraft:spruce_planks"}}}
                """);
        var next = store.update(first.sceneId(), dimension, edits);
        check(!next.sceneId().equals(first.sceneId()) && next.parentSceneId().equals(first.sceneId()), "edits produce a new immutable revision");
        check(next.anchor().equals(anchor) && next.scene().getAsJsonArray("objects").get(0).getAsJsonObject().has("dimensions"), "partial transforms retain geometry and fixed world anchor");
        check(store.load(first.sceneId(), dimension).scene().equals(reopened.scene()), "editing does not overwrite the previous revision");
        check(next.scene().getAsJsonObject("materials").getAsJsonObject("wood").get("block_id").getAsString().equals("minecraft:spruce_planks"), "named material edits change the intended material");
        rejects(() -> store.load(first.sceneId(), "minecraft:the_nether"));
        rejects(() -> store.load("../" + first.sceneId(), dimension));
        rejects(() -> new BuildingSceneStore(root, "b".repeat(64)).load(first.sceneId(), dimension));
        Path source = root.resolve("build-scenes").resolve(world).resolve(first.sceneId() + ".json");
        Path foreign = root.resolve("build-scenes").resolve("b".repeat(64)).resolve(first.sceneId() + ".json");
        Files.createDirectories(foreign.getParent()); Files.copy(source, foreign);
        rejects(() -> new BuildingSceneStore(root, "b".repeat(64)).load(first.sceneId(), dimension));
        rejects(() -> store.update(first.sceneId(), dimension, json("{\"remove_objects\":[\"unknown\"]}")));
        rejects(() -> store.update(first.sceneId(), dimension, json("{\"objects\":[{\"name\":\"wall\",\"dimensions\":[2,2,2]}]}")));
        for (String invalid : new String[] {
                "{\"objects\":[{\"name\":\"wall\",\"clicks\":[]}]}",
                "{\"objects\":[{\"name\":\"wall\",\"location\":[{\"ops\":[]},0,0]}]}",
                "{\"objects\":[{\"name\":\"wall\",\"modifiers\":[{\"type\":\"BOOLEAN\",\"operation\":\"DIFFERENCE\",\"object\":\"cut\",\"click\":1}]}]}",
                "{\"materials\":{\"wood\":{\"block_id\":\"minecraft:oak_planks\",\"properties\":{\"axis\":{\"ops\":[]}}}}}",
                "{\"remove_objects\":[\"wall\"],\"objects\":[{\"name\":\"wall\"}]}"})
            rejects(() -> BuildingSceneStore.validateEdits(json(invalid)));
        check(store.load(next.sceneId(), dimension).scene().equals(next.scene()), "failed edits preserve both revisions");
        Files.write(source, new byte[4 * 1024 * 1024 + 1]);
        rejects(() -> store.load(first.sceneId(), dimension));
        System.out.println("BuildingSceneStoreTest: immutable world-bound revisions passed");
    }
    private static String name(JsonObject scene) { return scene.getAsJsonArray("objects").get(0).getAsJsonObject().get("name").getAsString(); }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void rejects(Runnable operation) {
        try { operation.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("invalid scene operation was accepted");
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
