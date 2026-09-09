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
    // 顺序运行机器、建造和部分语义边界测试；任一断言或未处理异常都会使这组检查失败。
    public static void main(String[] args) {
        try { TravelTransportContractTest.main(args); }
        catch (Exception failure) { throw new AssertionError(failure); }
        try { IntentTerminalStateTest.main(args); }
        catch (Exception failure) { throw new AssertionError(failure); }
        try { SequenceProtectionTest.main(args); }
        catch (Exception failure) { throw new AssertionError(failure); }
        DecisionAnswerPersistenceTest.main(args);
        try { TaskStepPersistenceTest.main(args); }
        catch (Exception failure) { throw new AssertionError(failure); }
        try { HarvestEvidenceContractTest.main(args); }
        catch (Exception failure) { throw new AssertionError(failure); }
        org.maiwithu.maicraft.core.scan.SearchGeometryTest.main(args);
        MachineSurveyModelTest.main(args);
        MachineDesignReviewTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayoutTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutModulesTest.main(args);
        MachineLayoutJobsTest.main(args);
        MachineBlueprintAbilityTest.main(args);
        BlueprintRuntimeEntryTest.main(args);
        BuildingStatePolicyTest.main(args);
        org.maiwithu.maicraft.core.blueprint.BuildingSceneCompilerTest.main(args);
        org.maiwithu.maicraft.core.blueprint.BuildingSceneInspectionTest.main(args);
        org.maiwithu.maicraft.mcp.BuildingModelPublicTest.main(args);
        try { org.maiwithu.maicraft.core.blueprint.BuildingSceneStoreTest.main(args); }
        catch (Exception failure) { throw new AssertionError("building scene revision regression", failure); }
        try { BuildingSceneRuntimeTest.main(args); }
        catch (Exception failure) { throw new AssertionError("building model entry and export regression", failure); }
        try { BuildProjectContinuationTest.main(args); }
        catch (Exception failure) { throw new AssertionError("durable building continuation regression", failure); }
        org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocumentTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.MachineBuildCompletionTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.MachinePlanningBudgetTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutAeNetworksTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutItemOutputsTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.assembly.MachineAssemblyTest.main(args);
        MachineControlTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.MachineMenuPolicyTest.main(args);
        MachineBlueprintSpecTest.main(args);
        try { org.maiwithu.maicraft.core.integration.machine.MachineMenuObservationTest.main(args); }
        catch (Exception failure) { throw new AssertionError("menu observation regression", failure); }
        MachineBlueprintStateTest.main(args);
        org.maiwithu.maicraft.core.integration.machine.MachineConstructionPlanTest.main(args);
        org.maiwithu.maicraft.core.task.build.MachineBlueprintGeometryTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildExecutionContextTest.main(args);
        org.maiwithu.maicraft.core.task.supply.BuildBatchCompletionTest.main(args);
        try { org.maiwithu.maicraft.core.task.supply.BuildSupplyPreviewTest.main(args); }
        catch (Exception failure) { throw new AssertionError("preview before material supply", failure); }
        SemanticBuildPlannerTest.main(args);
        try { BuildDesignPreviewTest.main(args); }
        catch (Exception failure) { throw new AssertionError("read-only build preview regression", failure); }
        try { SemanticBuildSiteTest.main(args); }
        catch (Exception failure) { throw new AssertionError("loaded build site regression", failure); }
        // 接下来检查内部机器能力参数，包括整数、来源和目标约束；这里没有经过最外层 PublicToolCatalog。
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
        accepts("maicraft:build_machine", "{\"kind\":\"landmark\",\"label\":\"site\"}", "{\"snapshot_id\":\"receipt\",\"blueprint\":{\"blocks\":[{\"offset\":[0,0,0],\"block_id\":\"minecraft:stone\"}]}}");
        rejects("maicraft:build_machine", "{\"kind\":\"landmark\",\"label\":\"site\"}", "{\"snapshot_id\":\"receipt\",\"design\":{\"components\":[],\"connections\":[]}}");
        rejects("maicraft:modify_machine", "{\"kind\":\"landmark\",\"label\":\"site\"}", "{\"operation\":\"apply_blueprint\",\"snapshot_id\":\"receipt\",\"blueprint\":{}}");
        rejects("maicraft:design_machine", "null", "{\"design\":{\"components\":[{\"name\":\"buffer\",\"block_id\":\"minecraft:chest\",\"count\":1,\"role\":\"storage\"}],\"connections\":[],\"blueprint\":{}}}");
        // 模拟中途取消，要求保留已发生的数量变化和不确定性，同时不把内部槽号直接公开。
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
        org.maiwithu.maicraft.core.task.acquire.WorkToolPreparationTest.main(args);
        SemanticInteractionToolTest.main(args);
        org.maiwithu.maicraft.mcp.NearbySignPerceptionTest.main(args);
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
    // 只调用 SemanticGoalContract；单独通过这项检查不等于同一个请求一定能通过外层公开协议。
    private static void accepts(String ability, String target, String parameters) {
        SemanticGoalContract.validate(goal(ability, target, parameters), Set.of(ability));
    }
    // 预期明确的契约异常才算拒绝成功；没有抛异常就让测试失败，其他异常也不能冒充正确拒绝。
    private static void rejects(String ability, String target, String parameters) {
        try { accepts(ability, target, parameters); }
        catch (SemanticContractException expected) { return; }
        throw new AssertionError("Unsafe or ambiguous machine contract accepted: " + parameters);
    }
}
