// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.MachineControlTest;
import org.maiwithu.maicraft.core.integration.machine.MachineDesignReviewTest;
import org.maiwithu.maicraft.core.integration.machine.MachineDesignConstraintsTest;
import org.maiwithu.maicraft.core.integration.create.CreateBeltGeometryTest;
import org.maiwithu.maicraft.core.integration.create.BeltLinkReceiptTest;
import org.maiwithu.maicraft.core.integration.machine.MachineAssemblyDocumentTest;
import org.maiwithu.maicraft.core.integration.machine.MachineBeltAssemblyTest;
import org.maiwithu.maicraft.core.integration.machine.MachineBeltRoutesTest;
import org.maiwithu.maicraft.core.integration.machine.MachinePlacementDependenciesTest;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDiffTest;
import org.maiwithu.maicraft.core.integration.machine.MachineCompletionArchiveTest;
import org.maiwithu.maicraft.core.integration.machine.MachineBuildEvidenceTest;
import org.maiwithu.maicraft.core.integration.machine.MachineWorldBlueprintTest;
import org.maiwithu.maicraft.core.integration.machine.MachineDesignRejectionTest;
import org.maiwithu.maicraft.core.integration.machine.MachineNativeInstallationTest;
import org.maiwithu.maicraft.mcp.knowledge.MachineAssemblyResourcesTest;
import org.maiwithu.maicraft.core.integration.machine.MachineSurveyModelTest;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintStateTest;
import java.util.Map;
import org.maiwithu.maicraft.agent.tool.ToolRegistryTest;
import org.maiwithu.maicraft.core.blueprint.BuildingConvexMeshTest;
import org.maiwithu.maicraft.core.blueprint.BuildingModelBlockStatesTest;
import org.maiwithu.maicraft.core.blueprint.BuildingModelCompositionTest;
import org.maiwithu.maicraft.core.blueprint.BuildingModelContractTest;
import org.maiwithu.maicraft.core.blueprint.BuildingModelGuardTest;
import org.maiwithu.maicraft.core.blueprint.BuildingModelInspectionTest;
import org.maiwithu.maicraft.core.blueprint.BuildingModelPatternTest;
import org.maiwithu.maicraft.core.blueprint.BuildingModelShapeTest;
import org.maiwithu.maicraft.core.blueprint.BuildingModelShowcaseTest;
import org.maiwithu.maicraft.core.blueprint.BuildingModelSurfaceTest;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneCompilerTest;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneInspectionTest;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneStoreTest;
import org.maiwithu.maicraft.core.blueprint.BuildingSceneV2StoreTest;
import org.maiwithu.maicraft.core.integration.machine.MachineBlueprintDocumentTest;
import org.maiwithu.maicraft.core.integration.machine.ConstructionSiteGeometryTest;
import org.maiwithu.maicraft.core.integration.machine.MachineBuildCompletionTest;
import org.maiwithu.maicraft.core.integration.machine.MachineConstructionPlanTest;
import org.maiwithu.maicraft.core.integration.machine.MachineMenuObservationTest;
import org.maiwithu.maicraft.core.integration.machine.MachineMenuPolicyTest;
import org.maiwithu.maicraft.core.integration.machine.MachinePlanningBudgetTest;
import org.maiwithu.maicraft.core.integration.machine.assembly.MachineAssemblyTest;
import org.maiwithu.maicraft.core.integration.machine.control.VehicleRegressionSuite;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutAeNetworksTest;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutInstanceIdentityTest;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutItemOutputsTest;
import org.maiwithu.maicraft.core.integration.machine.layout.MachineLayoutModulesTest;
import org.maiwithu.maicraft.core.integration.machine.layout.SemanticMachineLayoutTest;
import org.maiwithu.maicraft.core.scan.SearchGeometryTest;
import org.maiwithu.maicraft.core.scan.TargetIndexInvalidationTest;
import org.maiwithu.maicraft.core.task.acquire.AcquisitionSettlementTest;
import org.maiwithu.maicraft.core.task.acquire.WorkToolPreparationTest;
import org.maiwithu.maicraft.core.task.build.BuildExecutionContextTest;
import org.maiwithu.maicraft.core.task.build.MachineBlueprintGeometryTest;
import org.maiwithu.maicraft.core.task.supply.BuildBatchCompletionTest;
import org.maiwithu.maicraft.core.task.supply.BuildSupplyPreviewTest;
import org.maiwithu.maicraft.core.task.supply.MaterialSupplyReceiptTest;
import org.maiwithu.maicraft.mcp.BuildingModelPublicTest;
import org.maiwithu.maicraft.mcp.BuildingModelV2PublicTest;
import org.maiwithu.maicraft.mcp.NearbySignPerceptionTest;
import org.maiwithu.maicraft.mcp.PerceiveSectionsTest;
import org.maiwithu.maicraft.task.TaskResult;

