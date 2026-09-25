// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import org.maiwithu.maicraft.task.TaskSlotFailureTest;
import org.maiwithu.maicraft.client.chat.ChatTypingTest;
import org.maiwithu.maicraft.client.preview.PreviewRefreshTest;
import org.maiwithu.maicraft.client.preview.PreviewSessionTest;
import org.maiwithu.maicraft.client.preview.PreviewVisibilityTest;
import org.maiwithu.maicraft.core.integration.ae2.Ae2DepositAccessPolicyTest;
import org.maiwithu.maicraft.core.integration.ae2.Ae2DepositHostAccessTest;
import org.maiwithu.maicraft.core.integration.ae2.Ae2DepositLedgerTest;
import org.maiwithu.maicraft.core.integration.ae2.Ae2DepositTransferTest;
import org.maiwithu.maicraft.core.integration.ae2.Ae2DepositVisibilityTest;
import org.maiwithu.maicraft.core.integration.ae2.Ae2FixedTerminalDiscoveryTest;
import org.maiwithu.maicraft.core.integration.ae2.Ae2InPlaceSupplyTest;
import org.maiwithu.maicraft.core.integration.ae2.Ae2LandingWaterFallbackTest;
import org.maiwithu.maicraft.core.integration.ae2.Ae2ScreenAccessTest;
import org.maiwithu.maicraft.core.integration.ae2.Ae2WaterBucketFillTest;
import org.maiwithu.maicraft.core.integration.create.CreateStockObservationTest;
import org.maiwithu.maicraft.core.integration.ultimine.UltimineSelectionPolicyTest;
import org.maiwithu.maicraft.core.inventory.StockEvidenceTest;
import org.maiwithu.maicraft.core.pathing.baritone.landing.LandingMaterialSupplyTest;
import org.maiwithu.maicraft.core.task.acquire.ObservedRecipeStockCostTest;
import org.maiwithu.maicraft.core.task.acquire.RecipeMaterialPlanTest;
import org.maiwithu.maicraft.core.task.acquire.AcquisitionWirelessInventoryTest;
import org.maiwithu.maicraft.core.integration.ae2.Ae2StockObservationTest;
import org.maiwithu.maicraft.core.integration.ae2.Ae2TerminalIdentityTest;
import org.maiwithu.maicraft.core.task.acquire.AcquisitionSourceInheritanceTest;
import org.maiwithu.maicraft.core.task.mine.MiningSearchScopeTest;
import org.maiwithu.maicraft.core.task.acquire.AcquisitionRecipePlanningTest;
import org.maiwithu.maicraft.core.task.cook.CookingFuelTest;
import org.maiwithu.maicraft.core.task.cook.CookingBatchTest;
import org.maiwithu.maicraft.core.task.cook.CookingStockBudgetTest;
import org.maiwithu.maicraft.core.task.cook.CookingPreparationEffectsTest;
import org.maiwithu.maicraft.core.task.container.QuickMoveEvidenceTest;
import org.maiwithu.maicraft.core.task.cook.CookingOutputReceiptTest;
import org.maiwithu.maicraft.core.task.cook.CookingSynchronizationTest;
import org.maiwithu.maicraft.core.task.cook.CookingStationSelectionTest;
import org.maiwithu.maicraft.core.task.cook.CookingProtectionTest;
import org.maiwithu.maicraft.core.task.cook.CookingQuantityTest;
import org.maiwithu.maicraft.core.task.cook.CookingMenuCloseTest;
import org.maiwithu.maicraft.core.task.cook.CookingMenuOwnershipTest;
import org.maiwithu.maicraft.core.task.cook.CookingSettlementTest;
import org.maiwithu.maicraft.core.task.acquire.OrdinaryStorageAcquireTest;
import org.maiwithu.maicraft.core.task.acquire.StorageSupplyRadiusTest;
import org.maiwithu.maicraft.core.task.build.BuildAeSpoilReceiptTest;
import org.maiwithu.maicraft.core.task.build.BuildAimRetryTest;
import org.maiwithu.maicraft.core.task.build.BuildAnchorDriftTest;
import org.maiwithu.maicraft.core.task.build.BuildBasementSupportAccessTest;
import org.maiwithu.maicraft.core.task.build.BuildDoorPlacementPredictionTest;
import org.maiwithu.maicraft.core.task.build.BuildDoorStateRepairTest;
import org.maiwithu.maicraft.core.task.build.BuildEavePlacementProofTest;
import org.maiwithu.maicraft.core.task.build.BuildEdgeHandoffTest;
import org.maiwithu.maicraft.core.task.build.BuildEdgeMotionTest;
import org.maiwithu.maicraft.core.task.build.BuildExcavationCargoRecoveryTest;
import org.maiwithu.maicraft.core.task.build.BuildExcavationCargoTest;
import org.maiwithu.maicraft.core.task.build.BuildExcavationFrontierTest;
import org.maiwithu.maicraft.core.task.build.BuildExcavationSpoilBoundaryTest;
import org.maiwithu.maicraft.core.task.build.BuildExcavationSpoilSupplyTest;
import org.maiwithu.maicraft.core.task.build.BuildExecutionPacingTest;
import org.maiwithu.maicraft.core.task.build.BuildFailureEvidenceTest;
import org.maiwithu.maicraft.core.task.build.BuildFoodBoundaryTest;
import org.maiwithu.maicraft.core.task.build.BuildFoodPreparationTest;
import org.maiwithu.maicraft.core.task.build.BuildFootingTest;
import org.maiwithu.maicraft.core.task.build.BuildLayerFrontierTest;
import org.maiwithu.maicraft.core.task.build.BuildNavigationRetryTest;
import org.maiwithu.maicraft.core.task.build.BuildPlacementAccessBoundsTest;
import org.maiwithu.maicraft.core.task.build.BuildPlacementBudgetTest;
import org.maiwithu.maicraft.core.task.build.BuildPlacementConfirmationTest;
import org.maiwithu.maicraft.core.task.build.BuildPlacementDestinationTest;
import org.maiwithu.maicraft.core.task.build.BuildPlacementFootingTest;
import org.maiwithu.maicraft.core.task.build.BuildPlacementGestureTest;
import org.maiwithu.maicraft.core.task.build.BuildPlacementPlayerRotationTest;
import org.maiwithu.maicraft.core.task.build.BuildPlacementSettlingTest;
import org.maiwithu.maicraft.core.task.build.BuildPlacementStageTest;
import org.maiwithu.maicraft.core.task.build.BuildPlacementStandingAccessTest;
import org.maiwithu.maicraft.core.task.build.BuildPrecisionAimTest;
import org.maiwithu.maicraft.core.task.build.BuildPreviewCancellationTest;
import org.maiwithu.maicraft.core.task.build.BuildProjectRevisionTest;
import org.maiwithu.maicraft.core.task.build.BuildProjectScaffoldPersistenceTest;
import org.maiwithu.maicraft.core.task.build.BuildRegionsTest;
import org.maiwithu.maicraft.core.task.build.BuildRoofStairAccessTest;
import org.maiwithu.maicraft.core.task.build.BuildScaffoldCleanupAccessTest;
import org.maiwithu.maicraft.core.task.build.BuildScaffoldCleanupGuardTest;
import org.maiwithu.maicraft.core.task.build.BuildScaffoldCleanupReceiptTest;
import org.maiwithu.maicraft.core.task.build.BuildScaffoldCleanupScheduleTest;
import org.maiwithu.maicraft.core.task.build.BuildScaffoldCleanupTest;
import org.maiwithu.maicraft.core.task.build.BuildScaffoldDescentBoundsTest;
import org.maiwithu.maicraft.core.task.build.BuildScaffoldDescentStopTest;
import org.maiwithu.maicraft.core.task.build.BuildScaffoldDescentTest;
import org.maiwithu.maicraft.core.task.build.BuildScaffoldNearMachineTest;
import org.maiwithu.maicraft.core.task.build.BuildScaffoldLedgerTest;
import org.maiwithu.maicraft.core.task.build.BuildSharedWorksiteTest;
import org.maiwithu.maicraft.core.task.build.BuildSiteConstraintsTest;
import org.maiwithu.maicraft.core.task.build.BuildClearanceSurveyTest;
import org.maiwithu.maicraft.core.task.build.BuildClearanceExecutionTest;
import org.maiwithu.maicraft.core.task.build.BuildStanceNavigationTest;
import org.maiwithu.maicraft.core.task.build.BuildSupplyAccessTest;
import org.maiwithu.maicraft.core.task.build.BuildSupportAccessTest;
import org.maiwithu.maicraft.core.task.build.BuildSupportApproachTest;
import org.maiwithu.maicraft.core.task.build.BuildSupportSchedulingTest;
import org.maiwithu.maicraft.core.task.build.BuildSupportSettlingTest;
import org.maiwithu.maicraft.core.task.build.BuildTaskSearchBudgetTest;
import org.maiwithu.maicraft.core.task.build.BuildTemporarySupportMaterialsTest;
import org.maiwithu.maicraft.core.task.build.BuildTemporarySupportPlanTest;
import org.maiwithu.maicraft.core.task.build.BuildWorksitePlannerTest;
import org.maiwithu.maicraft.core.task.build.BuildWorksiteRouteTest;
import org.maiwithu.maicraft.core.task.build.CreativeBuildInventoryTest;
import org.maiwithu.maicraft.core.task.build.CreativeBuildRetentionTest;
import org.maiwithu.maicraft.core.task.build.MachineSealingTest;
import org.maiwithu.maicraft.core.task.build.NativePlacementDirectionsTest;
import org.maiwithu.maicraft.core.task.build.PlacementAttemptLedgerTest;
import org.maiwithu.maicraft.core.task.container.ContainerAccessPolicyTest;
import org.maiwithu.maicraft.core.task.container.ContainerBatchReplanTest;
import org.maiwithu.maicraft.core.task.container.ContainerDepositCapacityTest;
import org.maiwithu.maicraft.core.task.container.ContainerSplitPlannerTest;
import org.maiwithu.maicraft.core.task.container.ContainerSplitTransferTest;
import org.maiwithu.maicraft.core.task.container.ContainerSupplySourcesTest;
import org.maiwithu.maicraft.core.task.container.ObservedContainerStockTest;
import org.maiwithu.maicraft.core.task.supply.BuildSupplyAccessDispatchTest;
import org.maiwithu.maicraft.core.task.supply.BuildSupplyCargoDeferralTest;
import org.maiwithu.maicraft.core.task.supply.BuildSupplyCargoDispatchTest;
import org.maiwithu.maicraft.core.task.supply.BuildSupplyHandoffTest;
import org.maiwithu.maicraft.core.task.supply.BuildSupplyUncertaintyTest;
import org.maiwithu.maicraft.core.task.supply.MaterialSupplyReturnPolicyTest;
import org.maiwithu.maicraft.intent.ChatAbilityTest;
import org.maiwithu.maicraft.intent.AcquireGoalTest;
import org.maiwithu.maicraft.intent.CookGoalTest;
import org.maiwithu.maicraft.intent.ExactInteractionTargetTest;
import org.maiwithu.maicraft.intent.ShipTravelContractTest;

