// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.MachineControlTest;
import org.maiwithu.maicraft.core.integration.machine.MachineDesignReviewTest;
import org.maiwithu.maicraft.core.integration.machine.MachineSurveyModelTest;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintSpecTest;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintStateTest;

/** No game launch required; actual server receipts still require in-game acceptance tests. */
public final class MachineRegressionSuite {
    public static void main(String[] args) {
        MachineSurveyModelTest.main(args);
        MachineDesignReviewTest.main(args);
        MachineControlTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.MachineMenuPolicyTest.main(args);
        MachineBlueprintSpecTest.main(args);
        MachineBlueprintStateTest.main(args);
        org.maiwithu.maicraft.core.task.build.MachineBlueprintGeometryTest.main(args);
        accepts("maicraft:inspect_machine", "{\"kind\":\"current_place\"}", "{\"label\":\"factory\",\"radius\":4}");
        rejects("maicraft:inspect_machine", "{\"kind\":\"current_place\"}", "{\"radius\":4.5}");
        rejects("maicraft:inspect_machine", "{\"kind\":\"current_place\"}", "{\"radius\":2147483648}");
        rejects("maicraft:inspect_machine", "{\"kind\":\"current_place\"}", "{\"radius\":\"4\"}");
        rejects("maicraft:inspect_machine", "null", "{\"label\":\"factory\"}");
        accepts("maicraft:operate_machine", "{\"kind\":\"nearest\"}", "{\"operation\":\"ae2_supply\",\"item_id\":\"minecraft:iron_ingot\",\"count\":2}");
        rejects("maicraft:operate_machine", "{\"kind\":\"nearest\",\"label\":\"selected network\"}", "{\"operation\":\"ae2_supply\",\"item_id\":\"minecraft:iron_ingot\"}");
        rejects("maicraft:operate_machine", "{\"kind\":\"nearest\"}", "{\"operation\":\"ae2_supply\",\"item_id\":\"minecraft:iron_ingot\",\"snapshot_id\":\"ignored\"}");
        rejects("maicraft:operate_machine", "{\"kind\":\"nearest\"}", "{\"operation\":\"ae2_supply\",\"item_id\":\"minecraft:iron_ingot\",\"allow_use\":\"true\"}");
        rejects("maicraft:operate_machine", "{\"kind\":\"landmark\",\"label\":\"factory\"}", "{\"operation\":\"set_control\",\"snapshot_id\":\"receipt\"}");
        rejects("maicraft:operate_machine", "{\"kind\":\"landmark\",\"label\":\"factory\"}", "{\"operation\":\"set_control\",\"snapshot_id\":\"receipt\",\"powered\":true,\"count\":1}");
        accepts("maicraft:operate_machine", "null", "{\"operation\":\"deposit\",\"menu_receipt_id\":\"receipt\",\"entry_index\":2,\"item_id\":\"minecraft:iron_ingot\",\"count\":3}");
        rejects("maicraft:operate_machine", "null", "{\"operation\":\"deposit\",\"menu_receipt_id\":\"receipt\",\"item_id\":\"minecraft:iron_ingot\"}");
        rejects("maicraft:operate_machine", "{\"kind\":\"nearest\"}", "{\"operation\":\"deposit\",\"menu_receipt_id\":\"receipt\",\"entry_index\":2,\"item_id\":\"minecraft:iron_ingot\"}");
        accepts("maicraft:build_machine", "{\"kind\":\"landmark\",\"label\":\"site\"}", "{\"snapshot_id\":\"receipt\",\"design\":{\"components\":[{\"name\":\"buffer\",\"block_id\":\"minecraft:chest\",\"count\":1,\"role\":\"storage\"}],\"connections\":[],\"style\":\"compact\",\"constraints\":{\"max_width\":5}}}");
        rejects("maicraft:build_machine", "{\"kind\":\"landmark\",\"label\":\"site\"}", "{\"snapshot_id\":\"receipt\",\"blueprint\":{\"blocks\":[{\"offset\":[0,0,0],\"block_id\":\"minecraft:stone\"}]}}");
        rejects("maicraft:build_machine", "{\"kind\":\"landmark\",\"label\":\"site\"}", "{\"snapshot_id\":\"receipt\",\"design\":{\"components\":[],\"connections\":[]}}");
        rejects("maicraft:modify_machine", "{\"kind\":\"landmark\",\"label\":\"site\"}", "{\"operation\":\"apply_blueprint\",\"snapshot_id\":\"receipt\",\"blueprint\":{}}");
        rejects("maicraft:design_machine", "null", "{\"design\":{\"components\":[{\"name\":\"buffer\",\"block_id\":\"minecraft:chest\",\"count\":1,\"role\":\"storage\"}],\"connections\":[],\"blueprint\":{}}}");
        var interrupted = IntentTask.withInterruptedEffects(
                org.maiwithu.maicraft.task.TaskResult.cancelled("cancelled"),
                org.maiwithu.maicraft.task.TaskResult.fail("partial deposit", java.util.Map.of(
                        "actual_player_delta", -2, "outcome_uncertain", true,
                        "mechanical_retry_allowed", false, "slot", 3)));
        JsonObject interruption = JsonParser.parseString(interrupted.toJson()).getAsJsonObject();
        JsonObject childData = interruption.getAsJsonObject("data").getAsJsonObject("interrupted_child").getAsJsonObject("data");
        if (!interrupted.interrupted() || interrupted.success() || childData.get("actual_player_delta").getAsInt() != -2
                || childData.has("slot") || !interruption.getAsJsonObject("data").get("outcome_uncertain").getAsBoolean()) {
            throw new AssertionError("Cancellation lost or leaked native partial-effect evidence");
        }
        System.out.println("MachineRegressionSuite: semantic contracts passed");
    }

    private static Goal goal(String ability, String target, String parameters) {
        JsonObject json = new JsonObject();
        json.addProperty("ability", ability);
        json.addProperty("outcome", "test machine boundary");
        json.add("target", JsonParser.parseString(target));
        json.add("parameters", JsonParser.parseString(parameters));
        return Goal.fromJson(json);
    }
    private static void accepts(String ability, String target, String parameters) {
        SemanticGoalContract.validate(goal(ability, target, parameters), Set.of(ability));
    }
    private static void rejects(String ability, String target, String parameters) {
        try { accepts(ability, target, parameters); }
        catch (SemanticContractException expected) { return; }
        throw new AssertionError("Unsafe or ambiguous machine contract accepted: " + parameters);
    }
}