/** 无需启动游戏；真实服务器回执仍需通过游戏内验收测试。 */
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
        SearchGeometryTest.main(args);
        try { TargetIndexInvalidationTest.main(args); }
        catch (Exception failure) { throw new AssertionError(failure); }
        MachineSurveyModelTest.main(args);
        ConstructionSiteGeometryTest.main(args);
        VehicleRegressionSuite.main(args);
        MachineDesignReviewTest.main(args);
        MachineDesignConstraintsTest.main(args);
        CreateBeltGeometryTest.main(args);
        BeltLinkReceiptTest.main(args);
        MachineAssemblyDocumentTest.main(args);
        MachineAssemblyResourcesTest.main(args);
        try { MachineNativeInstallationTest.main(args); }
        catch (Exception failed) { throw new AssertionError("native installation dispatch", failed); }
        SemanticMachineLayoutTest.main(args);
        MachineLayoutModulesTest.main(args);
        MachineLayoutJobsTest.main(args);
        MachineBlueprintAbilityTest.main(args);
        MachinePlanPreflightTest.main(args);
        try { ConstructionSiteRuntimeTest.main(args); }
        catch (Exception failure) { throw new AssertionError("construction site receipt regression", failure); }
        BlueprintRuntimeEntryTest.main(args);
        BuildingStatePolicyTest.main(args);
        BuildingSceneCompilerTest.main(args);
        BuildingSceneInspectionTest.main(args);
        // 快速图元、组件复制和原生状态变换先独立验证，再从公共建模入口检查保存与查询。
        BuildingModelShapeTest.main(args);
        BuildingConvexMeshTest.main(args);
        BuildingModelBlockStatesTest.main(args);
        BuildingModelSurfaceTest.main(args);
        // 网格孔洞与交替半砖也必须经由同一编译器展开，再检查组件变换和材料状态。
        BuildingModelPatternTest.main(args);
        try { BuildingModelContractTest.main(args); }
        catch (Exception failure) { throw new AssertionError("versioned building contract regression",failure); }
        try { BuildingSceneVersionRuntimeTest.main(args); }
        catch (Exception failure) { throw new AssertionError("versioned scene operation regression",failure); }
        try { BuildingDesignConcurrencyTest.main(args); }
        catch (Exception failure) { throw new AssertionError("concurrent building design regression",failure); }
        BuildingModelCompositionTest.main(args);
        BuildingModelGuardTest.main(args);
        BuildingModelInspectionTest.main(args);
        try { BuildingModelShowcaseTest.main(args); }
        catch (Exception failure) { throw new AssertionError("quick model showcase compilation", failure); }
        try { BuildingSceneV2StoreTest.main(args); }
        catch (Exception failure) { throw new AssertionError("component scene revision regression", failure); }
        BuildingModelV2PublicTest.main(args);
        try { BuildingModelV2RuntimeTest.main(args); }
        catch (Exception failure) { throw new AssertionError("quick model public preview and build regression", failure); }
        BuildingModelPublicTest.main(args);
        try { BuildingSceneStoreTest.main(args); }
        catch (Exception failure) { throw new AssertionError("building scene revision regression", failure); }
        try { BuildingSceneRuntimeTest.main(args); }
        catch (Exception failure) { throw new AssertionError("building model entry and export regression", failure); }
        try { BuildProjectContinuationTest.main(args); }
        catch (Exception failure) { throw new AssertionError("durable building continuation regression", failure); }
        MachineBlueprintDocumentTest.main(args);
        MachineBeltAssemblyTest.main(args);
        MachineBeltRoutesTest.main(args);
        MachinePlacementDependenciesTest.main(args);
        // 地图差异是只读现场事实，不能因未加载或只读了一页就宣称整机匹配蓝图。
        try { MachineBlueprintDiffTest.main(args); }
        catch (Exception failure) { throw new AssertionError("machine blueprint comparison", failure); }
        MachineBuildEvidenceTest.main(args);
        try { MachineCompletionArchiveTest.main(args); }
        catch (Exception failure) { throw new AssertionError("automatic machine archive", failure); }
        try { MachineWorldBlueprintTest.main(args); }
        catch (Exception failure) { throw new AssertionError("machine as-built capture", failure); }
        try { MachineInspectionModesTest.main(args); }
        catch (Exception failure) { throw new AssertionError("machine inspection modes", failure); }
        MachineDesignRejectionTest.main(args);
        MachineBuildCompletionTest.main(args);
        MachinePlanningBudgetTest.main(args);
        MachineLayoutAeNetworksTest.main(args);
        MachineLayoutInstanceIdentityTest.main(args);
        MachineLayoutItemOutputsTest.main(args);
        MachineAssemblyTest.main(args);
        MachineControlTest.main(args);
        MachineMenuPolicyTest.main(args);
        try { MachineMenuObservationTest.main(args); }
        catch (Exception failure) { throw new AssertionError("menu observation regression", failure); }
        MachineBlueprintStateTest.main(args);
        MachineConstructionPlanTest.main(args);
        MachineBlueprintGeometryTest.main(args);
        BuildExecutionContextTest.main(args);
        BuildBatchCompletionTest.main(args);
        try { MaterialSupplyReceiptTest.main(args); }
        catch (Exception failure) { throw new AssertionError(failure); }
        try { AcquisitionSettlementTest.main(args); }
        catch (Exception failure) { throw new AssertionError(failure); }
        try { BuildSupplyPreviewTest.main(args); }
        catch (Exception failure) { throw new AssertionError("preview before material supply", failure); }
        // 作者蓝图预览必须保持只读；模板生成与外出找地已不属于建筑入口。
        try { BuildDesignPreviewTest.main(args); }
        catch (Exception failure) { throw new AssertionError("read-only build preview regression", failure); }
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
                TaskResult.cancelled("cancelled"),
                TaskResult.fail("partial deposit", Map.of(
                        "actual_player_delta", -2, "outcome_uncertain", true,
                        "mechanical_retry_allowed", false, "slot", 3)));
        JsonObject interruption = JsonParser.parseString(interrupted.toJson()).getAsJsonObject();
        JsonObject childData = interruption.getAsJsonObject("data").getAsJsonObject("interrupted_child").getAsJsonObject("data");
        if (!interrupted.interrupted() || interrupted.success() || childData.get("actual_player_delta").getAsInt() != -2
                || childData.has("slot") || !interruption.getAsJsonObject("data").get("outcome_uncertain").getAsBoolean()) {
            throw new AssertionError("Cancellation lost or leaked native partial-effect evidence");
        }
        WorkToolPreparationTest.main(args);
        SemanticInteractionToolTest.main(args);
        ToolRegistryTest.main(args);
        NearbySignPerceptionTest.main(args);
        PerceiveSectionsTest.main(args);
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
