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

/** The real semantic entry accepts model geometry while preserving exact material/state requirements. */
public final class BuildingSceneRuntimeTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        JsonObject scene = JsonParser.parseString("""
                {"materials":{"Wall":{"block_id":"minecraft:oak_planks"},"Trim":{"block_id":"minecraft:spruce_planks"}},
                 "objects":[{"name":"Wall","type":"MESH","primitive":"cube","location":[1,0.5,1],"dimensions":[2,1,2],"material":"Wall"},
                            {"name":"Trim","type":"MESH","primitive":"cube","location":[2.5,0.5,1],"dimensions":[1,1,2],"material":"Trim"}]}
                """).getAsJsonObject();
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
        var snow = BuildTool.resolvedTargets(exact.getAsJsonArray("ops"), true).getFirst();
        check(snow.exactProperties().contains("layers") && !snow.matchesExactProperties(Blocks.SNOW.defaultBlockState()), "authored layers are part of completion verification");
        var structure = BuildingSceneExport.structure(blueprint);
        check(structure.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND).size() == 6
                && structure.getIntArray("maicraft_offset")[2] == -1, "NBT export retains all cells and origin translation");
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
