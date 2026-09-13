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
        // 从实机下台阶与惯性样本复核：先自然落稳再建立支撑快照，真实环境变化不能借身体重证绕过。
        org.maiwithu.maicraft.core.task.build.BuildSupportSettlingTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildSupportSchedulingTest.main(args);
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
        // 檐边末段使用真实潜行姿态与碰撞摩擦，保持部分足底支撑并能原路退回。
        org.maiwithu.maicraft.core.task.build.BuildEdgeMotionTest.main(args);
        BuildEdgeMotionNativeTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildEdgeHandoffTest.main(args);
        // 补齐半阶对齐的余速侧偏边界，主方向可走不代表惯性方向也可走。
        org.maiwithu.maicraft.core.task.build.BuildAnchorDriftTest.main(args);
        org.maiwithu.maicraft.core.task.build.BuildEavePlacementProofTest.main(args);
        // 正确朝向的屋顶楼梯可借已有结构升高贴边，不能把内侧已铺地板伪装成空气站位。
        org.maiwithu.maicraft.core.task.build.BuildRoofStairAccessTest.main(args);
        // 内部楼梯的支撑可先从地下室地面放置，再回到较高阶面完成正式楼梯。
        org.maiwithu.maicraft.core.task.build.BuildBasementSupportAccessTest.main(args);
        // 跨房间时搜索包含真实起点，缺口和保护仍会拒绝，不把超出范围误报成当前一格无路。
        org.maiwithu.maicraft.core.task.build.BuildPlacementAccessBoundsTest.main(args);
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
        // 建筑拿齐材料后在仓库交还控制，不要求普通导航重新爬回墙顶；通用机器任务仍保持返程规则。
        org.maiwithu.maicraft.core.task.supply.MaterialSupplyReturnPolicyTest.main(args);
        org.maiwithu.maicraft.core.task.supply.BuildSupplyHandoffTest.main(args);
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
        // 重启只恢复本项目记录且现场仍匹配的支撑，不能扫描泥土冒认所有权。
        org.maiwithu.maicraft.core.task.build.BuildProjectScaffoldPersistenceTest.main(args);
        // 明确采用新场景时保留旧项目支撑，并拒绝不匹配父版本或改变过的原生证据。
        org.maiwithu.maicraft.core.task.build.BuildProjectRevisionTest.main(args);
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
        // 原生客户端不保存服务端 locator，仍须从当前真实终端宿主证明来源和访问边界。
        org.maiwithu.maicraft.core.integration.ae2.Ae2DepositHostAccessTest.main(args);
        // 每次新存入都等真实菜单的新画面，前置检查未通过不能记成已经提交点击。
        org.maiwithu.maicraft.core.integration.ae2.Ae2DepositVisibilityTest.main(args);
        VisibleMenuSessionTest.main(args);
        LegacyInventoryBoundaryTest.main(args);
        GuiBoundaryAuditTest.main(args);
        System.out.println("GuiRegressionSuite: passed");
    }
}
