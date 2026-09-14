// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.blueprint;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 组件和阵列编辑先验证完整模型，再发布新版本；旧作者节点、组件定义和世界锚点都保持不变。 */
public final class BuildingSceneV2StoreTest {
    private static final String WORLD = "d".repeat(64), DIMENSION = "minecraft:overworld";
    private static final Goal.WorldPosition ANCHOR = new Goal.WorldPosition(32, 70, -24, DIMENSION);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        upgradeIsExplicitAndKeepsOriginalSource();
        componentReplacementAndRemovalAreAtomic();
        invalidNestedEditsAreRejectedBeforeStorage();
        System.out.println("BuildingSceneV2StoreTest: passed");
    }

    private static void upgradeIsExplicitAndKeepsOriginalSource() throws Exception {
        Path directory = Files.createTempDirectory("scene-v2-upgrade-"); int[] compilations = {0};
        var store = new BuildingSceneStore(new StateIdentity(WORLD, directory), scene -> {
            compilations[0]++; return BuildingSceneCompiler.compile(scene);
        });
        JsonObject original = v1(); var first = store.save(original, ANCHOR);
        JsonObject edits = json("""
                {"schema_version":2,"materials":{"accent":{"block_id":"minecraft:stone_bricks"}},
                 "components":{"Column":{"objects":[{"name":"Part","type":"MESH","primitive":"cube",
                    "location":[0.5,1.5,0.5],"dimensions":[1,3,1],"material":"wood"}]}},
                 "objects":[{"name":"Columns","type":"INSTANCE","component":"Column","location":[4,0,0],
                    "mirror":["x"],"material_map":{"wood":"accent"},"array":{"count":[3,1,1],"step":[3,0,0],"skip":[[1,0,0]]}}]}
                """);
        JsonObject noUpgrade = edits.deepCopy(); noUpgrade.remove("schema_version");
        rejects(() -> store.update(first.sceneId(), DIMENSION, noUpgrade));
        check(files(directory) == 1, "v1 没有显式升级时不能写入组件/阵列版本");
        var revised = store.update(first.sceneId(), DIMENSION, edits);
        check(compilations[0] == 2, "通过的升级只完整编译一次，失败的合并不进入保存编译");
        check(revised.parentSceneId().equals(first.sceneId()) && revised.anchor().equals(ANCHOR)
                && !revised.sceneId().equals(first.sceneId()), "显式升级生成新编号并保留父版本和原锚点");
        check(revised.scene().get("schema_version").getAsInt() == 2
                && revised.scene().getAsJsonArray("objects").size() == 2
                && revised.scene().getAsJsonObject("components").getAsJsonObject("Column").getAsJsonArray("objects").size() == 1,
                "持久化保留作者组件和 INSTANCE，不把展开后的方块或复制节点覆盖源模型");
        edits.getAsJsonObject("components").remove("Column"); revised.scene().remove("objects");
        var reopened = new BuildingSceneStore(directory, WORLD).load(revised.sceneId(), DIMENSION);
        check(reopened.scene().has("objects") && reopened.scene().getAsJsonObject("components").has("Column"),
                "调用方改动编辑输入或返回副本不能污染已经保存的 v2 场景");
        check(store.load(first.sceneId(), DIMENSION).scene().equals(original), "升级不会改写 v1 场景或它的原材质语义");
        rejects(() -> store.update(revised.sceneId(), DIMENSION, json("{\"schema_version\":1}")));
        rejects(() -> store.load(revised.sceneId(), "minecraft:the_nether"));
    }

    private static void componentReplacementAndRemovalAreAtomic() throws Exception {
        Path directory = Files.createTempDirectory("scene-v2-components-"); var store = new BuildingSceneStore(directory, WORLD);
        JsonObject source = v2(); var first = store.save(source, ANCHOR);
        var replacement = store.update(first.sceneId(), DIMENSION, json("""
                {"components":{"Column":{"objects":[{"name":"Replacement","type":"MESH","primitive":"cube",
                    "location":[0.5,0.5,0.5],"dimensions":[1,1,1],"material":"wood"}]}},
                 "objects":[{"name":"Columns","mirror":["z"],"array":{"count":[2,1,1],"step":[3,0,0]}}]}
                """));
        var definition = replacement.scene().getAsJsonObject("components").getAsJsonObject("Column").getAsJsonArray("objects");
        check(definition.size() == 1 && definition.get(0).getAsJsonObject().get("name").getAsString().equals("Replacement"),
                "组件定义整条替换，旧底座和旧顶部不能残留在新版本中");
        var instance = replacement.scene().getAsJsonArray("objects").get(1).getAsJsonObject();
        check(instance.get("component").getAsString().equals("Column") && instance.has("location")
                && instance.getAsJsonArray("mirror").get(0).getAsString().equals("z"), "实例局部编辑保留组件引用和原位置");
        check(replacement.scene().getAsJsonObject("components").getAsJsonObject("Unused").equals(
                source.getAsJsonObject("components").getAsJsonObject("Unused")), "未点名的组件保持原样");
        long before = files(directory);
        for (String invalid : new String[]{
                "{\"remove_components\":[\"Column\"]}",
                "{\"remove_components\":[\"Unknown\"]}",
                "{\"components\":{\"Column\":{\"objects\":[{\"name\":\"Again\",\"type\":\"INSTANCE\",\"component\":\"Column\",\"location\":[0,0,0]}]}}}"})
            rejects(() -> store.update(replacement.sceneId(), DIMENSION, json(invalid)));
        check(files(directory) == before && store.load(replacement.sceneId(), DIMENSION).scene().equals(replacement.scene()),
                "删除仍在使用的组件或造成引用环时，不发布半份修订，也不覆盖原场景");
        var removed = store.update(replacement.sceneId(), DIMENSION, json("{\"remove_components\":[\"Column\"],\"remove_objects\":[\"Columns\"]}"));
        check(!removed.scene().getAsJsonObject("components").has("Column") && removed.scene().getAsJsonArray("objects").size() == 1,
                "同一次编辑可以一起移除实例和定义，先完整合并再检查引用");
        check(store.load(first.sceneId(), DIMENSION).scene().equals(source), "多次替换和删除后仍能读取最初的组件版本");
    }

    private static void invalidNestedEditsAreRejectedBeforeStorage() {
        for (String invalid : new String[]{
                "{\"schema_version\":3}", "{\"schema_version\":\"2\"}",
                "{\"remove_components\":[\"Column\",\"Column\"]}",
                "{\"remove_components\":[\"Column\"],\"components\":{\"Column\":{\"objects\":[]}}}",
                "{\"objects\":[{\"name\":\"Columns\",\"array\":{\"count\":[2,1,1],\"step\":[3,0,0],\"clicks\":[]}}]}",
                "{\"objects\":[{\"name\":\"Columns\",\"material_map\":{\"wood\":{\"slot\":1}}}]}",
                "{\"components\":{\"Column\":{\"unknown\":1,\"objects\":[]}}}",
                "{\"components\":{\"Column\":{\"objects\":[{\"name\":\"Part\",\"type\":\"MESH\",\"primitive\":\"cube\",\"location\":[0.5,0.5,0.5],\"dimensions\":[1,1,1],\"material\":\"wood\",\"clicks\":[]}]}}}"})
            rejects(() -> BuildingSceneStore.validateEdits(json(invalid)));
    }

    private static JsonObject v1() {
        return json("""
                {"schema_version":1,"coordinate_system":"minecraft_y_up","materials":{"wood":{"block_id":"minecraft:oak_planks"}},
                 "objects":[{"name":"Anchor","type":"MESH","primitive":"cube","location":[0.5,0.5,0.5],"dimensions":[1,1,1],"material":"wood"}]}
                """);
    }
    private static JsonObject v2() {
        JsonObject scene = v1(); scene.addProperty("schema_version", 2);
        scene.add("components", json("""
                {"Column":{"objects":[
                  {"name":"Base","type":"MESH","primitive":"cube","location":[0.5,0.5,0.5],"dimensions":[1,1,1],"material":"wood"},
                  {"name":"Cap","type":"MESH","primitive":"cube","location":[0.5,1.5,0.5],"dimensions":[1,1,1],"material":"wood"}]},
                 "Unused":{"objects":[{"name":"Other","type":"MESH","primitive":"cube","location":[0.5,0.5,0.5],"dimensions":[1,1,1],"material":"wood"}]}}
                """));
        scene.getAsJsonArray("objects").add(json("{\"name\":\"Columns\",\"type\":\"INSTANCE\",\"component\":\"Column\",\"location\":[4,0,0]}"));
        return scene;
    }
    private static long files(Path directory) throws Exception {
        try (var paths = Files.list(directory.resolve("build-scenes").resolve(WORLD))) { return paths.count(); }
    }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void rejects(Runnable operation) {
        try { operation.run(); throw new AssertionError("非法 v2 修订被接受"); }
        catch (IllegalArgumentException | IllegalStateException expected) { }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
