// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneCompiler;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneExport;
import org.maiwithu.maicraft.core.tools.work.BuildTool;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;

/** 检查模型目标能经过真实语义校验，保持世界锚点、指定材料、精确状态和导出内容；不会在游戏里实际施工。 */
public final class BuildingSceneRuntimeTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        JsonObject scene = JsonParser.parseString("""
                {"materials":{"Wall":{"block_id":"minecraft:oak_planks"},"Trim":{"block_id":"minecraft:spruce_planks"}},
                 "objects":[{"name":"Wall","type":"MESH","primitive":"cube","location":[1,0.5,1],"dimensions":[2,1,2],"material":"Wall"},
                            {"name":"Trim","type":"MESH","primitive":"cube","location":[2.5,0.5,1],"dimensions":[1,1,2],"material":"Trim"}]}
                """).getAsJsonObject();
        createAndUpdateCompileOnce(scene);
        JsonObject p = new JsonObject(); p.add("scene", scene); p.addProperty("operation", "create_scene");
        Goal goal = goal(p);
        IntentRuntime runtime = IntentRuntime.get();
        Plan plan = runtime.compile(goal, 100);
        check(IntentRuntime.isReadOnlyDesign(goal), "modelling must use the no-body execution path");
        var saved = IntentStateCodec.encode("test", List.of(plan), List.of(), Map.of(), List.of());
        check(IntentStateCodec.decode(saved).plans().getFirst().goal().equals(goal), "model coordinates survive semantic checkpoints");
        JsonObject blueprint = BuildingSceneCompiler.compile(scene);
        JsonObject buildArgs = BuildingSceneAdapter.buildArguments(blueprint,
                new Goal.WorldPosition(73, -60, -318, "minecraft:overworld"), new JsonObject());
        var targets = BuildTool.resolvedTargets(buildArgs.getAsJsonArray("ops"), true);
        check(targets.size() == 6 && targets.stream().allMatch(t -> t.strictIdentity() && !t.itemPlace()), "model keeps strict material identity");
        check(targets.stream().anyMatch(t -> t.pos().equals(new BlockPos(75, -60, -319))
                && t.block() == Blocks.SPRUCE_PLANKS), "Blender conversion keeps trim material and exact original anchor");
        check(!buildArgs.get("broaden_material_families").getAsBoolean(), "explicit model may never rebind materials");
        check(!buildArgs.get("replace_existing").getAsBoolean(), "model does not silently authorize clearance");
        p.addProperty("operation", "build");
        check(!IntentRuntime.isReadOnlyDesign(goal(p)), "build submits the existing construction executor");
        JsonObject explicit = JsonParser.parseString("""
                {"blocks":[{"offset":[0,0,0],"block_id":"minecraft:snow","properties":{"layers":"7"}}]}
                """).getAsJsonObject();
        var exact = BuildingSceneAdapter.buildArguments(explicit, new Goal.WorldPosition(0, 64, 0, "minecraft:overworld"), new JsonObject());
        // 这里只检查最终七层雪与一层雪不能混同，没有验证从第一层逐次放到第七层的执行过程。
        var snow = BuildTool.resolvedTargets(exact.getAsJsonArray("ops"), true).getFirst();
        check(snow.exactProperties().contains("layers") && !snow.matchesExactProperties(Blocks.SNOW.defaultBlockState()), "authored layers are part of completion verification");
        var structure = BuildingSceneExport.structure(blueprint);
        check(structure.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND).size() == 6
                && structure.getIntArray("maicraft_offset")[2] == -1, "NBT export retains all cells and origin translation");
        // 用临时目录分别导出 NBT 和 JSON，再读回比较；另检查树叶的防腐烂默认值在两种导出中一致。
        var directory = java.nio.file.Files.createTempDirectory("maicraft-scene-export-");
        String id = java.util.UUID.randomUUID().toString();
        var file = BuildingSceneExport.write(directory, id, blueprint, "nbt");
        check(net.minecraft.nbt.NbtIo.readCompressed(file, net.minecraft.nbt.NbtAccounter.unlimitedHeap()).equals(structure), "compressed NBT export round trips");
        var json = BuildingSceneExport.write(directory, id, blueprint, "json");
        check(JsonParser.parseString(java.nio.file.Files.readString(json)).equals(blueprint), "JSON preserves negative offsets and exact materials");
        JsonObject leaves = JsonParser.parseString("{\"blocks\":[{\"offset\":[0,0,0],\"block_id\":\"minecraft:oak_leaves\"}]}").getAsJsonObject();
        var leafBlueprint = org.maiwithu.maicraft.core.blueprint.BuildingSceneBlocks.export(leaves);
        check(leafBlueprint.getAsJsonArray("blocks").get(0).getAsJsonObject().getAsJsonObject("properties")
                .get("persistent").getAsString().equals("true"), "export must retain the same non-decaying leaf default as construction");
        check(BuildingSceneExport.structure(leaves).getList("palette", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .getCompound(0).getCompound("Properties").getString("persistent").equals("true"), "NBT and JSON must share effective defaults");
        JsonObject bad = p.deepCopy(); bad.addProperty("style", "replace author's palette"); rejects(runtime, bad);
        bad = p.deepCopy(); bad.getAsJsonObject("scene").getAsJsonArray("objects").get(0).getAsJsonObject().add("clicks", new JsonArray()); rejects(runtime, bad);
        bad = new JsonObject(); bad.addProperty("operation", "update_scene"); bad.addProperty("scene_id", id);
        bad.add("edits", JsonParser.parseString("{\"objects\":[{\"name\":\"Wall\",\"clicks\":[]}]}")); rejects(runtime, bad);
        explicit.getAsJsonArray("blocks").get(0).getAsJsonObject().addProperty("block_id", "missing:material");
        try { BuildingSceneAdapter.buildArguments(explicit, new Goal.WorldPosition(0,64,0,"minecraft:overworld"), new JsonObject());
            throw new AssertionError("unknown block silently substituted"); } catch (IllegalArgumentException expected) { }
        explicit.getAsJsonArray("blocks").get(0).getAsJsonObject().addProperty("block_id", "minecraft:beehive");
        explicit.getAsJsonArray("blocks").get(0).getAsJsonObject().add("properties", JsonParser.parseString("{\"honey_level\":\"5\"}"));
        try { BuildingSceneAdapter.buildArguments(explicit, new Goal.WorldPosition(0,64,0,"minecraft:overworld"), new JsonObject());
            throw new AssertionError("normalized state silently changed"); } catch (IllegalArgumentException expected) { }
        System.out.println("BuildingSceneRuntimeTest: semantic model contracts, exact cells and exports passed");
    }

    private static void createAndUpdateCompileOnce(JsonObject scene) throws Exception {
        var root = java.nio.file.Files.createTempDirectory("scene-compilation-");
        String world = "c".repeat(64), dimension = "minecraft:overworld";
        int[] compilations = {0};
        java.util.function.Function<JsonObject, JsonObject> compiler = source -> {
            compilations[0]++;
            return BuildingSceneCompiler.compile(source);
        };
        var constructor = org.maiwithu.maicraft.core.blueprint.BuildingSceneStore.class.getDeclaredConstructor(
                org.maiwithu.maicraft.intent.persistence.StateIdentity.class, java.util.function.Function.class);
        constructor.setAccessible(true);
        var store = constructor.newInstance(new org.maiwithu.maicraft.intent.persistence.StateIdentity(world, root), compiler);
        try (var f = new org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness()) {
            JsonObject create = new JsonObject(); create.addProperty("operation", "create_scene"); create.add("scene", scene);
            var first = sceneReport(goal(create), f.player, store);
            check(compilations[0] == 1, "create_scene must perform one complete compilation across adapter and storage");
            String firstId = first.data().get("scene_id").toString();
            var original = store.load(firstId, dimension);
            JsonObject update = new JsonObject(); update.addProperty("operation", "update_scene"); update.addProperty("scene_id", firstId);
            update.add("edits", JsonParser.parseString("{\"objects\":[{\"name\":\"Wall\",\"location\":[2,0.5,1]}]}"));
            var second = sceneReport(goal(update).withTarget(null), f.player, store);
            check(compilations[0] == 2, "update_scene must compile the edited model once, including saving");
            var revision = store.load(second.data().get("scene_id").toString(), dimension);
            check(revision.parentSceneId().equals(firstId) && revision.anchor().equals(original.anchor()),
                    "the optimized update retains its parent and fixed world anchor");
            check(store.load(firstId, dimension).scene().equals(original.scene()), "updating must preserve the original model");
            JsonObject missing = create.deepCopy();
            missing.getAsJsonObject("scene").getAsJsonObject("materials").getAsJsonObject("Wall")
                    .addProperty("block_id", "missing:material");
            try {
                sceneReport(goal(missing), f.player, store);
                throw new AssertionError("an unresolvable model was saved before native-state validation");
            } catch (IllegalArgumentException expected) { }
            try (var files = java.nio.file.Files.list(root.resolve("build-scenes").resolve(world))) {
                check(files.count() == 2, "a failed registry check cannot publish another scene revision");
            }
            check(f.blockUses() == 0 && f.itemUses() == 0, "scene compilation and storage must not issue game interactions");
        }
    }

    private static org.maiwithu.maicraft.task.TaskResult sceneReport(Goal goal, net.minecraft.client.player.LocalPlayer player,
            org.maiwithu.maicraft.core.blueprint.BuildingSceneStore store) {
        var action = BuildingSceneAdapter.adapt(goal, player, null,
                preview -> { throw new AssertionError("create/update must not publish a preview"); }, () -> store);
        check(action instanceof IntentAction.Report, "create/update must return a report instead of construction work");
        var result = ((IntentAction.Report) action).result();
        check(result.success() && Boolean.FALSE.equals(result.data().get("construction_started")),
                "model creation and editing remain read-only with respect to the game world");
        return result;
    }

    private static Goal goal(JsonObject p) {
        return new Goal("maicraft:build", "Author this wall", new Goal.SemanticTarget("current_place", null, null, null),
                p.toString(), "{}", List.of(), List.of());
    }
    private static void rejects(IntentRuntime runtime, JsonObject p) {
        try { runtime.compile(goal(p), 100); throw new AssertionError("invalid scene request accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
}