/** 验证真实可见性策略和架构边界；实际界面点击仍需在游戏中测试。 */
public final class GuiRegressionSuite {
    public static void main(String[] args) throws Exception {
        // 接单失败后必须归还任务槽位，后继操作才能正常取得玩家身体。
        TaskSlotFailureTest.main(args);
        // 嵌套取材只扫描已声明的附近范围，空搜加工设备不能把整片已加载世界都扫一遍。
        MiningSearchScopeTest.main(args);
        // 聊天在真实发送前必须取得持久许可，重启后的旧编号不能变成第二条消息。
        ChatSubmissionHistoryTest.main(args);
        ChatTypingTest.main(args);
        ChatSessionTest.main(args);
        ChatAbilityTest.main(args);
        MenuVisibilityTest.main(args);
        MenuConfirmationLatencyTest.main(args);
        WindowControlTest.main(args);
        ControlProtocolTest.main(args);
        BlockUseConfirmationTest.main(args);
        BlockUsePostureSyncTest.main(args);
        BodyControlInputTest.main(args);
        BodyPostureObservationTest.main(args);
        BodyCameraSmoothingTest.main(args);
        EquipRoutingTest.main(args);
        ExactInteractionTargetTest.main(args);
        ShipTravelContractTest.main(args);
        BucketInteractionRayTest.main(args);
        PreviewControlTest.main(args);
        PreviewSessionTest.main(args);
        PreviewVisibilityTest.main(args);
        PreviewRefreshTest.main(args);
        BuildPreviewCancellationTest.main(args);
        BuildPlacementStageTest.main(args);
        BuildTemporarySupportPlanTest.main(args);
        BuildSupportAccessTest.main(args);
        // 几何辅助器单独保留样本回归；真实支撑调度直接排入普通施工，不以稳定证明作为开工条件。
        BuildSupportSettlingTest.main(args);
        BuildSupportSchedulingTest.main(args);
        // 远处支撑同样逐块接近并放置，不另设通行准备或整链证明阶段。
        BuildSupportApproachTest.main(args);
        BuildExecutionPacingTest.main(args);
        BuildPlacementGestureTest.main(args);
        BuildAimRetryTest.main(args);
        // 准心真实拒绝后同格只做少量微调，随后换脚下位置，防止用不同瞄点绕过重试预算。
        PlacementAttemptLedgerTest.main(args);
        BuildPlacementFootingTest.main(args);
        // 模组可能直接读玩家角度，候选放置也要按该视角求状态，同时保证真实角色完全不动。
        BuildPlacementPlayerRotationTest.main(args);
        BuildPrecisionAimTest.main(args);
        BuildPlacementSettlingTest.main(args);
        BuildPlacementDestinationTest.main(args);
        NativePlacementDirectionsTest.main(args);
        BuildPlacementBudgetTest.main(args);
        BuildTaskSearchBudgetTest.main(args);
        BuildPlacementConfirmationTest.main(args);
        BuildDoorStateRepairTest.main(args);
        // 门的原生门轴与两半生成结果要结合作者要求确认，生存模式还须等一件物品实际扣除。
        BuildDoorPlacementPredictionTest.main(args);
        BuildFailureEvidenceTest.main(args);
        BuildWorksitePlannerTest.main(args);
        BuildStanceNavigationTest.main(args);
        // 精确施工挪位保持步行，已证明能连续走通的路段不逐格停开导航。
        BuildWorksiteRouteTest.main(args);
        // 檐边末段使用真实潜行姿态与碰撞摩擦，保持部分足底支撑并能原路退回。
        BuildEdgeMotionTest.main(args);
        BuildEdgeMotionNativeTest.main(args);
        BuildEdgeHandoffTest.main(args);
        // 补齐半阶对齐的余速侧偏边界，主方向可走不代表惯性方向也可走。
        BuildAnchorDriftTest.main(args);
        BuildEavePlacementProofTest.main(args);
        // 正确朝向的屋顶楼梯可借已有结构升高贴边，不能把内侧已铺地板伪装成空气站位。
        BuildRoofStairAccessTest.main(args);
        // 内部楼梯的支撑可先从地下室地面放置，再回到较高阶面完成正式楼梯。
        BuildBasementSupportAccessTest.main(args);
        // 跨房间时搜索包含真实起点，缺口和保护仍会拒绝，不把超出范围误报成当前一格无路。
        BuildPlacementAccessBoundsTest.main(args);
        // 完整平台的普通站位与真正部分支撑的临边分别验收，避免站位偏移把整个施工过程变成潜行。
        BuildPlacementStandingAccessTest.main(args);
        BuildNavigationRetryTest.main(args);
        BuildLayerFrontierTest.main(args);
        BuildSiteConstraintsTest.main(args);
        // 施工遇到人工障碍时只给出经过整份蓝图核对的最近选址偏移，不在未知地形上猜测空地。
        BuildClearanceSurveyTest.main(args);
        // 现场在开工前或开挖途中出现人工方块时，实际施工任务也要停手并回报原坐标。
        BuildClearanceExecutionTest.main(args);
        BuildExcavationFrontierTest.main(args);
        ContainerBatchReplanTest.main(args);
        ContainerSplitPlannerTest.main(args);
        ContainerSplitTransferTest.main(args);
        ContainerSupplySourcesTest.main(args);
        // AE 终端共享地标与访问保护，但不能被误当作普通箱子使用虚拟库存槽。
        ContainerAccessPolicyTest.main(args);
        OrdinaryStorageAcquireTest.main(args);
        // 出坑后补料应找到较远的已加载仓库，同时保持采矿范围和主人指定的查找距离。
        StorageSupplyRadiusTest.main(args);
        UltimineSelectionPolicyTest.main(args);
        BuildExcavationSpoilSupplyTest.main(args);
        BuildExcavationCargoTest.main(args);
        // 续建先保留建材与垫脚储备，再把普通土石存进有空位的仓库；没有确认回执不能继续取料。
        BuildExcavationCargoRecoveryTest.main(args);
        BuildExcavationSpoilBoundaryTest.main(args);
        BuildAeSpoilReceiptTest.main(args);
        BuildSupplyCargoDispatchTest.main(args);
        // 没仓库时区分可延期整理与真正满包；已确认存入不能掩盖之后的放置未知。
        BuildSupplyCargoDeferralTest.main(args);
        BuildSupplyUncertaintyTest.main(args);
        BuildSupplyAccessTest.main(args);
        BuildFoodPreparationTest.main(args);
        BuildFoodBoundaryTest.main(args);
        BuildSupplyAccessDispatchTest.main(args);
        // 建筑拿齐材料后在仓库交还控制，不要求普通导航重新爬回墙顶；通用机器任务仍保持返程规则。
        MaterialSupplyReturnPolicyTest.main(args);
        BuildSupplyHandoffTest.main(args);
        MachineMenuHandParkingTest.main(args);
        FirstPersonGateExtendedHotbarTest.main(args);
        ItemUseTimingTest.main(args);
        ObservedRecipeStockCostTest.main(args);
        RecipeMaterialPlanTest.main(args);
        AcquisitionWirelessInventoryTest.main(args);
        Ae2StockObservationTest.main(args);
        // 终端交换遇到原生耗电仍应确认，身份或数量改变则保留不确定结果。
        Ae2TerminalIdentityTest.main(args);
        ObservedContainerStockTest.main(args);
        ContainerDepositCapacityTest.main(args);
        BuildRegionsTest.main(args);
        BuildFootingTest.main(args);
        BuildSharedWorksiteTest.main(args);
        CreativeBuildInventoryTest.main(args);
        CreativeBuildRetentionTest.main(args);
        CreativeBuildSupplyMenuTest.main(args);
        BuildScaffoldLedgerTest.main(args);
        // 重启只恢复本项目记录且现场仍匹配的支撑，不能扫描泥土冒认所有权。
        BuildProjectScaffoldPersistenceTest.main(args);
        // 明确采用新场景时保留旧项目支撑，并拒绝不匹配父版本或改变过的原生证据。
        BuildProjectRevisionTest.main(args);
        // 机器旁需要垫块时先进入施工，验证不再因预计拆除掉落而拒绝支撑。
        BuildScaffoldNearMachineTest.main(args);
        BuildTemporarySupportMaterialsTest.main(args);
        BuildScaffoldCleanupTest.main(args);
        BuildScaffoldCleanupGuardTest.main(args);
        BuildScaffoldCleanupScheduleTest.main(args);
        BuildScaffoldCleanupReceiptTest.main(args);
        // 高处清理先证明整柱与退路，再验原生确认和逐格落稳，不能把计划当成实际下降。
        BuildScaffoldDescentTest.main(args);
        BuildScaffoldDescentBoundsTest.main(args);
        BuildScaffoldCleanupAccessTest.main(args);
        BuildScaffoldDescentStopTest.main(args);
        MachineSealingTest.main(args);
        CompanionCancellationTest.main(args);
        SleepSafetyTest.main(args);
        FishingBiteTest.main(args);
        StockEvidenceTest.main(args);
        // 补工具也必须遵守本次取材范围，不能因为看过仓库库存就偷偷开箱或制造。
        AcquisitionSourceInheritanceTest.main(args);
        AcquisitionRecipePlanningTest.main(args);
        CookingFuelTest.main(args);
        CookingBatchTest.main(args);
        CookingStockBudgetTest.main(args);
        CookingPreparationEffectsTest.main(args);
        QuickMoveEvidenceTest.main(args);
        CookingOutputReceiptTest.main(args);
        CookingSynchronizationTest.main(args);
        CookingStationSelectionTest.main(args);
        CookingProtectionTest.main(args);
        CookingQuantityTest.main(args);
        CookingMenuCloseTest.main(args);
        CookingMenuOwnershipTest.main(args);
        CookingSettlementTest.main(args);
        AcquireGoalTest.main(args);
        CookGoalTest.main(args);
        CreateStockObservationTest.main(args);
        Ae2ScreenAccessTest.main(args);
        Ae2WaterBucketFillTest.main(args);
        Ae2InPlaceSupplyTest.main(args);
        Ae2LandingWaterFallbackTest.main(args);
        LandingMaterialSupplyTest.main(args);
        Ae2FixedTerminalDiscoveryTest.main(args);
        // 存入经真实玩家槽和可见网络库存双向确认，并遵守终端范围、命名物品和原生界面边界。
        Ae2DepositLedgerTest.main(args);
        Ae2DepositTransferTest.main(args);
        Ae2DepositAccessPolicyTest.main(args);
        // 原生客户端不保存服务端 locator，仍须从当前真实终端宿主证明来源和访问边界。
        Ae2DepositHostAccessTest.main(args);
        // 每次新存入都等真实菜单的新画面，前置检查未通过不能记成已经提交点击。
        Ae2DepositVisibilityTest.main(args);
        VisibleMenuSessionTest.main(args);
        LegacyInventoryBoundaryTest.main(args);
        GuiBoundaryAuditTest.main(args);
        System.out.println("GuiRegressionSuite: passed");
    }
}
