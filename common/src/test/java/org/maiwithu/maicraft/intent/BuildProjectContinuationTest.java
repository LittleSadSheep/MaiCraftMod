// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.core.blueprint.BuildProjectStore;
import org.maiwithu.maicraft.core.blueprint.BuildProjectTargets;
import org.maiwithu.maicraft.core.task.build.BuildTaskRecord;
import org.maiwithu.maicraft.core.tools.work.BuildTool;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** Disk reconstruction keeps the reviewed site/palette after cancellation and altered world progress. */
public final class BuildProjectContinuationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var directory = Files.createTempDirectory("maicraft-build-project-");
        String world = "a".repeat(64), dimension = "minecraft:overworld";
        BuildProjectStore store = new BuildProjectStore(new StateIdentity(world, directory));
        JsonObject arguments = JsonParser.parseString("""
                {"ops":[
                  {"op":"set","block_id":"minecraft:spruce_stairs","x":143,"y":70,"z":-91,
                   "facing":"west","properties":{"half":"top"}},
                  {"op":"set","block_id":"minecraft:snow","x":144,"y":70,"z":-91,"properties":{"layers":"4"}},
                  {"op":"set","block_id":"minecraft:air","x":143,"y":71,"z":-91}],
                 "replace_existing":true,"allow_partial":true,"broaden_material_families":true,
                 "protected_labels":["garden"],"material_policy":"storage_available"}
                """).getAsJsonObject();
        List<BuildTaskRecord.Target> targets = BuildTool.resolvedExactTargets(arguments.getAsJsonArray("ops"));
        String id = store.save(dimension, arguments, targets);
        arguments.getAsJsonArray("ops").get(0).getAsJsonObject().addProperty("x", 999);
        JsonObject restored = new BuildProjectStore(new StateIdentity(world, directory)).load(id, dimension);
        List<BuildTaskRecord.Target> actual = BuildProjectTargets.decode(restored.getAsJsonArray("project_targets"));
        check(actual.equals(targets), "disk plan changed origin, material, orientation, exact properties or explicit air");
        check(!restored.get("broaden_material_families").getAsBoolean(), "resume cannot choose a new palette");
        check(restored.get("material_policy").getAsString().equals("storage_available"), "supply authority changed");
        check(actual.getFirst().pos().equals(new BlockPos(143, 70, -91)), "current-place movement relocated the frozen site");
        check(actual.getFirst().desiredState().getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.WEST,
                "stair orientation was lost");
        check(actual.get(1).matches(Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 4))
                        && !actual.get(1).matches(Blocks.SNOW.defaultBlockState()),
                "authored non-directional state must remain exact");
        check(actual.getFirst().matches(actual.getFirst().desiredState())
                        && !actual.getFirst().matches(Blocks.OAK_STAIRS.defaultBlockState()),
                "live world matching must skip completed targets and retain wrong materials as remaining work");
        restored.getAsJsonArray("project_targets").get(0).getAsJsonObject().addProperty("x", 0);
        check(BuildProjectTargets.decode(store.load(id, dimension).getAsJsonArray("project_targets")).equals(targets),
                "a caller mutated the stored project");
        expectFailure(() -> store.load(id, "minecraft:the_nether"));
        expectFailure(() -> new BuildProjectStore(new StateIdentity("b".repeat(64), directory)).load(id, dimension));
        expectFailure(() -> store.load("../escape", dimension));
        JsonArray missingBlock = BuildProjectTargets.encode(targets);
        missingBlock.get(0).getAsJsonObject().addProperty("block_id", "missing_mod:wall");
        expectFailure(() -> BuildProjectTargets.decode(missingBlock));
        JsonArray normalizedState = BuildProjectTargets.encode(targets);
        normalizedState.get(0).getAsJsonObject().getAsJsonObject("properties").addProperty("waterlogged", "true");
        expectFailure(() -> BuildProjectTargets.decode(normalizedState));

        // The supply coordinator may choose its concrete palette once, before review. Save that version under the same id.
        JsonArray boundOps = JsonParser.parseString("""
                [{"op":"set","block_id":"minecraft:birch_planks","x":143,"y":70,"z":-91}]
                """).getAsJsonArray();
        var bound = BuildTool.resolvedExactTargets(boundOps);
        var plan = new BuildTaskRecord("test", 100, targets, true);
        plan.project(id, frozen -> store.save(id, dimension, arguments, frozen.targets));
        var rebound = new BuildTaskRecord("bound", 100, bound, true);
        plan.copyExecutionContextTo(rebound);
        rebound.persistProject();
        check(id.equals(rebound.projectId()) && BuildProjectTargets.decode(
                store.load(id, dimension).getAsJsonArray("project_targets")).equals(bound),
                "supply palette binding lost or failed to update the original project");

        Goal goal = new Goal("maicraft:build", "resume the same house", null,
                "{\"size\":\"small\",\"material_policy\":\"available\",\"protected_labels\":[\"garden\"]}",
                "{}", List.of(), List.of());
        IntentTaskRecord record = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        record.retainBuildProject(id);
        record.insertRecovery(new Goal("maicraft:wait_for_condition", "wait", null,
                "{\"condition\":\"daytime\"}", "{}", List.of(), List.of()));
        record.terminal(TaskState.CANCELLED, TaskResult.cancelled("interrupted"), 20);
        var snapshot = IntentStateCodec.decode(IntentStateCodec.encode(world, List.of(), List.of(record), Map.of(), List.of()))
                .tasks().getFirst();
        check(snapshot.steps().get(1).parameters().get("project_id").getAsString().equals(id),
                "cancellation, recovery insertion or state persistence discarded the continuation");
        check(!snapshot.steps().get(1).parameters().has("material_policy")
                        && snapshot.steps().get(1).parameters().has("protected_labels"),
                "continuation must keep protection while removing semantic replanning inputs");
        // A completely new semantic task can use this id even though the former task remains cancelled.
        check(new BuildProjectStore(new StateIdentity(world, directory)).load(
                snapshot.steps().get(1).parameters().get("project_id").getAsString(), dimension).has("project_targets"),
                "terminal task prevented explicit continuation of its retained building");
        var resumedGoal = goal.withParameters(JsonParser.parseString("{\"project_id\":\"" + id + "\"}").getAsJsonObject());
        var resumedRecord = new IntentTaskRecord(UUID.randomUUID(), null, resumedGoal);
        resumedRecord.retainBuildProject(id, List.of("garden"));
        check(resumedRecord.steps().getFirst().parameters().getAsJsonArray("protected_labels").get(0).getAsString().equals("garden"),
                "submitting only project_id must retain the saved area protections");
    }

    private static void expectFailure(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("unsafe project lookup was accepted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
