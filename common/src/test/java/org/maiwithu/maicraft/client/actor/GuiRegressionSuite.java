// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

/** Actual visibility-policy checks and architecture guards; live UI clicks still need a game. */
public final class GuiRegressionSuite {
    public static void main(String[] args) throws Exception {
        org.maiwithu.maicraft.client.chat.ChatTypingTest.main(args);
        ChatSessionTest.main(args);
        org.maiwithu.maicraft.intent.ChatAbilityTest.main(args);
        MenuVisibilityTest.main(args);
        WindowControlTest.main(args);
        ControlProtocolTest.main(args);
        BlockUseConfirmationTest.main(args);
        BlockUsePostureSyncTest.main(args);
        BodyControlInputTest.main(args);
        BodyPostureObservationTest.main(args);
        BodyCameraSmoothingTest.main(args);
        EquipRoutingTest.main(args);
        org.maiwithu.maicraft.intent.ExactInteractionTargetTest.main(args);
        org.maiwithu.maicraft.intent.ShipTravelContractTest.main(args);
        BucketInteractionRayTest.main(args);
        PreviewControlTest.main(args);
        org.maiwithu.maicraft.client.preview.PreviewSessionTest.main(args);
        org.maiwithu.maicraft.client.preview.PreviewVisibilityTest.main(args);
        org.maiwithu.maicraft.client.preview.PreviewRefreshTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildPreviewCancellationTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildPlacementStageTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildTemporarySupportPlanTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildSupportAccessTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildExecutionPacingTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildPlacementGestureTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildAimRetryTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildPrecisionAimTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildPlacementSettlingTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildPlacementDestinationTest.main(args);
        org.maiwithu.maicraft.core.task.build.NativePlacementDirectionsTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildPlacementBudgetTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildTaskSearchBudgetTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildPlacementConfirmationTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildDoorStateRepairTest.main(args);
        // 门的原生门轴与两半生成结果要结合作者要求确认，生存模式还须等一件物品实际扣除。
        org.maiwithu.maicraft.core.task.build.BuildDoorPlacementPredictionTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildFailureEvidenceTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildWorksitePlannerTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildStanceNavigationTest.main(args);
        // 精确施工挪位保持步行，已证明能连续走通的路段不逐格停开导航。
        org.maiwithu.maicraft.core.task.build.BuildWorksiteRouteTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildNavigationRetryTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildLayerFrontierTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildSiteConstraintsTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildExcavationFrontierTest.main(args);
        org.maiwithu.maicraft.core.task.container.ContainerBatchReplanTest.main(args);
        org.maiwithu.maicraft.core.task.container.ContainerSplitPlannerTest.main(args);
        org.maiwithu.maicraft.core.task.container.ContainerSplitTransferTest.main(args);
        org.maiwithu.maicraft.core.task.container.ContainerSupplySourcesTest.main(args);
        // AE 终端共享地标与访问保护，但不能被误当作普通箱子使用虚拟库存槽。
        org.maiwithu.maicraft.core.task.container.ContainerAccessPolicyTest.main(args);
        org.maiwithu.maicraft.core.task.acquire.OrdinaryStorageAcquireTest.main(args);
        // 出坑后补料应找到较远的已加载仓库，同时保持采矿范围和主人指定的查找距离。
        org.maiwithu.maicraft.core.task.acquire.StorageSupplyRadiusTest.main(args);
        org.maiwithu.maicraft.core.integration.ultimine.UltimineSelectionPolicyTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildExcavationSpoilSupplyTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildExcavationCargoTest.main(args);
        // 续建先保留建材与垫脚储备，再把普通土石存进有空位的仓库；没有确认回执不能继续取料。
        org.maiwithu.maicraft.core.task.build.BuildExcavationCargoRecoveryTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildExcavationSpoilBoundaryTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildAeSpoilReceiptTest.main(args);
        org.maiwithu.maicraft.core.task.supply.BuildSupplyCargoDispatchTest.main(args);
        // 没仓库时区分可延期整理与真正满包；已确认存入不能掩盖之后的放置未知。
        org.maiwithu.maicraft.core.task.supply.BuildSupplyCargoDeferralTest.main(args);
        org.maiwithu.maicraft.core.task.supply.BuildSupplyUncertaintyTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildSupplyAccessTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildFoodPreparationTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildFoodBoundaryTest.main(args);
        org.maiwithu.maicraft.core.task.supply.BuildSupplyAccessDispatchTest.main(args);
        org.maiwithu.maicraft.client.actor.MachineMenuHandParkingTest.main(args);
        org.maiwithu.maicraft.client.actor.ItemUseTimingTest.main(args);
        org.maiwithu.maicraft.core.task.acquire.ObservedRecipeStockCostTest.main(args);
        org.maiwithu.maicraft.core.task.container.ObservedContainerStockTest.main(args);
        org.maiwithu.maicraft.core.task.container.ContainerDepositCapacityTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildRegionsTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildFootingTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildSharedWorksiteTest.main(args);
        org.maiwithu.maicraft.core.task.build.CreativeBuildInventoryTest.main(args);
        org.maiwithu.maicraft.core.task.build.CreativeBuildRetentionTest.main(args);
        CreativeBuildSupplyMenuTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildScaffoldLedgerTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildScaffoldDropSafetyTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildTemporarySupportMaterialsTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildScaffoldCleanupTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildScaffoldCleanupGuardTest.main(args);
        org.maiwithu.maicraft.core.task.build.MachineSealingTest.main(args);
        CompanionCancellationTest.main(args);
        SleepSafetyTest.main(args);
        FishingBiteTest.main(args);
        org.maiwithu.maicraft.core.inventory.StockEvidenceTest.main(args);
        org.maiwithu.maicraft.core.integration.create.CreateStockObservationTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2ScreenAccessTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2WaterBucketFillTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2InPlaceSupplyTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2LandingWaterFallbackTest.main(args);
        org.maiwithu.maicraft.core.pathing.baritone.landing.LandingMaterialSupplyTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2FixedTerminalDiscoveryTest.main(args);
        // 存入经真实玩家槽和可见网络库存双向确认，并遵守终端范围、命名物品和原生界面边界。
        org.maiwithu.maicraft.core.integration.ae2.Ae2DepositLedgerTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2DepositTransferTest.main(args);
        org.maiwithu.maicraft.core.integration.ae2.Ae2DepositAccessPolicyTest.main(args);
        VisibleMenuSessionTest.main(args);
        LegacyInventoryBoundaryTest.main(args);
        GuiBoundaryAuditTest.main(args);
        System.out.println("GuiRegressionSuite: passed");
    }
}
