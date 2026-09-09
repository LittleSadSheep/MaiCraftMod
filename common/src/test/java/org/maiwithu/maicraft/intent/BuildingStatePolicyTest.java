// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.core.blueprint.BuildProjectTargets;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneCompiler;
import org.maiwithu.maicraft.core.tools.work.BuildTool;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;

// 从建模材料一路转换并保存工程，检查未声明门开关时不强加要求、已声明属性保留到最终验收，以及旧工程迁移不会丢要求。
public final class BuildingStatePolicyTest {
    public static void main(String[] args) {
        JsonObject scene = JsonParser.parseString("""
                {"materials":{"Door":{"block_id":"minecraft:spruce_door",
                  "properties":{"facing":"north","half":"lower"}}},
                 "objects":[{"name":"Door","type":"MESH","primitive":"cube",
                  "location":[0.5,0.5,0.5],"dimensions":[1,1,1],"material":"Door"}]}
                """).getAsJsonObject();
        var blueprint = BuildingSceneCompiler.compile(scene);
        check(!blueprint.getAsJsonArray("blocks").get(0).getAsJsonObject().getAsJsonObject("properties").has("open"), "compiler does not invent an OPEN requirement");
        var argsJson = BuildingSceneAdapter.buildArguments(blueprint,
                new Goal.WorldPosition(0, 64, 0, "minecraft:overworld"), new JsonObject());
        var target = BuildTool.resolvedExactTargets(argsJson.getAsJsonArray("ops")).getFirst();
        var open = target.desiredState().setValue(BlockStateProperties.OPEN, true);
        check(target.matches(open), "unimportant door use does not obstruct completion");
        var rotated = open.setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.EAST);
        check(target.constructionMatches(rotated) && !target.matches(rotated), "important state discrepancies wait for final verification");
        check(!target.acceptsPlacedState(rotated), "a new placement still tries the important authored orientation");
        check(BuildProjectTargets.decode(BuildProjectTargets.encode(List.of(target))).getFirst().equals(target), "importance survives persisted project replay");
        scene.getAsJsonObject("materials").getAsJsonObject("Door").getAsJsonObject("properties").addProperty("open", "false");
        argsJson = BuildingSceneAdapter.buildArguments(BuildingSceneCompiler.compile(scene),
                new Goal.WorldPosition(0, 64, 0, "minecraft:overworld"), new JsonObject());
        var important = BuildTool.resolvedExactTargets(argsJson.getAsJsonArray("ops")).getFirst();
        check(important.constructionMatches(open) && !important.matches(open), "important OPEN waits for final adjustment");
        check(important.acceptsPlacedState(open), "operating state does not rule out structurally valid placement");
        var oldRows = BuildProjectTargets.encode(List.of(important)); oldRows.get(0).getAsJsonObject().remove("final_properties");
        check(!BuildProjectTargets.decode(oldRows).getFirst().matches(open), "old explicit projects preserve declared OPEN as a final requirement");
        JsonObject parameters = new JsonObject(); parameters.add("scene", scene); parameters.addProperty("operation", "create_scene");
        var goal = new Goal("maicraft:build", "State policy", new Goal.SemanticTarget("current_place", null, null, null),
                parameters.toString(), "{}", List.of(), List.of());
        var plan = IntentRuntime.get().compile(goal, 100);
        check(IntentStateCodec.decode(IntentStateCodec.encode("test", List.of(plan), List.of(), Map.of(), List.of()))
                .plans().getFirst().goal().equals(goal), "public semantic persistence retains importance flags");
        System.out.println("BuildingStatePolicyTest: omitted and declared final states survive model and project boundaries");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
