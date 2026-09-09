// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.maiwithu.maicraft.intent.Goal;

/** Saved revisions keep object identity and the original site across edits and process reloads. */
public final class BuildingSceneStoreTest {
    // 在临时目录保存并重开模型，检查外部修改不改变已存版本、局部编辑保留锚点，并拒绝跨世界、跨维度和非法编辑。
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("building-scene-store-");
        String world = "a".repeat(64), dimension = "minecraft:overworld";
        int[] compilations = {0};
        var store = new BuildingSceneStore(new org.maiwithu.maicraft.intent.persistence.StateIdentity(world, root), source -> {
            compilations[0]++;
            return BuildingSceneCompiler.compile(source);
        });
        var anchor = new Goal.WorldPosition(123, 64, -321, dimension);
        JsonObject scene = json("""
                {"schema_version":1,"coordinate_system":"minecraft_y_up",
                 "materials":{"wood":{"block_id":"minecraft:oak_planks"}},
                 "objects":[{"name":"wall","type":"panel","location":[2.5,1.5,0.5],
                             "dimensions":[5,3,1],"material":"wood"}]}
                """);
        var first = store.save(scene, anchor);
        check(compilations[0] == 1, "ordinary storage still fully validates its input once");
        scene.getAsJsonArray("objects").get(0).getAsJsonObject().addProperty("name", "mutated");
        first.scene().getAsJsonArray("objects").get(0).getAsJsonObject().addProperty("name", "also mutated");
        var reopened = new BuildingSceneStore(root, world).load(first.sceneId(), dimension);
        check(reopened.anchor().equals(anchor) && name(reopened.scene()).equals("wall"), "source and returned JSON cannot mutate the saved design");
        JsonObject edits = json("""
                {"objects":[{"name":"wall","location":[20.5,1.5,0.5]}],
                 "materials":{"wood":{"block_id":"minecraft:spruce_planks"}}}
                """);
        var next = store.update(first.sceneId(), dimension, edits);
        check(compilations[0] == 2, "ordinary updates still fully validate the edited scene once");
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
        JsonObject preparedInput = reopened.scene();
        var prepared = store.prepare(preparedInput);
        int checked = compilations[0];
        preparedInput.remove("objects");
        prepared.blueprint().remove("blocks");
        var preparedSave = store.savePrepared(prepared, anchor);
        var preparedRevision = store.updatePrepared(first.sceneId(), dimension, prepared);
        check(compilations[0] == checked, "saving a validated result must not repeat full scene compilation");
        check(preparedSave.scene().equals(reopened.scene()) && preparedRevision.scene().equals(reopened.scene())
                        && prepared.blueprint().has("blocks"), "input and output mutation cannot poison the validated snapshot");
        check(preparedRevision.parentSceneId().equals(first.sceneId()) && preparedRevision.anchor().equals(anchor),
                "reuse retains immutable revision lineage and the parent's fixed anchor");
        rejects(() -> store.prepare(preparedInput));
        rejects(() -> store.savePrepared(prepared, new Goal.WorldPosition(0, 0, 0, null)));
        rejects(() -> store.updatePrepared(first.sceneId(), "minecraft:the_nether", prepared));
        rejects(() -> new BuildingSceneStore(root, "b".repeat(64)).updatePrepared(first.sceneId(), dimension, prepared));
        // 把测试文件故意加到上限之外，确认读取会拒绝；只影响这个测试创建的临时目录。
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
