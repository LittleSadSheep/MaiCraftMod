// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.intent.persistence.IntentStateCodec;

/** Exercise the same runtime entry used by MCP plan/execute, including retry and persistence. */
public final class BlueprintRuntimeEntryTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        IntentRuntime runtime = IntentRuntime.get();
        for (String ability : List.of("maicraft:design_machine", "maicraft:build_machine", "maicraft:modify_machine")) {
            Goal goal = goal(ability, 2);
            JsonObject before = goal.toJson();
            Plan plan = runtime.compile(goal, 100);
            check(plan.goal().toJson().equals(before) && goal.toJson().equals(before), "compilation retains explicit structure and evidence");
            var saved = IntentStateCodec.encode("test", List.of(plan), List.of(), Map.of(), List.of());
            check(IntentStateCodec.decode(saved).plans().getFirst().goal().equals(goal), "blueprint survives checkpoint round trip");
            var record = new IntentTaskRecord(UUID.randomUUID(), null, goal);
            JsonObject details = new JsonObject(); details.add("parameters", goal.parameters());
            runtime.validateDecisionAnswer(record, "retry", details);
            details = new JsonObject(); details.add("goal", goal.toJson());
            runtime.validateDecisionAnswer(record, "replace_goal", details);
            try {
                runtime.execute(null, goal, null, null);
                throw new AssertionError("worldless execution unexpectedly started");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().contains("not bound"), "direct execution passed blueprint validation and stopped at world binding");
            }
        }
        Goal large = goal("maicraft:design_machine", 600);
        Plan largePlan = runtime.compile(large, 101);
        check(IntentStateCodec.decode(IntentStateCodec.encode("test", List.of(largePlan), List.of(), Map.of(), List.of()))
                .plans().getFirst().goal().parameters().getAsJsonObject("blueprint").getAsJsonArray("blocks").size() == 600,
                "physical arrays are not truncated to the metadata limit of 256");
        Goal child = goal("maicraft:design_machine", 2);
        Goal sequence = new Goal("maicraft:sequence", "review then build", null, "{}", "{}", List.of(),
                List.of(child, goal("maicraft:build_machine", 2)));
        check(runtime.compile(sequence, 102).steps().size() == 2, "typed blueprints are recognized in sequence children");

        JsonObject invalid = child.toJson(); invalid.addProperty("ability", "maicraft:operate_machine"); rejects(runtime, invalid);
        invalid = child.toJson(); invalid.getAsJsonObject("parameters").add("clicks", new JsonArray()); rejects(runtime, invalid);
        invalid = child.toJson(); invalid.getAsJsonObject("parameters").getAsJsonObject("blueprint")
                .getAsJsonArray("blocks").get(0).getAsJsonObject().add("clicks", new JsonArray()); rejects(runtime, invalid);
        invalid = goal("maicraft:modify_machine", 2).toJson(); invalid.getAsJsonObject("parameters")
                .addProperty("operation", "connect_mechanical_power"); rejects(runtime, invalid);
        invalid = child.toJson(); invalid.getAsJsonObject("parameters").getAsJsonObject("blueprint")
                .add("placements", new JsonArray()); rejects(runtime, invalid);
        invalid = child.toJson(); invalid.getAsJsonObject("parameters").getAsJsonObject("blueprint")
                .getAsJsonArray("blocks").get(0).getAsJsonObject().addProperty("offset", "not coordinates"); rejects(runtime, invalid);
        invalid = child.toJson(); invalid.getAsJsonObject("parameters").remove("blueprint");
        invalid.getAsJsonObject("parameters").add("wrapper", child.parameters()); rejects(runtime, invalid);
        System.out.println("BlueprintRuntimeEntryTest: passed");
    }

    private static Goal goal(String ability, int count) {
        JsonObject blueprint = new JsonObject(); blueprint.addProperty("schema_version", 1); JsonArray blocks = new JsonArray();
        for (int i = 0; i < count; i++) {
            JsonObject cell = new JsonObject(); JsonArray offset = new JsonArray(); offset.add(i % 30); offset.add(0); offset.add(i / 30);
            cell.add("offset", offset); cell.addProperty("block_id", i == 1 ? "minecraft:barrel" : "minecraft:stone");
            blocks.add(cell);
        }
        blueprint.add("blocks", blocks);
        // These names describe observed data and must not be interpreted as live execution handles.
        blueprint.add("evidence", JsonParser.parseString("{\"observed_entities\":[{\"entity_id\":\"create:super_glue\",\"position\":[0,0,0]}],\"observed_nbt\":{\"inventory_slots\":[]}}"));
        JsonObject parameters = new JsonObject(); parameters.add("blueprint", blueprint);
        Goal.SemanticTarget target = null;
        if (!ability.equals("maicraft:design_machine")) {
            parameters.addProperty("snapshot_id", UUID.randomUUID().toString());
            target = new Goal.SemanticTarget("landmark", "test_site", null, null);
        }
        if (ability.equals("maicraft:modify_machine")) parameters.addProperty("operation", "apply_blueprint");
        return new Goal(ability, "test explicit machine structure", target, parameters.toString(), "{}", List.of(), List.of());
    }
    private static void rejects(IntentRuntime runtime, JsonObject request) {
        try { runtime.compile(Goal.fromJson(request), 200); throw new AssertionError("invalid input accepted: " + request); }
        catch (IllegalArgumentException expected) { }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
